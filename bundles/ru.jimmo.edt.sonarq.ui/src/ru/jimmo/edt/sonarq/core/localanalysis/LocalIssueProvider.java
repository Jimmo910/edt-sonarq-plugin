/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.eclipse.core.runtime.IProgressMonitor;

import ru.jimmo.edt.sonarq.core.analysis.TimeoutDownloads;
import ru.jimmo.edt.sonarq.core.client.SonarServerException;
import ru.jimmo.edt.sonarq.core.model.BranchInfo;
import ru.jimmo.edt.sonarq.core.model.IssueQuery;
import ru.jimmo.edt.sonarq.core.model.IssueSnapshot;
import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarRule;
import ru.jimmo.edt.sonarq.core.provider.IIssueProvider;
import ru.jimmo.edt.sonarq.core.scope.ChangedLines;
import ru.jimmo.edt.sonarq.core.scope.ChangedLinesIssueFilter;
import ru.jimmo.edt.sonarq.core.scope.GitChangedLines;

/**
 * Loads issues by running the BSL Language Server locally against a project's sources, instead of
 * querying a SonarQube server.
 *
 * <p>{@code projectRoot} is the EDT project location. If it has a {@code src} subdirectory (the
 * conventional layout of a 1C configuration) that subdirectory is analyzed; otherwise the project root
 * itself is analyzed. Either way {@code projectRoot} is passed as the {@code uriBasePrefix} argument to
 * {@link SarifParser#parse(String, String, String)}: the language server reports absolute
 * {@code file:///} artifact locations, and relativizing them against the project root turns them into
 * {@code src/...} paths, so component keys come out as {@code <projectKey>:src/...} — matching the
 * mapping server-backed issues use for markers and navigation.
 *
 * <p>Every {@link #fetchIssues(IssueQuery, IProgressMonitor)} call resolves the language server
 * executable (a user override, or a managed download via {@link BslServerInstaller}), rewrites the
 * bundled launcher's pinned JVM heap limit to {@link #maxHeapGb} (best-effort - see
 * {@link BslServerInstaller#configureHeap}), recreates a clean report directory, runs the analysis and
 * parses the resulting report, caching its rule descriptions for {@link #describeRule(String)}. Branches
 * are not a local-analysis concept: {@link #listBranches(String)} always returns an empty list and
 * {@link #branchAnalysisSupported()} always returns {@code false}.
 *
 * <p>A generated checks configuration ({@code configPath}) is passed to the language server, but a
 * project-local {@code .bsl-language-server.json} always takes priority — looked up first under
 * {@code projectRoot}, then under the analyzed source directory — and, when found, is passed explicitly via
 * the {@code --configuration} flag instead of {@code configPath}. Live verification (2026-07-17) showed the
 * language server only auto-discovers this file relative to the analyzed source directory, never relative to
 * {@code projectRoot}: leaving discovery to the launcher would silently ignore a {@code projectRoot}-level
 * file, so the project file is always passed explicitly, guaranteeing it applies regardless of the launcher's
 * discovery quirks.
 *
 * <p>After a successful analysis, the parsed report's rule descriptions are persisted as a
 * {@link DiagnosticsCatalog} into the state directory, best-effort: a settings page can list every known
 * diagnostic key/name without re-running an analysis, but a failure to write that cache must never fail the
 * fetch itself, so any {@link IOException} from the write is swallowed.
 *
 * <p>When {@code baseBranch} is non-blank, the parsed issues are additionally filtered down to those on
 * lines changed relative to that branch (see {@link ChangedLinesIssueFilter}). This post-filter degrades to
 * keeping every issue when the base cannot be resolved (no repository, unknown ref) and, unlike the
 * generated checks configuration, always applies - even when the project ships its own
 * {@code .bsl-language-server.json}. The subsystem filter, by contrast, is applied upstream: it is baked
 * into the generated {@code configPath} configuration and is therefore skipped whenever a project-local
 * configuration file takes priority, exactly like the disabled-diagnostics filter.
 */
public final class LocalIssueProvider implements IIssueProvider
{
    private static final String BSL_REPORT_DIR = "bsl-report"; //$NON-NLS-1$
    private static final String SRC_DIR_NAME = "src"; //$NON-NLS-1$
    private static final String PROJECT_CONFIG_FILE_NAME = ".bsl-language-server.json"; //$NON-NLS-1$
    private static final String EMPTY_DESCRIPTION = ""; //$NON-NLS-1$
    private static final int MAX_DIR_NAME_LENGTH = 80;
    // Coarse, two-phase progress for the Progress view. This class has no NLS/logging facility of its own
    // (see the class javadoc), so - like ProcessAnalyzeRunner's out-of-memory hint - these are plain English
    // literals rather than Messages keys.
    private static final String PROGRESS_TASK_NAME = "Local BSL analysis"; //$NON-NLS-1$
    private static final String PROGRESS_PREPARE_ENGINE = "Preparing analysis engine"; //$NON-NLS-1$
    private static final String PROGRESS_ANALYZING = "Analyzing sources"; //$NON-NLS-1$
    private static final int PROGRESS_TOTAL_WORK = 2;

