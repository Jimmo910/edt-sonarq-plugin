/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.markers;

import org.eclipse.core.resources.IMarker;

import ru.jimmo.edt.sonarq.core.model.SonarSeverity;
import ru.jimmo.edt.sonarq.ui.SonarqPlugin;

/**
 * Marker type id and attribute names for the transient SonarQube issue marker contributed via
 * {@code org.eclipse.core.resources.markers} in {@code plugin.xml}.
 */
public final class IssueMarkers
{
    /** The marker type id: {@value}. */
    public static final String MARKER_TYPE = SonarqPlugin.PLUGIN_ID + ".issue"; //$NON-NLS-1$

    /** The marker attribute holding the SonarQube rule key. */
    public static final String ATTR_RULE_KEY = "ruleKey"; //$NON-NLS-1$

    /** The marker attribute holding the raw SonarQube severity name. */
    public static final String ATTR_SONAR_SEVERITY = "sonarSeverity"; //$NON-NLS-1$

    /** The marker attribute holding the server-side issue key. */
    public static final String ATTR_ISSUE_KEY = "issueKey"; //$NON-NLS-1$

    private IssueMarkers()
    {
    }

    /**
     * Maps a SonarQube severity to the closest {@link IMarker} severity.
     *
     * @param severity the SonarQube severity, not {@code null}
     * @return {@link IMarker#SEVERITY_ERROR} for {@code BLOCKER}/{@code CRITICAL},
     *     {@link IMarker#SEVERITY_WARNING} for {@code MAJOR}, {@link IMarker#SEVERITY_INFO} for
     *     {@code MINOR}/{@code INFO}
     */
    public static int toMarkerSeverity(SonarSeverity severity)
    {
        return switch (severity)
        {
            case BLOCKER, CRITICAL -> IMarker.SEVERITY_ERROR;
            case MAJOR -> IMarker.SEVERITY_WARNING;
            case MINOR, INFO -> IMarker.SEVERITY_INFO;
        };
    }
}
