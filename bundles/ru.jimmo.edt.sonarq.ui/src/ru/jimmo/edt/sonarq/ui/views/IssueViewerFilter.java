/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;

/**
 * A {@link ViewerFilter} that hides {@link IssueEntry} leaves rejected by an {@link IssueFilterState}, and
 * hides {@link IssueGroup} nodes whose every entry is rejected.
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
        if (element instanceof IssueGroup group)
        {
            return group.entries().stream().anyMatch(entry -> state.matches(entry.issue()));
        }
        return true;
    }
}
