/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;

/**
 * A {@link ViewerFilter} that hides {@link IssueEntry} leaves rejected by an {@link IssueFilterState}, hides
 * {@link IssueGroup} nodes whose every entry is rejected, and hides {@link IssueSuperGroup} nodes whose every
 * sub-group entry is rejected.
 */
public class IssueViewerFilter extends ViewerFilter
{
    private final IssueFilterState state;

    /**
     * Creates a filter backed by the given state.
     *
     * @param state the filter state, not {@code null}
     */
    public IssueViewerFilter(IssueFilterState state)
    {
        this.state = state;
    }

    @Override
    public boolean select(Viewer viewer, Object parentElement, Object element)
    {
        if (element instanceof IssueEntry entry)
        {
            return state.matches(entry.issue());
        }
        // Deliberately expressed through the same count the group header displays (see
        // IssueFilterState#countMatching): a node is shown exactly when its header would claim a non-zero
        // number of rows, so the label and the visibility can never disagree.
        if (element instanceof IssueGroup group)
        {
            return state.countMatching(group) > 0;
        }
        if (element instanceof IssueSuperGroup superGroup)
        {
            return state.countMatching(superGroup) > 0;
        }
        return true;
    }
}
