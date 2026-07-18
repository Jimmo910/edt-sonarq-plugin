/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.jimmo.edt.sonarq.core.client.SonarServerException;
import ru.jimmo.edt.sonarq.core.model.IssueQuery;
import ru.jimmo.edt.sonarq.core.model.IssueSnapshot;
import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarRule;
import ru.jimmo.edt.sonarq.core.scope.ChangedLines;
import ru.jimmo.edt.sonarq.ui.Messages;

/** Tests for {@link LocalIssueProvider}. */
public class LocalIssueProviderTest
{
    private static final String PROJECT_KEY = "TestConfiguration";

    /**
     * Records the sequence of progress calls it receives, for asserting the indeterminate progress
     * {@link LocalIssueProvider#fetchIssues} reports (issue #4 point 2/3). Every other
     * {@link IProgressMonitor} method is a no-op; cancellation is never requested by these tests.
     */
    private static final class RecordingProgressMonitor implements IProgressMonitor
    {
        private final List<String> events = new ArrayList<>();

        @Override
        public void beginTask(String name, int totalWork)
        {
            events.add("beginTask(" + name + "," + totalWork + ")");
        }

        @Override
        public void done()
        {
            events.add("done()");
        }

        @Override
        public void internalWorked(double work)
        {
            // Not used by the coarse progress reporting under test.
        }

        @Override
        public boolean isCanceled()
        {
            return false;
        }

        @Override
        public void setCanceled(boolean value)
        {
            // Not used by the coarse progress reporting under test.
        }

        @Override
        public void setTaskName(String name)
        {
            // Not used by the coarse progress reporting under test.
        }

        @Override
        public void subTask(String name)
        {
            events.add("subTask(" + name + ")");
        }

        @Override
        public void worked(int work)
        {
            events.add("worked(" + work + ")");
        }
    }

    /** Records the arguments it was called with and serves a pre-canned SARIF file or a failure. */
    private static final class FakeRunner implements AnalyzeRunner
    {
        private Path capturedExecutable;
        private Path capturedSrcDir;
        private Path capturedOutputDir;
        private Path capturedConfigPath;
        private String sarifJson = "{ \"runs\": [] }";
        private IOException ioFailure;
        private InterruptedException interruptedFailure;
        private RuntimeException runtimeFailure;

        @Override
        public Path analyze(Path serverExecutable, Path srcDir, Path outputDir, Path configPath,
            IProgressMonitor monitor) throws IOException, InterruptedException
        {
            capturedExecutable = serverExecutable;
            capturedSrcDir = srcDir;
            capturedOutputDir = outputDir;
            capturedConfigPath = configPath;
            if (ioFailure != null)
            {
                throw ioFailure;
            }
            if (interruptedFailure != null)
            {
                throw interruptedFailure;
            }
            if (runtimeFailure != null)
            {
                throw runtimeFailure;
            }
            Path sarif = outputDir.resolve("bsl-ls.sarif");
            Files.writeString(sarif, sarifJson, StandardCharsets.UTF_8);
            return sarif;
        }
    }

    private Path root;
    private Path projectRoot;
    private Path stateDir;
    private Path override;

    @Before
    public void setUp() throws IOException
    {
        root = Files.createTempDirectory("sonarq-local-issue-provider-test");
        projectRoot = root.resolve("project");
        stateDir = root.resolve("state");
        Files.createDirectories(projectRoot.resolve("src").resolve("CommonModules").resolve("X"));
        Files.createDirectories(stateDir);
        override = root.resolve("fake-bsl-language-server");
        Files.writeString(override, "#!fake");
    }