    private final String projectKey;
    private final Path projectRoot;
    private final Path stateDir;
    private final Path serverOverride;
    private final Path configPath;
    private final String baseBranch;
    private final int maxHeapGb;
    private final AnalyzeRunner runner;
    private final BiFunction<File, String, ChangedLines> changedLinesSource;

    private volatile Map<String, SonarRule> ruleCache = Map.of();

    /**
     * Creates the provider.
     *
     * @param projectKey the SonarQube project key used to build component keys, not {@code null}
     * @param projectRoot the EDT project location to analyze, not {@code null}
     * @param stateDir the plugin state directory to install the language server under and write reports
     *     into, not {@code null}
     * @param serverOverride a user-provided BSL Language Server executable, or {@code null} to use the
     *     managed download
     * @param configPath a generated checks configuration file to pass to the language server, or
     *     {@code null} to run with its defaults; overridden when a project-local
     *     {@code .bsl-language-server.json} is found under {@code projectRoot} or the analyzed source
     *     directory, which is then passed explicitly instead
     * @param baseBranch the git base branch or commit to filter changed lines against, not {@code null};
     *     blank means no base-branch filtering
     * @param maxHeapGb the maximum JVM heap, in gigabytes, to configure the bundled BSL Language Server
     *     with before each analysis (see {@link BslServerInstaller#configureHeap})
     * @param runner runs the headless analysis, not {@code null}
     */
    public LocalIssueProvider(String projectKey, Path projectRoot, Path stateDir, Path serverOverride,
        Path configPath, String baseBranch, int maxHeapGb, AnalyzeRunner runner)
    {
        this(projectKey, projectRoot, stateDir, serverOverride, configPath, baseBranch, maxHeapGb, runner,
            GitChangedLines::compute);
    }

    /**
     * Creates the provider with an injectable changed-lines source, for tests.
     *
     * @param projectKey the SonarQube project key used to build component keys, not {@code null}
     * @param projectRoot the EDT project location to analyze, not {@code null}
     * @param stateDir the plugin state directory to install the language server under and write reports
     *     into, not {@code null}
     * @param serverOverride a user-provided BSL Language Server executable, or {@code null} to use the
     *     managed download
     * @param configPath a generated checks configuration file to pass to the language server, or
     *     {@code null} to run with its defaults; overridden when a project-local
     *     {@code .bsl-language-server.json} is found under {@code projectRoot} or the analyzed source
     *     directory, which is then passed explicitly instead
     * @param baseBranch the git base branch or commit to filter changed lines against, not {@code null};
     *     blank means no base-branch filtering
     * @param maxHeapGb the maximum JVM heap, in gigabytes, to configure the bundled BSL Language Server
     *     with before each analysis (see {@link BslServerInstaller#configureHeap})
     * @param runner runs the headless analysis, not {@code null}
     * @param changedLinesSource resolves the changed lines for a work-tree directory and base ref, not
     *     {@code null}; production code always passes {@link GitChangedLines#compute(File, String)}
     */
    LocalIssueProvider(String projectKey, Path projectRoot, Path stateDir, Path serverOverride, Path configPath,
        String baseBranch, int maxHeapGb, AnalyzeRunner runner,
        BiFunction<File, String, ChangedLines> changedLinesSource)
    {
        this.projectKey = projectKey;
        this.projectRoot = projectRoot;
        this.stateDir = stateDir;
        this.serverOverride = serverOverride;
        this.configPath = configPath;
        this.baseBranch = baseBranch;
        this.maxHeapGb = maxHeapGb;
        this.runner = runner;
        this.changedLinesSource = changedLinesSource;
    }

