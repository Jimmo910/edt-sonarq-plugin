/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.jimmo.edt.sonarq.core.localanalysis.LocalIssueProvider;
import ru.jimmo.edt.sonarq.core.model.IssueQuery;
import ru.jimmo.edt.sonarq.core.model.SonarRule;
import ru.jimmo.edt.sonarq.core.provider.IIssueProvider;

/**
 * Tests for {@link SonarIssuesView#providerAfterRefresh}, the pure (SWT-free) decision behind which
 * provider serves rule-description lookups after a refresh attempt.
 */
public class SonarIssuesViewTest
{
    private static final String PROJECT_KEY = "TestConfiguration";

    private static final String RULE_KEY = "MethodSize";

    private Path root;
    private Path projectRoot;
    private Path stateDir;
    private Path override;

    @Before
    public void setUp() throws IOException
    {
        root = Files.createTempDirectory("sonarq-issues-view-test");
        projectRoot = root.resolve("project");
        stateDir = root.resolve("state");
        Files.createDirectories(projectRoot.resolve("src"));
        Files.createDirectories(stateDir);
        override = root.resolve("fake-bsl-language-server");
        Files.writeString(override, "#!fake");
    }

    @After
    public void tearDown() throws IOException
    {
        try (Stream<Path> walk = Files.walk(root))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Test
    public void succeededRefreshSwitchesToTheRefreshedProvider()
    {
        IIssueProvider previous = newProvider();
        IIssueProvider refreshed = newProvider();

        assertSame(refreshed, SonarIssuesView.providerAfterRefresh(previous, refreshed, true));
    }

    @Test
    public void nullPreviousProviderIsReplacedOnFirstSuccessfulRefresh()
    {
        IIssueProvider refreshed = newProvider();

        assertSame(refreshed, SonarIssuesView.providerAfterRefresh(null, refreshed, true));
    }

    /**
     * Reproduces issue #4 point 6 ("show the full rule description, not just the name"): a refresh in local
     * analysis mode always builds a brand-new {@link LocalIssueProvider} whose rule cache starts empty,
     * populated only once its own {@code fetchIssues} call succeeds. Switching the active provider to it
     * before that happens - while a refresh is still in flight - must not lose access to a rule the
     * PREVIOUS, still-displayed snapshot already fetched in full, so
     * {@link SonarIssuesView#providerAfterRefresh} must keep serving lookups from the previous provider
     * while the new refresh has not (yet) succeeded.
     */
    @Test
    public void unfinishedRefreshKeepsServingDescriptionsFromThePreviousProvider() throws Exception
    {
        LocalIssueProvider previous = new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, null, "",
            4, (executable, srcDir, outputDir, configPath, monitor) ->
            {
                Path sarif = outputDir.resolve("bsl-ls.sarif");
                Files.writeString(sarif, sarifWithFullDescription(), StandardCharsets.UTF_8);
                return sarif;
            });
        previous.fetchIssues(new IssueQuery(PROJECT_KEY, null), new NullProgressMonitor());
        LocalIssueProvider refreshed = newProvider();

        // The refresh that built "refreshed" has been scheduled but has not completed yet, so the view must
        // still resolve descriptions against "previous".
        IIssueProvider active = SonarIssuesView.providerAfterRefresh(previous, refreshed, false);

        SonarRule rule = active.describeRule(RULE_KEY);
        assertTrue("expected the full description to still be shown while a refresh is in flight, got: "
            + rule.htmlDescription(), rule.htmlDescription().contains("Methods should not be too long."));
        // What a premature switch would have shown instead: resolving against the brand-new, not-yet-fetched
        // instance degrades to a name-only stand-in - exactly the reported "only the name, not the full
        // description".
        SonarRule prematureLookup = refreshed.describeRule(RULE_KEY);
        assertEquals(RULE_KEY, prematureLookup.name());
        assertEquals("", prematureLookup.htmlDescription());
    }

    private LocalIssueProvider newProvider()
    {
        return new LocalIssueProvider(PROJECT_KEY, projectRoot, stateDir, override, null, "", 4,
            (executable, srcDir, outputDir, configPath, monitor) -> outputDir.resolve("not-run-yet.sarif"));
    }

    private static String sarifWithFullDescription()
    {
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
                  "results": []
                }
              ]
            }""";
    }
}
