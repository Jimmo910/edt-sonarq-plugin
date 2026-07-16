/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ICoreRunnable;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuCreator;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

import ru.jimmo.edt.sonarq.core.client.SonarConnection;
import ru.jimmo.edt.sonarq.core.client.SonarHttpClient;
import ru.jimmo.edt.sonarq.core.client.SonarServerException;
import ru.jimmo.edt.sonarq.core.model.IssueSnapshot;
import ru.jimmo.edt.sonarq.core.model.SonarIssueType;
import ru.jimmo.edt.sonarq.core.model.SonarRule;
import ru.jimmo.edt.sonarq.core.model.SonarSeverity;
import ru.jimmo.edt.sonarq.core.provider.BranchState;
import ru.jimmo.edt.sonarq.core.provider.IIssueProvider;
import ru.jimmo.edt.sonarq.core.provider.ServerIssueProvider;
import ru.jimmo.edt.sonarq.core.settings.ProjectBinding;
import ru.jimmo.edt.sonarq.ui.Messages;
import ru.jimmo.edt.sonarq.ui.settings.ProjectBindingStore;
import ru.jimmo.edt.sonarq.ui.settings.SonarConnectionFactory;

/** The SonarQube Issues view: an issue tree with a rule description pane. */
public class SonarIssuesView extends ViewPart
{
    /** The view id. */
    public static final String VIEW_ID = "ru.jimmo.edt.sonarq.ui.views.issues"; //$NON-NLS-1$

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss"); //$NON-NLS-1$

    private TreeViewer viewer;
    private Label statusLabel;
    private Composite bannerComposite;
    private Label bannerLabel;
    private Link bannerLink;
    private IssueSnapshot snapshot;
    private BranchState branchState;
    private IssueGrouping grouping = IssueGrouping.BY_FILE;
    private final IssueFilterState state = new IssueFilterState();
    private IProject selectedProject;
    private String sessionBranch;
    private String boundProjectKey = ""; //$NON-NLS-1$
    private String boundPathPrefix = ""; //$NON-NLS-1$
    private long refreshGeneration;
    private RuleDescriptionPanel rulePanel;
    private IIssueProvider currentProvider;
    private String requestedRuleKey;