    @Override
    public IssueSnapshot fetchIssues(IssueQuery query, IProgressMonitor monitor) throws SonarServerException
    {
        beginProgress(monitor);
        try
        {
            reportProgress(monitor, PROGRESS_PREPARE_ENGINE);
            Path executable = serverOverride != null
                ? serverOverride
                : BslServerInstaller.ensureServer(stateDir, TimeoutDownloads::open, monitor);
            configureHeapBestEffort();
            tick(monitor);
            Path srcDir = sourceDirectory();
            Path outputDir = stateDir.resolve(BSL_REPORT_DIR).resolve(safeDirName(projectKey));
            recreateOutputDir(outputDir);
            Path projectConfig = findProjectConfigFile(srcDir);
            Path effectiveConfigPath = projectConfig != null ? projectConfig : configPath;
            reportProgress(monitor, PROGRESS_ANALYZING);
            Path sarif = runner.analyze(executable, srcDir, outputDir, effectiveConfigPath, monitor);
            tick(monitor);
            SarifReport report = SarifParser.parse(Files.readString(sarif, StandardCharsets.UTF_8), projectKey,
                projectRoot.toString());
            ruleCache = report.rules();
            saveDiagnosticsCatalog(report);
            List<SonarIssue> issues = report.issues();
            if (baseBranch != null && !baseBranch.isBlank())
            {
                ChangedLines changed = changedLinesSource.apply(projectRoot.toFile(), baseBranch.trim());
                issues = ChangedLinesIssueFilter.keepChanged(issues, projectKey, projectRoot, changed);
            }
            return new IssueSnapshot(query, issues, issues.size(), Instant.now());
        }
        catch (IOException | UncheckedIOException e)
        {
            // recreateOutputDir and, transitively, BslServerInstaller#ensureServer walk a directory tree
            // with Files.walk; a delete racing an install can make that throw the unchecked
            // UncheckedIOException instead of IOException. Catching it alongside IOException here reports
            // the race as a normal SonarServerException instead of letting it escape as an unhandled
            // RuntimeException (review minor, issue #4/#5).
            throw new SonarServerException(e.getMessage(), e);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new SonarServerException(e.getMessage(), e);
        }
        finally
        {
            if (monitor != null)
            {
                monitor.done();
            }
        }
    }

    /**
     * Starts the coarse, two-phase progress reporting for one {@link #fetchIssues} call, best-effort.
     *
     * @param monitor the progress monitor, or {@code null} to skip progress reporting entirely
     */
    private static void beginProgress(IProgressMonitor monitor)
    {
        if (monitor != null)
        {
            monitor.beginTask(PROGRESS_TASK_NAME, PROGRESS_TOTAL_WORK);
        }
    }

    /**
     * Reports the start of a named phase, best-effort.
     *
     * @param monitor the progress monitor, or {@code null} to skip progress reporting entirely
     * @param phaseName the phase name to show, not {@code null}
     */
    private static void reportProgress(IProgressMonitor monitor, String phaseName)
    {
        if (monitor != null)
        {
            monitor.subTask(phaseName);
        }
    }

    /**
     * Marks one unit of the two-phase progress as complete, best-effort.
     *
     * @param monitor the progress monitor, or {@code null} to skip progress reporting entirely
     */
    private static void tick(IProgressMonitor monitor)
    {
        if (monitor != null)
        {
            monitor.worked(1);
        }
    }

    /**
     * The SonarQube project key this provider prefixes component keys with.
     *
     * <p>Exposed so callers that build the provider (the refresh-inputs factory) can assert which key was
     * resolved — in local analysis mode it is the binding's project key when configured, otherwise the
     * workspace project name.
     *
     * @return the project key, never {@code null}
     */
    public String projectKey()
    {
        return projectKey;
    }

    /**
     * The generated checks configuration file this provider was built with.
     *
     * <p>Exposed so callers that build the provider (the refresh-inputs factory) can assert which
     * configuration was generated for the current preferences, without running a fetch. This is the
     * constructor argument as given; a project-local {@code .bsl-language-server.json}, when found, still
     * takes priority at analysis time (see the class javadoc), but that override is resolved per-fetch and
     * is not reflected here.
     *
     * @return the generated checks configuration path, or {@code null} when the language server is meant
     *     to run with its defaults
     */
    public Path configPath()
    {
        return configPath;
    }

    /**
     * The maximum BSL Language Server JVM heap, in gigabytes, this provider was built with.
     *
     * <p>Exposed so callers that build the provider (the refresh-inputs factory) can assert which
     * preference value was resolved, without running a fetch.
     *
     * @return the configured maximum heap, in gigabytes
     */
    public int maxHeapGb()
    {
        return maxHeapGb;
    }

    @Override
    public SonarRule describeRule(String ruleKey)
    {
        SonarRule cached = ruleCache.get(ruleKey);
        return cached != null ? cached : new SonarRule(ruleKey, ruleKey, EMPTY_DESCRIPTION);
    }

    @Override
    public List<BranchInfo> listBranches(String projectKeyArg)
    {
        return List.of();
    }

    @Override
    public boolean branchAnalysisSupported()
    {
        return false;
    }

