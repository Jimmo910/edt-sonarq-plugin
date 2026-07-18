/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.suppress;

import org.eclipse.core.resources.IMarker;
import org.eclipse.ui.IMarkerResolution;
import org.eclipse.ui.IMarkerResolutionGenerator2;

import ru.jimmo.edt.sonarq.core.suppress.BslSuppression;
import ru.jimmo.edt.sonarq.ui.markers.IssueMarkers;

/**
 * Contributes a "Suppress" Problems-view quick fix to {@value IssueMarkers#MARKER_TYPE} markers
 * (issue #7), registered via {@code org.eclipse.ui.ide.markerResolution} in {@code plugin.xml}. Applies the
 * same {@link SuppressionApplier} edit the SonarQube Issues view's context-menu action uses.
 */
public class SuppressMarkerResolutionGenerator implements IMarkerResolutionGenerator2
{
    @Override
    public boolean hasResolutions(IMarker marker)
    {
        return hasRuleKeyAndLine(marker);
    }

    @Override
    public IMarkerResolution[] getResolutions(IMarker marker)
    {
        if (!hasRuleKeyAndLine(marker))
        {
            return new IMarkerResolution[0];
        }
        String ruleKey = marker.getAttribute(IssueMarkers.ATTR_RULE_KEY, ""); //$NON-NLS-1$
        return new IMarkerResolution[] { new SuppressMarkerResolution(BslSuppression.bareRuleKey(ruleKey)) };
    }

    /**
     * The pure predicate behind {@link #hasResolutions}: a marker only has a suppression quick fix when it
     * carries a non-empty {@link IssueMarkers#ATTR_RULE_KEY} and a positive {@link IMarker#LINE_NUMBER}.
     *
     * @param marker the marker to check, not {@code null}
     * @return {@code true} when both attributes are present and valid
     */
    static boolean hasRuleKeyAndLine(IMarker marker)
    {
        String ruleKey = marker.getAttribute(IssueMarkers.ATTR_RULE_KEY, ""); //$NON-NLS-1$
        int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
        return !ruleKey.isEmpty() && line > 0;
    }
}