    @Override
    public void createPartControl(Composite parent)
    {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        createBanner(root);

        SashForm sash = new SashForm(root, SWT.VERTICAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite treePane = new Composite(sash, SWT.NONE);
        treePane.setLayout(new GridLayout(1, false));

        Text filterText = new Text(treePane, SWT.SEARCH | SWT.ICON_SEARCH | SWT.BORDER);
        filterText.setMessage(Messages.IssuesView_FilterText_Hint);
        filterText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        filterText.addModifyListener(event ->
        {
            state.setText(filterText.getText());
            viewer.refresh();
        });

        viewer = new TreeViewer(treePane, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION);
        viewer.getTree().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        viewer.getTree().setHeaderVisible(true);
        viewer.setContentProvider(new IssueTreeContentProvider());
        viewer.addFilter(new IssueViewerFilter(state));
        createColumns();

        Composite detail = new Composite(sash, SWT.NONE);
        detail.setLayout(new FillLayout());
        rulePanel = new RuleDescriptionPanel(detail);
        sash.setWeights(new int[] { 70, 30 });

        viewer.addSelectionChangedListener(event ->
        {
            Object element = ((IStructuredSelection)event.getSelection()).getFirstElement();
            if (element instanceof IssueEntry entry)
            {
                requestRuleDescription(entry.issue().ruleKey());
            }
            else
            {
                requestedRuleKey = null;
                rulePanel.showMessage(""); //$NON-NLS-1$
            }
        });

        statusLabel = new Label(root, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        statusLabel.setText(Messages.IssuesView_Status_NotConfigured);

        createToolBar();
    }

    private void createBanner(Composite root)
    {
        bannerComposite = new Composite(root, SWT.NONE);
        bannerComposite.setLayout(new GridLayout(2, false));
        GridData bannerData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        bannerData.exclude = true;
        bannerComposite.setLayoutData(bannerData);
        bannerComposite.setVisible(false);

        bannerLabel = new Label(bannerComposite, SWT.NONE);
        bannerLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        bannerLink = new Link(bannerComposite, SWT.NONE);
        bannerLink.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        bannerLink.addSelectionListener(SelectionListener.widgetSelectedAdapter(event ->
        {
            if (branchState != null)
            {
                sessionBranch = branchState.effectiveBranch();
                refreshIssues();
            }
        }));
    }

    private void createToolBar()
    {
        IToolBarManager toolBar = getViewSite().getActionBars().getToolBarManager();
        toolBar.add(createRefreshAction());
        toolBar.add(createProjectAction());
        toolBar.add(new Separator());
        toolBar.add(createSeverityMenuAction());
        toolBar.add(createTypeMenuAction());
        toolBar.add(new Separator());
        toolBar.add(createGroupByFileAction());
        toolBar.add(createGroupByRuleAction());
        toolBar.update(true);
    }

    private Action createRefreshAction()
    {
        return new Action(Messages.IssuesView_RefreshAction, IAction.AS_PUSH_BUTTON)
        {
            @Override
            public void run()
            {
                refreshIssues();
            }
        };
    }

    private Action createProjectAction()
    {
        Action projects = new Action(Messages.IssuesView_ProjectMenu, IAction.AS_DROP_DOWN_MENU)
        {
        };
        projects.setMenuCreator(new ProjectMenuCreator());
        return projects;
    }

    private Action createSeverityMenuAction()
    {
        Action severity = new Action(Messages.IssuesView_SeverityMenu, IAction.AS_DROP_DOWN_MENU)
        {
        };
        severity.setMenuCreator(new SeverityMenuCreator());
        return severity;
    }

    private Action createTypeMenuAction()
    {
        Action type = new Action(Messages.IssuesView_TypeMenu, IAction.AS_DROP_DOWN_MENU)
        {
        };
        type.setMenuCreator(new TypeMenuCreator());
        return type;
    }

    private Action createGroupByFileAction()
    {
        Action action = new Action(Messages.IssuesView_GroupByFile, IAction.AS_RADIO_BUTTON)
        {
            @Override
            public void run()
            {
                grouping = IssueGrouping.BY_FILE;
                rebuildTree();
            }
        };
        action.setChecked(true);
        return action;
    }

    private Action createGroupByRuleAction()
    {
        return new Action(Messages.IssuesView_GroupByRule, IAction.AS_RADIO_BUTTON)
        {
            @Override
            public void run()
            {
                grouping = IssueGrouping.BY_RULE;
                rebuildTree();
            }
        };
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

    private void refreshIssues()
    {
        refreshGeneration++;
        long generation = refreshGeneration;
        IProject project = selectedProject != null ? selectedProject : firstOpenProject();
        if (project == null)
        {
            statusLabel.setText(Messages.IssuesView_Status_NotConfigured);
            return;
        }
        selectedProject = project;
        ProjectBinding binding = new ProjectBindingStore().load(project);
        Optional<SonarConnection> connection = new SonarConnectionFactory().create();
        if (!binding.isConfigured() || connection.isEmpty())
        {
            statusLabel.setText(Messages.IssuesView_Status_NotConfigured);
            return;
        }
        boundProjectKey = binding.projectKey();
        boundPathPrefix = binding.pathPrefix();
        currentProvider = new ServerIssueProvider(new SonarHttpClient(connection.get()));
        new RefreshIssuesJob(currentProvider, project, binding, sessionBranch,
            result -> onRefreshFinished(generation, result)).schedule();
    }

    private void requestRuleDescription(String ruleKey)
    {
        requestedRuleKey = ruleKey;
        if (currentProvider == null)
        {
            rulePanel.showMessage(""); //$NON-NLS-1$
            return;
        }
        rulePanel.showMessage(Messages.RulePanel_Loading);
        IIssueProvider provider = currentProvider;
        ICoreRunnable runnable = monitor -> loadRuleDescription(provider, ruleKey);
        Job.createSystem(Messages.RuleJob_Name, runnable).schedule();
    }

    private void loadRuleDescription(IIssueProvider provider, String ruleKey)
    {
        try
        {
            SonarRule rule = provider.describeRule(ruleKey);
            Display.getDefault().asyncExec(() -> applyRuleIfCurrent(ruleKey, rule));
        }
        catch (SonarServerException e)
        {
            Display.getDefault().asyncExec(() -> applyFailureIfCurrent(ruleKey, e));
        }
    }

    private void applyRuleIfCurrent(String ruleKey, SonarRule rule)
    {
        if (!rulePanel.isDisposed() && ruleKey.equals(requestedRuleKey))
        {
            rulePanel.showRule(rule);
        }
    }

    private void applyFailureIfCurrent(String ruleKey, SonarServerException e)
    {
        if (!rulePanel.isDisposed() && ruleKey.equals(requestedRuleKey))
        {
            rulePanel.showMessage(NLS.bind(Messages.RulePanel_LoadFailed, e.getMessage()));
        }
    }

    private void onRefreshFinished(long generation, RefreshResult result)
    {
        Display.getDefault().asyncExec(() ->
        {
            if (viewer.getControl().isDisposed())
            {
                return;
            }
            if (generation != refreshGeneration)
            {
                return;
            }
            if (result.isError())
            {
                statusLabel.setText(NLS.bind(Messages.IssuesView_Status_Error, result.errorMessage()));
                return;
            }
            setInput(result.snapshot(), result.branchState());
            updateStatusAndBanner();
        });
    }

    private void updateStatusAndBanner()
    {
        statusLabel.setText(buildStatusText());
        statusLabel.getParent().layout();
        updateBanner();
    }

    private String buildStatusText()
    {
        int count = snapshot.issues().size();
        String time = TIME_FORMAT.withZone(ZoneId.systemDefault()).format(snapshot.loadedAt());
        String text;
        if (branchState.branchesSupported() && branchState.effectiveBranch() != null)
        {
            text = NLS.bind(Messages.IssuesView_Status_Loaded,
                new Object[] { Integer.valueOf(count), branchState.effectiveBranch(), time });
        }
        else
        {
            text = NLS.bind(Messages.IssuesView_Status_LoadedNoBranch,
                new Object[] { Integer.valueOf(count), time });
        }
        if (snapshot.truncated())
        {
            text += "  " + NLS.bind(Messages.IssuesView_Status_Truncated, //$NON-NLS-1$
                new Object[] { Integer.valueOf(count), Integer.valueOf(snapshot.serverTotal()) });
        }
        return text;
    }

    private void updateBanner()
    {
        GridData data = (GridData)bannerComposite.getLayoutData();
        if (branchState.missingOnServer())
        {
            bannerLabel.setText(NLS.bind(Messages.IssuesView_BranchMissing, branchState.requestedBranch()));
            bannerLink.setText("<a>" //$NON-NLS-1$
                + NLS.bind(Messages.IssuesView_ShowMainBranch, branchState.effectiveBranch()) + "</a>"); //$NON-NLS-1$
            data.exclude = false;
            bannerComposite.setVisible(true);
        }
        else
        {
            data.exclude = true;
            bannerComposite.setVisible(false);
        }
        bannerComposite.getParent().layout();
    }

    private static IProject firstOpenProject()
    {
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
        {
            if (project.isOpen())
            {
                return project;
            }
        }
        return null;
    }

    @Override
    public void setFocus()
    {
        viewer.getControl().setFocus();
    }

    /** Lists the open workspace projects as a drop-down of the toolbar's Project action. */
    private final class ProjectMenuCreator implements IMenuCreator
    {
        private Menu menu;

        @Override
        public void dispose()
        {
            if (menu != null && !menu.isDisposed())
            {
                menu.dispose();
                menu = null;
            }
        }

        @Override
        public Menu getMenu(Control parent)
        {
            dispose();
            menu = new Menu(parent);
            populate(menu);
            return menu;
        }

        @Override
        public Menu getMenu(Menu parent)
        {
            dispose();
            menu = new Menu(parent);
            populate(menu);
            return menu;
        }

        private void populate(Menu target)
        {
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
            {
                if (project.isOpen())
                {
                    addItem(target, project);
                }
            }
        }

        private void addItem(Menu target, IProject project)
        {
            MenuItem item = new MenuItem(target, SWT.RADIO);
            item.setText(project.getName());
            item.setSelection(project.equals(selectedProject));
            item.addSelectionListener(SelectionListener.widgetSelectedAdapter(event ->
            {
                if (item.getSelection())
                {
                    selectedProject = project;
                    sessionBranch = null;
                    refreshIssues();
                }
            }));
        }
    }

    /** Lists {@link SonarSeverity} values as check-box actions in the toolbar's Severity drop-down. */
    private final class SeverityMenuCreator implements IMenuCreator
    {
        private Menu menu;

        @Override
        public void dispose()
        {
            if (menu != null && !menu.isDisposed())
            {
                menu.dispose();
                menu = null;
            }
        }

        @Override
        public Menu getMenu(Control parent)
        {
            dispose();
            menu = new Menu(parent);
            populate(menu);
            return menu;
        }

        @Override
        public Menu getMenu(Menu parent)
        {
            dispose();
            menu = new Menu(parent);
            populate(menu);
            return menu;
        }

        private void populate(Menu target)
        {
            for (SonarSeverity severity : SonarSeverity.values())
            {
                Action action = new Action(severity.name(), IAction.AS_CHECK_BOX)
                {
                    @Override
                    public void run()
                    {
                        state.toggleSeverity(severity);
                        viewer.refresh();
                    }
                };
                action.setChecked(state.isSeverityEnabled(severity));
                new ActionContributionItem(action).fill(target, -1);
            }
        }
    }

    /** Lists {@link SonarIssueType} values as check-box actions in the toolbar's Type drop-down. */
    private final class TypeMenuCreator implements IMenuCreator
    {
        private Menu menu;

        @Override
        public void dispose()
        {
            if (menu != null && !menu.isDisposed())
            {
                menu.dispose();
                menu = null;
            }
        }

        @Override
        public Menu getMenu(Control parent)
        {
            dispose();
            menu = new Menu(parent);
            populate(menu);
            return menu;
        }

        @Override
        public Menu getMenu(Menu parent)
        {
            dispose();
            menu = new Menu(parent);
            populate(menu);
            return menu;
        }

        private void populate(Menu target)
        {
            for (SonarIssueType type : SonarIssueType.values())
            {
                Action action = new Action(type.name(), IAction.AS_CHECK_BOX)
                {
                    @Override
                    public void run()
                    {
                        state.toggleType(type);
                        viewer.refresh();
                    }
                };
                action.setChecked(state.isTypeEnabled(type));
                new ActionContributionItem(action).fill(target, -1);
            }
        }
    }
}
