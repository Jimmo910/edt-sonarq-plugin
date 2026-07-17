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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
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

/** Tests for {@link LocalIssueProvider}. */
public class LocalIssueProviderTest
{
    private static final String PROJECT_KEY = "TestConfiguration";

    /** Records the arguments it was called with and serves a pre-canned SARIF file or a failure. */
    private static final class FakeRunner implements AnalyzeRunner
    {
        private Path capturedExecutable;
        private Path capturedSrcDir;
        private Path capturedOutputDir;
        private String sarifJson = "{ \"runs\": [] }";
        private IOException ioFailure;
        private InterruptedException interruptedFailure;
        private RuntimeException runtimeFailure;

        @Override
        public Path analyze(Path serverExecutable, Path srcDir, Path outputDir, IProgressMonitor monitor)
            throws IOException, InterruptedException
        {
            capturedExecutable = serverExecutable;
            capturedSrcDir = srcDir;
            capturedOutputDir = outputDir;
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

    @Test
    public void fetchIssuesReturnsProjectRelativeComponentKeysAndCachesRules() throws Exception
    {
        FakeRunner runner = new FakeRunner();
        runner.sarifJson = sarifFixture();
        LocalIssueProvider provider = new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, runner);

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
        LocalIssueProvider provider = new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override,
            new FakeRunner());

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
        LocalIssueProvider provider = new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, runner);

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
        LocalIssueProvider provider = new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, runner);

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
        LocalIssueProvider provider = new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, runner);

        provider.fetchIssues(new IssueQuery(PROJECT_KEY, null), new NullProgressMonitor());
    }

    @Test
    public void serverOverrideIsPassedToRunnerAsExecutable() throws Exception
    {
        FakeRunner runner = new FakeRunner();
        runner.sarifJson = sarifFixture();
        LocalIssueProvider provider = new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, runner);

        provider.fetchIssues(new IssueQuery(PROJECT_KEY, null), new NullProgressMonitor());

        assertEquals(override, runner.capturedExecutable);
    }

    @Test
    public void outputDirIsRecreatedCleanBeforeAnalysis() throws Exception
    {
        Path outputDir = stateDir.resolve("bsl-report").resolve(PROJECT_KEY);
        Files.createDirectories(outputDir);
        Path stale = outputDir.resolve("stale.txt");
        Files.writeString(stale, "leftover");

        FakeRunner runner = new FakeRunner();
        runner.sarifJson = sarifFixture();
        LocalIssueProvider provider = new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, runner);

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
            new LocalIssueProvider("com.example:module/../..", projectRoot, stateDir, override, runner);

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
        LocalIssueProvider provider = new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, runner);

        provider.fetchIssues(new IssueQuery(PROJECT_KEY, null), new NullProgressMonitor());

        assertEquals(projectRoot.resolve("src"), runner.capturedSrcDir);
    }

    @Test
    public void projectRootItselfIsUsedWhenNoSrcSubdirectoryExists() throws Exception
    {
        Path bareRoot = root.resolve("bare-project");
        Files.createDirectories(bareRoot);
        FakeRunner runner = new FakeRunner();
        LocalIssueProvider provider = new LocalIssueProvider(PROJECT_KEY, bareRoot, stateDir, override, runner);

        provider.fetchIssues(new IssueQuery(PROJECT_KEY, null), new NullProgressMonitor());

        assertEquals(bareRoot, runner.capturedSrcDir);
    }

    @Test
    public void listBranchesIsAlwaysEmpty() throws Exception
    {
        LocalIssueProvider provider = new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override,
            new FakeRunner());

        assertTrue(provider.listBranches(PROJECT_KEY).isEmpty());
    }

    @Test
    public void branchAnalysisIsNeverSupported() throws Exception
    {
        LocalIssueProvider provider = new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override,
            new FakeRunner());

        assertFalse(provider.branchAnalysisSupported());
    }
}