    @After
    public void tearDown() throws IOException
    {
        try (Stream<Path> walk = Files.walk(root))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    /**
     * Builds a fixture SARIF document with an ABSOLUTE {@code file:///} artifact URI built from the real
     * temp {@code projectRoot}, so relativizing against {@code uriBasePrefix} is actually exercised.
     */
    private String sarifFixture()
    {
        String moduleUri = projectRoot.resolve("src").resolve("CommonModules").resolve("X")
            .resolve("Module.bsl").toUri().toString();
        return """
            {
              "runs": [
                {
                  "tool": {
                    "driver": {
                      "rules": [
                        {
                          "id": "MethodSize",
                          "name": "Method size",
                          "fullDescription": { "text": "Methods should not be too long." }
                        }
                      ]
                    }
                  },
                  "results": [
                    {
                      "ruleId": "MethodSize",
                      "level": "warning",
                      "message": { "text": "Too long" },
                      "locations": [
                        {
                          "physicalLocation": {
                            "artifactLocation": { "uri": "%s" },
                            "region": { "startLine": 42 }
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }""".formatted(moduleUri);
    }

    /**
     * Builds a fixture SARIF document with two results on the same module, at lines 10 and 11, so a
     * base-branch changed-lines filter has something to distinguish between.
     */
    private String sarifFixtureTwoIssuesOnLines10And11()
    {
        String moduleUri = projectRoot.resolve("src").resolve("CommonModules").resolve("X")
            .resolve("Module.bsl").toUri().toString();
        return """
            {
              "runs": [
                {
                  "tool": {
                    "driver": {
                      "rules": [
                        {
                          "id": "MethodSize",
                          "name": "Method size",
                          "fullDescription": { "text": "Methods should not be too long." }
                        }
                      ]
                    }
                  },
                  "results": [
                    {
                      "ruleId": "MethodSize",
                      "level": "warning",
                      "message": { "text": "Too long" },
                      "locations": [
                        {
                          "physicalLocation": {
                            "artifactLocation": { "uri": "%s" },
                            "region": { "startLine": 10 }
                          }
                        }
                      ]
                    },
                    {
                      "ruleId": "MethodSize",
                      "level": "warning",
                      "message": { "text": "Too long" },
                      "locations": [
                        {
                          "physicalLocation": {
                            "artifactLocation": { "uri": "%s" },
                            "region": { "startLine": 11 }
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }""".formatted(moduleUri, moduleUri);
    }

    /**
     * A {@link ChangedLines} fixture where {@code projectRoot} is itself the git work-tree root and only
     * {@code onlyLine} of the module under {@code src/CommonModules/X/Module.bsl} changed.
     */
    private ChangedLines changedLinesFixture(int onlyLine)
    {
        return new ChangedLines()
        {
            @Override
            public boolean available()
            {
                return true;
            }

            @Override
            public boolean isChanged(String repoRelativePath, int line)
            {
                return line == onlyLine;
            }

            @Override
            public File workTreeRoot()
            {
                return projectRoot.toFile();
            }
        };
    }

    @Test
    public void fetchIssuesReturnsProjectRelativeComponentKeysAndCachesRules() throws Exception
    {
        FakeRunner runner = new FakeRunner();
        runner.sarifJson = sarifFixture();
        LocalIssueProvider provider =
            new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, null, "", 4, runner);

        IssueSnapshot snapshot = provider.fetchIssues(new IssueQuery(PROJECT_KEY, null), new NullProgressMonitor());

        assertEquals(1, snapshot.issues().size());
        assertEquals(1, snapshot.serverTotal());
        SonarIssue issue = snapshot.issues().get(0);
        assertEquals(PROJECT_KEY + ":src/CommonModules/X/Module.bsl", issue.componentKey());
        assertEquals(42, issue.line());

        SonarRule rule = provider.describeRule("MethodSize");
        assertEquals("Method size", rule.name());
        assertTrue(rule.htmlDescription().contains("Methods should not be too long."));
    }

    @Test
    public void describeRuleBeforeAnyFetchReturnsEmptyRule() throws Exception
    {
        LocalIssueProvider provider = new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, null, "",
            4, new FakeRunner());

        SonarRule rule = provider.describeRule("bsl:Unknown");

