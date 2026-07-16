/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import java.util.List;
import java.util.function.Function;

import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.part.ViewPart;

import ru.jimmo.edt.sonarq.core.model.IssueSnapshot;
import ru.jimmo.edt.sonarq.core.provider.BranchState;
import ru.jimmo.edt.sonarq.ui.Messages;

/** The SonarQube Issues view: an issue tree with a rule description pane. */
public class SonarIssuesView extends ViewPart
{
    /** The view id. */
    public static final String VIEW_ID = "ru.jimmo.edt.sonarq.ui.views.issues"; //$NON-NLS-1$

    private TreeViewer viewer;
    private Label statusLabel;
    private IssueSnapshot snapshot;
    private BranchState branchState;
    private IssueGrouping grouping = IssueGrouping.BY_FILE;
    private String boundProjectKey = ""; //$NON-NLS-1$
    private String boundPathPrefix = ""; //$NON-NLS-1$

    @Override
    public void createPartControl(Composite parent)
    {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        SashForm sash = new SashForm(root, SWT.VERTICAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        viewer = new TreeViewer(sash, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION);
        viewer.getTree().setHeaderVisible(true);
        viewer.setContentProvider(new IssueTreeContentProvider());
        createColumns();

        Composite detail = new Composite(sash, SWT.NONE);
        detail.setLayout(new FillLayout());
        sash.setWeights(new int[] { 70, 30 });

        statusLabel = new Label(root, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        statusLabel.setText(Messages.IssuesView_Status_NotConfigured);
    }

    private void createColumns()
    {
        addColumn(Messages.IssuesView_Column_Location, 260, element ->
        {
            if (element instanceof IssueGroup group)
            {
                return group.label() + " (" + group.entries().size() + ')'; //$NON-NLS-1$
            }
            int line = ((IssueEntry)element).issue().line();
            return line > 0 ? String.valueOf(line) : ""; //$NON-NLS-1$
        });
        addColumn(Messages.IssuesView_Column_Severity, 90,
            element -> element instanceof IssueEntry entry ? entry.issue().severity().name() : ""); //$NON-NLS-1$
        addColumn(Messages.IssuesView_Column_Rule, 140,
            element -> element instanceof IssueEntry entry ? entry.issue().ruleKey() : ""); //$NON-NLS-1$
        addColumn(Messages.IssuesView_Column_Message, 400,
            element -> element instanceof IssueEntry entry ? entry.issue().message() : ""); //$NON-NLS-1$
    }

    private void addColumn(String title, int width, Function<Object, String> textProvider)
    {
        TreeViewerColumn column = new TreeViewerColumn(viewer, SWT.NONE);
        column.getColumn().setText(title);
        column.getColumn().setWidth(width);
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return textProvider.apply(element);
            }

            @Override
            public Color getForeground(Object element)
            {
                if (element instanceof IssueEntry entry && entry.relativePath() == null)
                {
                    return viewer.getControl().getDisplay().getSystemColor(SWT.COLOR_GRAY);
                }
                return null;
            }
        });
    }

    /**
     * Applies a freshly loaded snapshot to the tree.
     *
     * @param newSnapshot the snapshot, not {@code null}
     * @param newBranchState the branch resolution result, not {@code null}
     */
    public void setInput(IssueSnapshot newSnapshot, BranchState newBranchState)
    {
        this.snapshot = newSnapshot;
        this.branchState = newBranchState;
        rebuildTree();
    }

    private void rebuildTree()
    {
        if (snapshot == null)
        {
            viewer.setInput(List.of());
            return;
        }
        viewer.setInput(IssueTreeBuilder.build(snapshot.issues(), boundProjectKey, boundPathPrefix, grouping));
    }

    @Override
    public void setFocus()
    {
        viewer.getControl().setFocus();
    }
}