    /**
     * Turns a SonarQube project key into a safe single path segment for the report directory. Real Sonar
     * keys routinely contain characters that are illegal or dangerous in a file name ({@code :}, {@code /},
     * {@code ..}), and the report directory is recursively deleted before each run, so the raw key must
     * never reach the filesystem. The key itself is still used verbatim for component-key mapping.
     *
     * @param key the project key, not {@code null}
     * @return a file-name-safe segment, never {@code null} or a path-traversal token
     */
    private static String safeDirName(String key)
    {
        // Allow only letters, digits, underscore and hyphen. Dots are deliberately excluded so the name
        // can never be "."/".." nor end in a dot (which Windows silently trims), and separators/colons
        // become underscores - the result is always a single, contained path segment. A short hash suffix
        // keeps the name unique and bounded even for long keys or collisions after sanitising, and a
        // leading underscore keeps it clear of Windows reserved device names (CON, NUL, COM1, ...).
        String cleaned = key.replaceAll("[^A-Za-z0-9_-]", "_"); //$NON-NLS-1$ //$NON-NLS-2$
        if (cleaned.length() > MAX_DIR_NAME_LENGTH)
        {
            cleaned = cleaned.substring(0, MAX_DIR_NAME_LENGTH);
        }
        return '_' + cleaned + '_' + Integer.toHexString(key.hashCode());
    }

    /**
     * Looks for a project-local {@code .bsl-language-server.json}, first under {@code projectRoot}, then
     * under {@code srcDir} (the directory actually analyzed).
     *
     * <p>Both locations are checked explicitly, rather than relying on the language server's own discovery,
     * because live verification (2026-07-17) showed the launcher only auto-discovers this file relative to
     * the analyzed source directory, never relative to {@code projectRoot}.
     *
     * @param srcDir the directory being analyzed, not {@code null}
     * @return the found configuration file path, or {@code null} if neither location has one
     */
    private Path findProjectConfigFile(Path srcDir)
    {
        Path atProjectRoot = projectRoot.resolve(PROJECT_CONFIG_FILE_NAME);
        if (Files.exists(atProjectRoot))
        {
            return atProjectRoot;
        }
        Path atSrcDir = srcDir.resolve(PROJECT_CONFIG_FILE_NAME);
        return Files.exists(atSrcDir) ? atSrcDir : null;
    }

    /**
     * Rewrites the bundled BSL Language Server's pinned heap limit to {@link #maxHeapGb}, best-effort.
     *
     * <p>A failure to rewrite the configuration file must never fail the analysis about to run: the
     * language server would simply keep whatever heap limit it already had (see
     * {@link BslServerInstaller#configureHeap}). This layer has no logging facility of its own (it must
     * stay usable headless, without the UI bundle's plugin log), so any {@link IOException} is swallowed,
     * matching the existing best-effort pattern used for {@link #saveDiagnosticsCatalog}.
     */
    private void configureHeapBestEffort()
    {
        try
        {
            BslServerInstaller.configureHeap(stateDir, maxHeapGb);
        }
        catch (IOException e)
        {
            // Best-effort heap tweak; the analysis itself must still run with whatever heap the bundled
            // launcher already has configured.
        }
    }

    /**
     * Persists the report's rule descriptions as a {@link DiagnosticsCatalog}, best-effort.
     *
     * <p>The catalog only feeds a settings page listing known diagnostics; a failure to write it must never
     * fail the fetch, so any {@link IOException} is swallowed.
     *
     * @param report the parsed report to derive catalog entries from, not {@code null}
     */
    private void saveDiagnosticsCatalog(SarifReport report)
    {
        try
        {
            DiagnosticsCatalog.save(stateDir.resolve(DiagnosticsCatalog.CATALOG_FILE_NAME),
                DiagnosticsCatalog.fromReport(report));
        }
        catch (IOException e)
        {
            // Best-effort cache for the checks preference page; the fetch itself already succeeded.
        }
    }

    /**
     * Resolves the directory to analyze: the project's {@code src} subdirectory when present, otherwise
     * the project root itself.
     *
     * @return the source directory to pass to the analyzer, never {@code null}
     */
    private Path sourceDirectory()
    {
        Path src = projectRoot.resolve(SRC_DIR_NAME);
        return Files.isDirectory(src) ? src : projectRoot;
    }

    /**
     * Deletes any previous report directory contents and recreates it empty, so a failed or stale run
     * never leaves a report behind that looks fresh.
     *
     * @param outputDir the report output directory, not {@code null}
     * @throws IOException if the directory cannot be cleaned or created
     */
    private static void recreateOutputDir(Path outputDir) throws IOException
    {
        if (Files.exists(outputDir))
        {
            try (Stream<Path> walk = Files.walk(outputDir))
            {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList())
                {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(outputDir);
    }
}
