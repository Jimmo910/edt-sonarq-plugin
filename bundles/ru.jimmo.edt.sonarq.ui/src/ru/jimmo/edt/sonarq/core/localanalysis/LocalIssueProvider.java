/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.core.runtime.IProgressMonitor;

import ru.jimmo.edt.sonarq.core.client.SonarServerException;
import ru.jimmo.edt.sonarq.core.model.BranchInfo;
import ru.jimmo.edt.sonarq.core.model.IssueQuery;
import ru.jimmo.edt.sonarq.core.model.IssueSnapshot;
import ru.jimmo.edt.sonarq.core.model.SonarRule;
import ru.jimmo.edt.sonarq.core.provider.IIssueProvider;

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
 * executable (a user override, or a managed download via {@link BslServerInstaller}), recreates a clean
 * report directory, runs the analysis and parses the resulting report, caching its rule descriptions for
 * {@link #describeRule(String)}. Branches are not a local-analysis concept: {@link #listBranches(String)}
 * always returns an empty list and {@link #branchAnalysisSupported()} always returns {@code false}.
 */
public final class LocalIssueProvider implements IIssueProvider
{
    private static final String BSL_REPORT_DIR = "bsl-report"; //$NON-NLS-1$
    private static final String SRC_DIR_NAME = "src"; //$NON-NLS-1$
    private static final String EMPTY_DESCRIPTION = ""; //$NON-NLS-1$

    private final String projectKey;
    private final Path projectRoot;
    private final Path stateDir;
    private final Path serverOverride;
    private final AnalyzeRunner runner;

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
     * @param runner runs the headless analysis, not {@code null}
     */
    public LocalIssueProvider(String projectKey, Path projectRoot, Path stateDir, Path serverOverride,
        AnalyzeRunner runner)
    {
        this.projectKey = projectKey;
        this.projectRoot = projectRoot;
        this.stateDir = stateDir;
        this.serverOverride = serverOverride;
        this.runner = runner;
    }

    @Override
    public IssueSnapshot fetchIssues(IssueQuery query, IProgressMonitor monitor) throws SonarServerException
    {
        try
        {
            Path executable = serverOverride != null
                ? serverOverride
                : BslServerInstaller.ensureServer(stateDir, url -> new URL(url).openStream(), monitor);
            Path srcDir = sourceDirectory();
            Path outputDir = stateDir.resolve(BSL_REPORT_DIR).resolve(projectKey);
            recreateOutputDir(outputDir);
            Path sarif = runner.analyze(executable, srcDir, outputDir, monitor);
            SarifReport report = SarifParser.parse(Files.readString(sarif, StandardCharsets.UTF_8), projectKey,
                projectRoot.toString());
            ruleCache = report.rules();
            return new IssueSnapshot(query, report.issues(), report.issues().size(), Instant.now());
        }
        catch (IOException e)
        {
            throw new SonarServerException(e.getMessage(), e);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new SonarServerException(e.getMessage(), e);
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
