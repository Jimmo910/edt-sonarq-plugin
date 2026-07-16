/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.markers;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarIssueType;
import ru.jimmo.edt.sonarq.core.model.SonarSeverity;
import ru.jimmo.edt.sonarq.ui.views.IssueEntry;

/** Tests for {@link IssueMarkerSynchronizer}. */
public class IssueMarkerSynchronizerTest
{
    private static final String RELATIVE_PATH = "src/Module.bsl";

    private IProject project;
    private IFile file;
    private IssueMarkerSynchronizer synchronizer;

    @Before
    public void setUp() throws CoreException
    {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject("marker-sync-test");
        if (!project.exists())
        {
            project.create(new NullProgressMonitor());
        }
        project.open(new NullProgressMonitor());
        IFolder folder = project.getFolder("src");
        if (!folder.exists())
        {
            folder.create(true, true, new NullProgressMonitor());
        }
        file = project.getFile(RELATIVE_PATH);
        if (!file.exists())
        {
            file.create(new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8)), true,
                new NullProgressMonitor());
        }
        synchronizer = new IssueMarkerSynchronizer();
    }

    @After
    public void tearDown() throws CoreException
    {
        project.delete(true, true, new NullProgressMonitor());
    }

    @Test
    public void createsMarkerWithAttributesAndSeverityMapping() throws CoreException
    {
        synchronizer.sync(project,
            List.of(new IssueEntry(issue("k1", "bsl:Rule1", SonarSeverity.BLOCKER, 10), RELATIVE_PATH)));

        IMarker marker = onlyMarker();
        assertEquals("[bsl:Rule1] boom", marker.getAttribute(IMarker.MESSAGE, ""));
        assertEquals(IMarker.SEVERITY_ERROR, marker.getAttribute(IMarker.SEVERITY, -1));
        assertEquals(10, marker.getAttribute(IMarker.LINE_NUMBER, -1));
        assertEquals("bsl:Rule1", marker.getAttribute(IssueMarkers.ATTR_RULE_KEY, ""));
        assertEquals("BLOCKER", marker.getAttribute(IssueMarkers.ATTR_SONAR_SEVERITY, ""));
        assertEquals("k1", marker.getAttribute(IssueMarkers.ATTR_ISSUE_KEY, ""));
    }

    @Test
    public void criticalMapsToError() throws CoreException
    {
        synchronizer.sync(project,
            List.of(new IssueEntry(issue("k2", "bsl:Rule2", SonarSeverity.CRITICAL, 1), RELATIVE_PATH)));
        assertEquals(IMarker.SEVERITY_ERROR, onlyMarker().getAttribute(IMarker.SEVERITY, -1));
    }

    @Test
    public void majorMapsToWarning() throws CoreException
    {
        synchronizer.sync(project,
            List.of(new IssueEntry(issue("k3", "bsl:Rule3", SonarSeverity.MAJOR, 1), RELATIVE_PATH)));
        assertEquals(IMarker.SEVERITY_WARNING, onlyMarker().getAttribute(IMarker.SEVERITY, -1));
    }

    @Test
    public void minorMapsToInfo() throws CoreException
    {
        synchronizer.sync(project,
            List.of(new IssueEntry(issue("k4", "bsl:Rule4", SonarSeverity.MINOR, 1), RELATIVE_PATH)));
        assertEquals(IMarker.SEVERITY_INFO, onlyMarker().getAttribute(IMarker.SEVERITY, -1));
    }

    @Test
    public void infoMapsToInfo() throws CoreException
    {
        synchronizer.sync(project,
            List.of(new IssueEntry(issue("k5", "bsl:Rule5", SonarSeverity.INFO, 1), RELATIVE_PATH)));
        assertEquals(IMarker.SEVERITY_INFO, onlyMarker().getAttribute(IMarker.SEVERITY, -1));
    }

    @Test
    public void zeroLineOmitsLineNumberAttribute() throws CoreException
    {
        synchronizer.sync(project,
            List.of(new IssueEntry(issue("k6", "bsl:Rule6", SonarSeverity.MINOR, 0), RELATIVE_PATH)));
        assertEquals(-1, onlyMarker().getAttribute(IMarker.LINE_NUMBER, -1));
    }

    @Test
    public void nullPathAndMissingFileEntriesAreSkipped() throws CoreException
    {
        IssueEntry unmapped = new IssueEntry(issue("k7", "bsl:Rule7", SonarSeverity.MAJOR, 1), null);
        IssueEntry missingFile = new IssueEntry(issue("k8", "bsl:Rule8", SonarSeverity.MAJOR, 1), "src/Missing.bsl");

        synchronizer.sync(project, List.of(unmapped, missingFile));

        assertEquals(0, file.findMarkers(IssueMarkers.MARKER_TYPE, true, IResource.DEPTH_ZERO).length);
        assertEquals(0, project.findMarkers(IssueMarkers.MARKER_TYPE, true, IResource.DEPTH_INFINITE).length);
    }

    @Test
    public void repeatedSyncDoesNotDuplicateMarkers() throws CoreException
    {
        List<IssueEntry> entries =
            List.of(new IssueEntry(issue("k9", "bsl:Rule9", SonarSeverity.MAJOR, 5), RELATIVE_PATH));

        synchronizer.sync(project, entries);
        synchronizer.sync(project, entries);

        assertEquals(1, file.findMarkers(IssueMarkers.MARKER_TYPE, true, IResource.DEPTH_ZERO).length);
    }

    @Test
    public void syncWithEmptyListClearsExistingMarkers() throws CoreException
    {
        synchronizer.sync(project,
            List.of(new IssueEntry(issue("k10", "bsl:Rule10", SonarSeverity.MAJOR, 1), RELATIVE_PATH)));

        synchronizer.sync(project, List.of());

        assertEquals(0, file.findMarkers(IssueMarkers.MARKER_TYPE, true, IResource.DEPTH_ZERO).length);
    }

    @Test
    public void clearAllRemovesMarkers() throws CoreException
    {
        synchronizer.sync(project,
            List.of(new IssueEntry(issue("k11", "bsl:Rule11", SonarSeverity.MAJOR, 1), RELATIVE_PATH)));

        synchronizer.clearAll();

        assertEquals(0, file.findMarkers(IssueMarkers.MARKER_TYPE, true, IResource.DEPTH_ZERO).length);
    }

    private IMarker onlyMarker() throws CoreException
    {
        IMarker[] markers = file.findMarkers(IssueMarkers.MARKER_TYPE, true, IResource.DEPTH_ZERO);
        assertEquals(1, markers.length);
        return markers[0];
    }

    private static SonarIssue issue(String key, String ruleKey, SonarSeverity severity, int line)
    {
        return new SonarIssue(key, ruleKey, severity, SonarIssueType.CODE_SMELL, "proj:" + RELATIVE_PATH, "boom",
            line);
    }
}
