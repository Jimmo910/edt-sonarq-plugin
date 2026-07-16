/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import java.util.List;

import org.eclipse.jface.viewers.ITreeContentProvider;

/**
 * Exposes a {@code List<IssueGroup>} input as a two-level tree: {@link IssueGroup} nodes with
 * {@link IssueEntry} leaves.
 */
public class IssueTreeContentProvider implements ITreeContentProvider
{
    @Override
    public Object[] getElements(Object inputElement)
    {
        if (inputElement instanceof List<?> groups)
        {
            return groups.toArray();
        }
        return new Object[0];
    }

    @Override
    public Object[] getChildren(Object parentElement)
    {
        if (parentElement instanceof IssueGroup group)
        {
            return group.entries().toArray();
        }
        return new Object[0];
    }

    @Override
    public Object getParent(Object element)
    {
        return null;
    }

    @Override
    public boolean hasChildren(Object element)
    {
        return element instanceof IssueGroup;
    }
}
