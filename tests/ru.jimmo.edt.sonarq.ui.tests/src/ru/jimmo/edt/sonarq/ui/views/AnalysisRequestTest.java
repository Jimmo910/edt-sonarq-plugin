/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.jimmo.edt.sonarq.core.analysis.AnalysisLaunchConfig;
import ru.jimmo.edt.sonarq.core.analysis.AnalysisLaunchMode;
import ru.jimmo.edt.sonarq.core.client.SonarConnection;
import ru.jimmo.edt.sonarq.core.settings.ProjectBinding;

/** Tests the credential redaction {@link AnalysisRequest} applies to its debug string (review minor M3). */
public class AnalysisRequestTest
{
    private static final String TOKEN = "squ_2b7c9f1e5a4d";
    private static final String CI_SECRET = "Bearer ci-9f4a2b";
    private static final String CI_URL_TOKEN = "glptt-77c1e3";

    private IProject project;

    @Before
    public void setUp() throws CoreException
    {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject("analysis-request-test");
        if (!project.exists())
        {
            project.create(new NullProgressMonitor());
        }
        project.open(new NullProgressMonitor());
    }

    @After
    public void tearDown() throws CoreException
    {
        project.delete(true, true, new NullProgressMonitor());
    }

    private AnalysisRequest request()
    {
        AnalysisLaunchConfig config = new AnalysisLaunchConfig(AnalysisLaunchMode.CI_TRIGGER, "",
            "https://gitlab.example.com/api/v4/projects/1/trigger/pipeline?token=" + CI_URL_TOKEN, "");
        return new AnalysisRequest(project, new ProjectBinding("proj", "", ""),
            SonarConnection.of("https://sonar.example.com", TOKEN, 30), config, "main", true, CI_SECRET,
            Path.of("state"), null);
    }

    @Test
    public void toStringDisclosesNeitherTheCiSecretNorTheServerToken()
    {
        String text = request().toString();

        assertFalse(text, text.contains(CI_SECRET));
        assertFalse(text, text.contains(TOKEN));
    }

    /**
     * The CI trigger URL carries its own credential in the query string (GitLab wants the trigger token
     * there), so printing the launch configuration verbatim would leak it even with the {@code ciSecret}
     * field redacted.
     */
    @Test
    public void toStringDisclosesNoTriggerTokenFromTheCiUrl()
    {
        String text = request().toString();

        assertFalse(text, text.contains(CI_URL_TOKEN));
        assertTrue(text, text.contains("https://gitlab.example.com/api/v4/projects/1/trigger/pipeline"));
    }

    /** Redaction must not turn the description into something useless to debug with. */
    @Test
    public void toStringKeepsTheNonSecretFieldsVisible()
    {
        String text = request().toString();

        assertTrue(text, text.contains("analysis-request-test"));
        assertTrue(text, text.contains("proj"));
        assertTrue(text, text.contains("main"));
        assertTrue(text, text.contains(AnalysisLaunchMode.CI_TRIGGER.name()));
    }
}