        assertEquals("bsl:Unknown", rule.key());
        assertEquals("bsl:Unknown", rule.name());
        assertEquals("", rule.htmlDescription());
    }

    @Test
    public void runnerIoFailureBecomesSonarServerException() throws Exception
    {
        FakeRunner runner = new FakeRunner();
        runner.ioFailure = new IOException("boom");
        LocalIssueProvider provider =
            new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, null, "", 4, runner);

        try
        {
            provider.fetchIssues(new IssueQuery(PROJECT_KEY, null), new NullProgressMonitor());
            fail("expected SonarServerException");
        }
        catch (SonarServerException e)
        {
            assertEquals("boom", e.getMessage());
        }
    }

    @Test
    public void runnerInterruptionRestoresFlagAndWrapsException() throws Exception
    {
        FakeRunner runner = new FakeRunner();
        runner.interruptedFailure = new InterruptedException("cancelled");
        LocalIssueProvider provider =
            new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, null, "", 4, runner);

        try
        {
            provider.fetchIssues(new IssueQuery(PROJECT_KEY, null), new NullProgressMonitor());
            fail("expected SonarServerException");
        }
        catch (SonarServerException e)
        {
            assertTrue(Thread.interrupted());
        }
    }

    @Test(expected = OperationCanceledException.class)
    public void cancellationFromRunnerPropagatesUnwrapped() throws Exception
    {
        FakeRunner runner = new FakeRunner();
        runner.runtimeFailure = new OperationCanceledException();
        LocalIssueProvider provider =
            new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, null, "", 4, runner);

        provider.fetchIssues(new IssueQuery(PROJECT_KEY, null), new NullProgressMonitor());
    }

    @Test
    public void serverOverrideIsPassedToRunnerAsExecutable() throws Exception
    {
        FakeRunner runner = new FakeRunner();
        runner.sarifJson = sarifFixture();
        LocalIssueProvider provider =
            new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, null, "", 4, runner);

        provider.fetchIssues(new IssueQuery(PROJECT_KEY, null), new NullProgressMonitor());

        assertEquals(override, runner.capturedExecutable);
    }

    @Test
    public void outputDirIsRecreatedCleanBeforeAnalysis() throws Exception
    {
        FakeRunner runner = new FakeRunner();
        runner.sarifJson = sarifFixture();
        LocalIssueProvider provider =
            new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, null, "", 4, runner);

        provider.fetchIssues(new IssueQuery(PROJECT_KEY, null), new NullProgressMonitor());
        Path outputDir = runner.capturedOutputDir;
        assertTrue(outputDir.startsWith(stateDir.resolve("bsl-report")));
        Path stale = outputDir.resolve("stale.txt");
        Files.writeString(stale, "leftover");

        provider.fetchIssues(new IssueQuery(PROJECT_KEY, null), new NullProgressMonitor());

        assertFalse(Files.exists(stale));
        assertEquals(outputDir, runner.capturedOutputDir);
    }

    @Test
    public void projectKeyWithPathUnsafeCharactersDoesNotEscapeReportDirectory() throws Exception
    {
        FakeRunner runner = new FakeRunner();
        runner.sarifJson = sarifFixture();
        LocalIssueProvider provider =
            new LocalIssueProvider("com.example:module/../..", projectRoot, stateDir, override, null, "", 4, runner);

        provider.fetchIssues(new IssueQuery("com.example:module/../..", null), new NullProgressMonitor());

        Path reportRoot = stateDir.resolve("bsl-report");
        assertTrue(runner.capturedOutputDir.startsWith(reportRoot));
        assertEquals(reportRoot, runner.capturedOutputDir.getParent());
    }

    @Test
    public void srcSubdirectoryIsUsedAsAnalysisRootWhenPresent() throws Exception
    {
        FakeRunner runner = new FakeRunner();
        runner.sarifJson = sarifFixture();
        LocalIssueProvider provider =
            new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, null, "", 4, runner);

        provider.fetchIssues(new IssueQuery(PROJECT_KEY, null), new NullProgressMonitor());

        assertEquals(projectRoot.resolve("src"), runner.capturedSrcDir);
    }

    @Test
    public void projectRootItselfIsUsedWhenNoSrcSubdirectoryExists() throws Exception
    {
        Path bareRoot = root.resolve("bare-project");
        Files.createDirectories(bareRoot);
        FakeRunner runner = new FakeRunner();
        LocalIssueProvider provider =
            new LocalIssueProvider(PROJECT_KEY, bareRoot, stateDir, override, null, "", 4, runner);

        provider.fetchIssues(new IssueQuery(PROJECT_KEY, null), new NullProgressMonitor());

        assertEquals(bareRoot, runner.capturedSrcDir);
    }

    @Test
    public void listBranchesIsAlwaysEmpty() throws Exception
    {
        LocalIssueProvider provider = new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, null, "",
            4, new FakeRunner());

        assertTrue(provider.listBranches(PROJECT_KEY).isEmpty());
    }

    @Test
    public void branchAnalysisIsNeverSupported() throws Exception
    {
        LocalIssueProvider provider = new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, null, "",
            4, new FakeRunner());

        assertFalse(provider.branchAnalysisSupported());
    }

    @Test
    public void configPathIsPassedToRunnerWhenNoProjectConfigFileExists() throws Exception
    {
        FakeRunner runner = new FakeRunner();
        runner.sarifJson = sarifFixture();
        Path configPath = stateDir.resolve("generated-checks-config.json");
        Files.writeString(configPath, "{}");
        LocalIssueProvider provider =
            new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, configPath, "", 4, runner);

        provider.fetchIssues(new IssueQuery(PROJECT_KEY, null), new NullProgressMonitor());

        assertEquals(configPath, runner.capturedConfigPath);
    }

    @Test
    public void projectConfigFileTakesPriorityOverGeneratedConfigPath() throws Exception
    {
        FakeRunner runner = new FakeRunner();
        runner.sarifJson = sarifFixture();
        Path projectConfigPath = projectRoot.resolve(".bsl-language-server.json");
        Files.writeString(projectConfigPath, "{}");
        Path configPath = stateDir.resolve("generated-checks-config.json");
        Files.writeString(configPath, "{}");
        LocalIssueProvider provider =
            new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, configPath, "", 4, runner);

        provider.fetchIssues(new IssueQuery(PROJECT_KEY, null), new NullProgressMonitor());

        assertEquals(projectConfigPath, runner.capturedConfigPath);
    }

    @Test
    public void projectConfigFileInsideSrcDirIsPassedExplicitlyWhenNoProjectRootFileExists() throws Exception
    {
        FakeRunner runner = new FakeRunner();
        runner.sarifJson = sarifFixture();
        Path srcConfigPath = projectRoot.resolve("src").resolve(".bsl-language-server.json");
        Files.writeString(srcConfigPath, "{}");
        Path configPath = stateDir.resolve("generated-checks-config.json");
        Files.writeString(configPath, "{}");
        LocalIssueProvider provider =
            new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, configPath, "", 4, runner);

        provider.fetchIssues(new IssueQuery(PROJECT_KEY, null), new NullProgressMonitor());

        assertEquals(srcConfigPath, runner.capturedConfigPath);
    }

    @Test
    public void successfulFetchSavesDiagnosticsCatalogForSettingsPage() throws Exception
    {
        FakeRunner runner = new FakeRunner();
        runner.sarifJson = sarifFixture();
        LocalIssueProvider provider =
            new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, null, "", 4, runner);

        provider.fetchIssues(new IssueQuery(PROJECT_KEY, null), new NullProgressMonitor());

        Path catalogFile = stateDir.resolve(DiagnosticsCatalog.CATALOG_FILE_NAME);
        assertTrue(Files.exists(catalogFile));
        List<DiagnosticsCatalog.Entry> entries = DiagnosticsCatalog.load(catalogFile);
        assertEquals(1, entries.size());
        assertEquals("MethodSize", entries.get(0).key());
        assertEquals("Method size", entries.get(0).name());
    }

    @Test
    public void nonBlankBaseBranchFiltersIssuesToChangedLinesOnly() throws Exception
    {
        FakeRunner runner = new FakeRunner();
        runner.sarifJson = sarifFixtureTwoIssuesOnLines10And11();
        ChangedLines onlyLine10 = changedLinesFixture(10);
        LocalIssueProvider provider = new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, null,
            "base", 4, runner, (dir, ref) -> onlyLine10);

        IssueSnapshot snapshot = provider.fetchIssues(new IssueQuery(PROJECT_KEY, null), new NullProgressMonitor());

        assertEquals(1, snapshot.issues().size());
        assertEquals(1, snapshot.serverTotal());
        assertEquals(10, snapshot.issues().get(0).line());
    }

    @Test
    public void blankBaseBranchSkipsFilteringAndNeverConsultsChangedLinesSource() throws Exception
    {
        FakeRunner runner = new FakeRunner();
        runner.sarifJson = sarifFixtureTwoIssuesOnLines10And11();
        LocalIssueProvider provider = new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, null, "",
            4, runner, (dir, ref) ->
            {
                throw new AssertionError("changed-lines source must not be consulted for a blank base branch");
            });

        IssueSnapshot snapshot = provider.fetchIssues(new IssueQuery(PROJECT_KEY, null), new NullProgressMonitor());

        assertEquals(2, snapshot.issues().size());
        assertEquals(2, snapshot.serverTotal());
    }

    /**
     * Regression test for issue #4 point 2 (initial report): local analysis was reported with no progress
     * feedback beyond a bare, indeterminate spinner. A follow-up fix then reported a coarse, fixed two-step
     * total and called {@code worked(1)} after the first phase, which left the Progress view bar frozen at a
     * misleading 50% for the entire "analyzing sources" phase (issue #4 point 3) - the language server gives
     * no per-file progress, so any fixed total is fake. {@link LocalIssueProvider#fetchIssues} must report an
     * {@link IProgressMonitor#UNKNOWN}-total task, localized phase names via {@code subTask}, and never call
     * {@code worked(...)}, and must always call {@code done()} even though no caller wraps the monitor in a
     * {@code SubMonitor}.
     */
    @Test
    public void fetchIssuesReportsIndeterminateLocalizedProgress() throws Exception
    {
        FakeRunner runner = new FakeRunner();
        runner.sarifJson = sarifFixture();
        LocalIssueProvider provider =
            new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, null, "", 4, runner);
        RecordingProgressMonitor monitor = new RecordingProgressMonitor();

        provider.fetchIssues(new IssueQuery(PROJECT_KEY, null), monitor);

        assertEquals(List.of(
            "beginTask(" + Messages.LocalProgress_Task + "," + IProgressMonitor.UNKNOWN + ")",
            "subTask(" + Messages.LocalProgress_PrepareEngine + ")",
            "subTask(" + Messages.LocalProgress_Analyzing + ")",
            "done()"),
            monitor.events);
    }

    /**
     * The progress monitor must be null-safe: local mode can, in principle, be invoked without one, and a
     * failure to report progress must never fail the analysis itself.
     */
    @Test
    public void fetchIssuesToleratesANullMonitor() throws Exception
    {
        FakeRunner runner = new FakeRunner();
        runner.sarifJson = sarifFixture();
        LocalIssueProvider provider =
            new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, null, "", 4, runner);

        IssueSnapshot snapshot = provider.fetchIssues(new IssueQuery(PROJECT_KEY, null), null);

        assertEquals(1, snapshot.issues().size());
    }

    /**
     * {@code done()} must be called even when the analysis fails, so a caller that shows a progress dialog
     * (see {@code RefreshIssuesJob#isLocalProvider}) never sees it stuck mid-way after an error.
     */
    @Test
    public void fetchIssuesCallsDoneOnMonitorEvenWhenTheAnalysisFails() throws Exception
    {
        FakeRunner runner = new FakeRunner();
        runner.ioFailure = new IOException("boom");
        LocalIssueProvider provider =
            new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, null, "", 4, runner);
        RecordingProgressMonitor monitor = new RecordingProgressMonitor();

        try
        {
            provider.fetchIssues(new IssueQuery(PROJECT_KEY, null), monitor);
            fail("expected SonarServerException");
        }
        catch (SonarServerException e)
        {
            assertTrue(monitor.events.contains("done()"));
        }
    }

    @Test
    public void maxHeapGbAccessorReturnsConstructorValue()
    {
        LocalIssueProvider provider =
            new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, null, "", 9, new FakeRunner());

        assertEquals(9, provider.maxHeapGb());
    }

    /**
     * Regression test for issue #5: {@link LocalIssueProvider#fetchIssues} must rewrite the bundled
     * launcher's pinned heap limit to {@code maxHeapGb} before running the analysis, so a large
     * configuration does not keep hitting the 4 GB default baked into the jpackage app-image.
     */
    @Test
    public void fetchIssuesConfiguresBundledHeapBeforeAnalysis() throws Exception
    {
        Path cfg = stateDir.resolve("bsl-ls").resolve("app").resolve("bsl-language-server.cfg");
        Files.createDirectories(cfg.getParent());
        Files.writeString(cfg, "[JavaOptions]\njava-options=-Xmx4g\n");
        FakeRunner runner = new FakeRunner();
        runner.sarifJson = sarifFixture();
        LocalIssueProvider provider =
            new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, null, "", 9, runner);

        provider.fetchIssues(new IssueQuery(PROJECT_KEY, null), new NullProgressMonitor());

        String content = Files.readString(cfg, StandardCharsets.UTF_8);
        assertTrue("expected the heap to be rewritten to 9g, got: " + content, content.contains("-Xmx9g"));
        assertFalse("expected the old 4g default to be gone, got: " + content, content.contains("-Xmx4g"));
    }
}
