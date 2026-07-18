/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.IPreferencesService;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuCreator;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Color;
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
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.eclipse.ui.part.ViewPart;

import ru.jimmo.edt.sonarq.core.analysis.AnalysisLaunchConfig;
import ru.jimmo.edt.sonarq.core.client.ISonarServerClient;
import ru.jimmo.edt.sonarq.core.client.SonarConnection;
import ru.jimmo.edt.sonarq.core.client.SonarHttpClient;
import ru.jimmo.edt.sonarq.core.localanalysis.BslServerInstaller;
import ru.jimmo.edt.sonarq.core.mapping.GitBranchDetector;
import ru.jimmo.edt.sonarq.core.model.IssueSnapshot;
import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarIssueType;
import ru.jimmo.edt.sonarq.core.model.SonarSeverity;
import ru.jimmo.edt.sonarq.core.provider.BranchState;
import ru.jimmo.edt.sonarq.core.provider.IIssueProvider;
import ru.jimmo.edt.sonarq.core.settings.ProjectBinding;
import ru.jimmo.edt.sonarq.core.suppress.SuppressionLineShift;
import ru.jimmo.edt.sonarq.ui.Messages;
import ru.jimmo.edt.sonarq.ui.SonarqPlugin;
import ru.jimmo.edt.sonarq.ui.markers.IssueMarkerSynchronizer;
import ru.jimmo.edt.sonarq.ui.markers.MarkerSyncResult;
import ru.jimmo.edt.sonarq.ui.settings.AnalysisLaunchConfigFactory;
import ru.jimmo.edt.sonarq.ui.settings.PreferenceConstants;
import ru.jimmo.edt.sonarq.ui.settings.ProjectBindingStore;
import ru.jimmo.edt.sonarq.ui.settings.SecureTokenStore;
import ru.jimmo.edt.sonarq.ui.settings.SonarConnectionFactory;
import ru.jimmo.edt.sonarq.ui.suppress.SuppressionApplier;
import ru.jimmo.edt.sonarq.ui.sync.ProjectRefreshInputs;
import ru.jimmo.edt.sonarq.ui.sync.RefreshInputsFactory;

/** The SonarQube Issues view: a full-height tree of issues grouped by file, rule or severity. */
public class SonarIssuesView extends ViewPart
{
    /** The view id. */
    public static final String VIEW_ID = "ru.jimmo.edt.sonarq.ui.views.issues"; //$NON-NLS-1$

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss"); //$NON-NLS-1$

    private static final int LOCATION_COLUMN_WIDTH = 260;
    private static final int SEVERITY_COLUMN_WIDTH = 90;
    private static final int RULE_COLUMN_WIDTH = 140;
    private static final int MESSAGE_COLUMN_WIDTH = 400;

    private TreeViewer viewer;
    private Label statusLabel;
    private Link errorDetailsLink;
    private String lastErrorMessage;
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
    private int missingFileMarkerCount;
    private Job inFlightJob;
    private TreeColumn severityColumn;
    private TreeColumn ruleColumn;

    @Override
    public void createPartControl(Composite parent)
    {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        createBanner(root);

        Text filterText = new Text(root, SWT.SEARCH | SWT.ICON_SEARCH | SWT.BORDER);
        filterText.setMessage(Messages.IssuesView_FilterText_Hint);
        filterText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        filterText.addModifyListener(event ->
        {
            state.setText(filterText.getText());
            viewer.refresh();
        });

        viewer = new TreeViewer(root, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION);
        viewer.getTree().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        viewer.getTree().setHeaderVisible(true);
        viewer.setContentProvider(new IssueTreeContentProvider());
        viewer.addFilter(new IssueViewerFilter(state));
        createColumns();
        ColumnViewerToolTipSupport.enableFor(viewer);

        viewer.addDoubleClickListener(event ->
        {
            Object element = ((IStructuredSelection)event.getSelection()).getFirstElement();
            if (element instanceof IssueEntry entry)
            {
                if (selectedProject != null)
                {
                    IssueNavigation.open(getSite().getPage(), selectedProject, entry);
                }
            }
            else if (element instanceof IssueSuperGroup superGroup)
            {
                viewer.setExpandedState(superGroup, !viewer.getExpandedState(superGroup));
            }
            else if (element instanceof IssueGroup group)
            {
                viewer.setExpandedState(group, !viewer.getExpandedState(group));
            }
        });

        hookContextMenu();

        createStatusRow(root);

        createToolBar();
    }

    /**
     * Creates the status row: a status label that fills the available width, plus an initially hidden
     * "Details" link shown only while an error status is displayed (see {@link #setErrorDetailsVisible}).
     * The link only ever opens its dialog from its own {@link SelectionListener} - i.e. on an explicit user
     * click - never automatically, so a background refresh (see {@link ru.jimmo.edt.sonarq.ui.sync.AutoSyncScheduler})
     * can never pop it up.
     *
     * @param root the parent composite, not {@code null}
     */
    private void createStatusRow(Composite root)
    {
        Composite statusRow = new Composite(root, SWT.NONE);
        statusRow.setLayout(new GridLayout(2, false));
        statusRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        statusLabel = new Label(statusRow, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        statusLabel.setText(Messages.IssuesView_Status_NotConfigured);

        errorDetailsLink = new Link(statusRow, SWT.NONE);
        errorDetailsLink.setText("<a>" + Messages.IssuesView_Error_DetailsLink + "</a>"); //$NON-NLS-1$ //$NON-NLS-2$
        GridData linkData = new GridData(SWT.RIGHT, SWT.CENTER, false, false);
        linkData.exclude = true;
        errorDetailsLink.setLayoutData(linkData);
        errorDetailsLink.setVisible(false);
        errorDetailsLink.addSelectionListener(SelectionListener.widgetSelectedAdapter(event -> showErrorDetails()));
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
        bannerLink.addSelectionListener(SelectionListener.widgetSelectedAdapter(event -> launchAnalysis()));
    }

    private void createToolBar()
    {
        IToolBarManager toolBar = getViewSite().getActionBars().getToolBarManager();
        toolBar.add(createRefreshAction());
        toolBar.add(createRunAnalysisAction());
        toolBar.add(createProjectAction());
        toolBar.add(new Separator());
        toolBar.add(createSeverityMenuAction());
        toolBar.add(createTypeMenuAction());
        toolBar.add(new Separator());
        toolBar.add(createGroupByFileAction());
        toolBar.add(createGroupByRuleAction());
        toolBar.add(createGroupBySeverityAction());
        toolBar.update(true);
    }

    private Action createRefreshAction()
    {
        Action action = new Action(Messages.IssuesView_RefreshAction, IAction.AS_PUSH_BUTTON)
        {
            @Override
            public void run()
            {
                refreshIssues();
            }
        };
        applyToolbarIcon(action, "icons/refresh.png", Messages.IssuesView_RefreshAction); //$NON-NLS-1$
        return action;
    }

    private Action createRunAnalysisAction()
    {
        Action action = new Action(Messages.IssuesView_RunAnalysisAction, IAction.AS_PUSH_BUTTON)
        {
            @Override
            public void run()
            {
                launchAnalysis();
            }
        };
        applyToolbarIcon(action, "icons/run.png", Messages.IssuesView_RunAnalysisAction); //$NON-NLS-1$
        return action;
    }

    private Action createProjectAction()
    {
        Action projects = new Action(Messages.IssuesView_ProjectMenu, IAction.AS_DROP_DOWN_MENU)
        {
        };
        projects.setMenuCreator(new ProjectMenuCreator());
        applyToolbarIcon(projects, "icons/project.png", Messages.IssuesView_ProjectMenu); //$NON-NLS-1$
        return projects;
    }

    private Action createSeverityMenuAction()
    {
        Action severity = new Action(Messages.IssuesView_SeverityMenu, IAction.AS_DROP_DOWN_MENU)
        {
        };
        severity.setMenuCreator(new SeverityMenuCreator());
        applyToolbarIcon(severity, "icons/severity.png", Messages.IssuesView_SeverityMenu); //$NON-NLS-1$
        return severity;
    }

    private Action createTypeMenuAction()
    {
        Action type = new Action(Messages.IssuesView_TypeMenu, IAction.AS_DROP_DOWN_MENU)
        {
        };
        type.setMenuCreator(new TypeMenuCreator());
        applyToolbarIcon(type, "icons/type.png", Messages.IssuesView_TypeMenu); //$NON-NLS-1$
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
        applyToolbarIcon(action, "icons/groupfile.png", Messages.IssuesView_GroupByFile); //$NON-NLS-1$
        return action;
    }

    private Action createGroupByRuleAction()
    {
        Action action = new Action(Messages.IssuesView_GroupByRule, IAction.AS_RADIO_BUTTON)
        {
            @Override
            public void run()
            {
                grouping = IssueGrouping.BY_RULE;
                rebuildTree();
            }
        };
        applyToolbarIcon(action, "icons/grouprule.png", Messages.IssuesView_GroupByRule); //$NON-NLS-1$
        return action;
    }

    private Action createGroupBySeverityAction()
    {
        Action action = new Action(Messages.IssuesView_GroupBySeverity, IAction.AS_RADIO_BUTTON)
        {
            @Override
            public void run()
            {
                grouping = IssueGrouping.BY_SEVERITY;
                rebuildTree();
            }
        };
        applyToolbarIcon(action, "icons/severity.png", Messages.IssuesView_GroupBySeverity); //$NON-NLS-1$
        return action;
    }

    /**
     * Switches a toolbar action from a text label to an icon, moving its label to the hover tooltip
     * (issue #4 point 7): JFace renders a toolbar {@link Action} icon-only once an image descriptor is set,
     * while the action text set at construction time remains its accessible name and drop-down/menu label.
     * The descriptor is resolved the same way the view's own extension-point icon is (bundle-relative path
     * under {@code icons/}, resolved through {@link AbstractUIPlugin#imageDescriptorFromPlugin}), and the
     * matching {@code @2x} file next to it is picked up automatically on HiDPI displays.
     *
     * @param action the action to update, not {@code null}
     * @param iconPath the bundle-relative icon path, e.g. {@code "icons/refresh.png"}, not {@code null}
     * @param tooltip the action's current label, reused as the tooltip text, not {@code null}
     */
    private static void applyToolbarIcon(Action action, String iconPath, String tooltip)
    {
        ImageDescriptor descriptor = AbstractUIPlugin.imageDescriptorFromPlugin(SonarqPlugin.PLUGIN_ID, iconPath);
        action.setImageDescriptor(descriptor);
        action.setToolTipText(tooltip);
    }

    /** A hideable column of the issue tree; see {@link #hiddenColumnFor} (issue #3). */
    enum IssueColumn
    {
        /** The location/line-number column; never auto-hidden. */
        LOCATION,

        /** The severity column. */
        SEVERITY,

        /** The rule column. */
        RULE,

        /** The message column; never auto-hidden. */
        MESSAGE
    }

    /**
     * Decides which issue-tree columns merely repeat the active grouping's structure on every row, and
     * should therefore auto-hide (issue #3): grouping by Rule repeats the rule key in the Rule column on
     * every row; grouping by Severity nests rule groups under each severity (see {@link IssueSuperGroup}),
     * so both the Severity column and the Rule column repeat the enclosing node's value on every row.
     * Grouping by File hides nothing, since the Location column then shows each row's line number, which is
     * useful.
     *
     * <p>Pure and SWT-free by design, so it can be unit-tested without a display.
     *
     * @param activeGrouping the active grouping mode, not {@code null}
     * @return the columns to hide, never {@code null}; empty when no column is redundant
     */
    static Set<IssueColumn> hiddenColumnFor(IssueGrouping activeGrouping)
    {
        return switch (activeGrouping)
        {
            case BY_RULE -> EnumSet.of(IssueColumn.RULE);
            case BY_SEVERITY -> EnumSet.of(IssueColumn.SEVERITY, IssueColumn.RULE);
            case BY_FILE -> EnumSet.noneOf(IssueColumn.class);
        };
    }

    /**
     * Hides whichever columns {@link #hiddenColumnFor} reports for the current {@link #grouping}, and
     * restores the rest. Hiding zeroes a column's width and disables resizing rather than disposing the
     * column, so the tree's column indices stay stable and {@link #createColumns()} only ever runs once.
     */
    private void applyColumnVisibility()
    {
        Set<IssueColumn> columnsToHide = hiddenColumnFor(grouping);
        setColumnHidden(severityColumn, SEVERITY_COLUMN_WIDTH, columnsToHide.contains(IssueColumn.SEVERITY));
        setColumnHidden(ruleColumn, RULE_COLUMN_WIDTH, columnsToHide.contains(IssueColumn.RULE));
    }

    private static void setColumnHidden(TreeColumn column, int visibleWidth, boolean hidden)
    {
        column.setWidth(hidden ? 0 : visibleWidth);
        column.setResizable(!hidden);
    }

    private void createColumns()
    {
        addColumn(Messages.IssuesView_Column_Location, LOCATION_COLUMN_WIDTH, element ->
        {
            if (element instanceof IssueSuperGroup superGroup)
            {
                return superGroup.label() + " (" + superGroup.totalEntries() + ')'; //$NON-NLS-1$
            }
            if (element instanceof IssueGroup group)
            {
                return group.label() + " (" + group.entries().size() + ')'; //$NON-NLS-1$
            }
            int line = ((IssueEntry)element).issue().line();
            return line > 0 ? String.valueOf(line) : ""; //$NON-NLS-1$
        });
        severityColumn = addColumn(Messages.IssuesView_Column_Severity, SEVERITY_COLUMN_WIDTH,
            element -> element instanceof IssueEntry entry ? entry.issue().severity().name() : ""); //$NON-NLS-1$
        ruleColumn = addColumn(Messages.IssuesView_Column_Rule, RULE_COLUMN_WIDTH,
            element -> element instanceof IssueEntry entry ? entry.issue().ruleKey() : ""); //$NON-NLS-1$
        addColumn(Messages.IssuesView_Column_Message, MESSAGE_COLUMN_WIDTH,
            element -> element instanceof IssueEntry entry ? entry.issue().message() : ""); //$NON-NLS-1$
    }

    private TreeColumn addColumn(String title, int width, Function<Object, String> textProvider)
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

            @Override
            public String getToolTipText(Object element)
            {
                if (element instanceof IssueEntry entry && entry.relativePath() == null)
                {
                    return Messages.IssuesView_FileMissing_Tooltip;
                }
                return null;
            }
        });
        return column.getColumn();
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
        applyColumnVisibility();
        if (snapshot == null)
        {
            viewer.setInput(List.of());
            return;
        }
        viewer.setInput(IssueTreeBuilder.build(snapshot.issues(), boundProjectKey, boundPathPrefix, grouping));
    }

    /**
     * Installs a context menu on the issue tree offering "Suppress issue" for a suppressible selection.
     */
    private void hookContextMenu()
    {
        MenuManager menuManager = new MenuManager();
        menuManager.setRemoveAllWhenShown(true);
        menuManager.addMenuListener(this::fillContextMenu);
        viewer.getTree().setMenu(menuManager.createContextMenu(viewer.getTree()));
    }

    /**
     * Adds the suppress action to the context menu when the selected entry can be quick-suppressed.
     *
     * @param manager the context menu manager, not {@code null}
     */
    private void fillContextMenu(IMenuManager manager)
    {
        IssueEntry entry = suppressibleSelection();
        if (entry != null)
        {
            manager.add(new Action(Messages.IssuesView_SuppressAction)
            {
                @Override
                public void run()
                {
                    suppressIssue(entry);
                }
            });
        }
    }

    /**
     * Returns the selected issue entry when it can be quick-suppressed - it maps to a file and has a rule key
     * and a positive line - or {@code null} otherwise.
     *
     * @return the suppressible entry, or {@code null}
     */
    private IssueEntry suppressibleSelection()
    {
        Object element = ((IStructuredSelection)viewer.getSelection()).getFirstElement();
        if (selectedProject != null && element instanceof IssueEntry entry && entry.relativePath() != null
            && entry.issue().line() > 0 && !entry.issue().ruleKey().isEmpty())
        {
            return entry;
        }
        return null;
    }

    /**
     * Inserts BSL Language Server suppression comments around the issue's line, so the false-positive stops
     * being reported, then updates the tree and markers in place.
     *
     * @param entry the issue entry to suppress, not {@code null}
     */
    private void suppressIssue(IssueEntry entry)
    {
        IFile file = selectedProject.getFile(entry.relativePath());
        if (!file.exists())
        {
            return;
        }
        try
        {
            SuppressionApplier.apply(file, entry.issue().line(), entry.issue().ruleKey(), getSite().getPage());
            applySuppressionLineShift(entry);
        }
        catch (CoreException | BadLocationException e)
        {
            SonarqPlugin.getInstance().getLog().error(e.getMessage(), e);
        }
    }

    /**
     * Updates the current {@link #snapshot} in place right after a successful quick-suppress, instead of
     * relying on the next asynchronous {@link #refreshIssues()} (which can take seconds, or a full
     * re-analysis in local analysis mode) to catch up.
     *
     * <p>Without this, suppressing a second issue in the same file before that refresh completes would read
     * a stale line number for it - {@link SuppressionLineShift#applyAfterSuppress} is what keeps every other
     * issue in the file numbered correctly for the comment pair {@link SuppressionApplier#apply} just wrote,
     * so this method never needs a fresh server or local-analysis round-trip to stay correct (issue #7
     * follow-up).
     *
     * @param entry the issue entry that was just suppressed, not {@code null}
     */
    private void applySuppressionLineShift(IssueEntry entry)
    {
        if (snapshot == null)
        {
            return;
        }
        List<SonarIssue> adjusted = SuppressionLineShift.applyAfterSuppress(snapshot.issues(), entry.issue().key(),
            entry.issue().componentKey(), entry.issue().line());
        snapshot = new IssueSnapshot(snapshot.query(), adjusted, adjusted.size(), snapshot.loadedAt());
        rebuildTree();
        scheduleMarkerSync();
    }

    private void refreshIssues()
    {
        refreshGeneration++;
        long generation = refreshGeneration;
        IProject project = selectedProject != null ? selectedProject : firstOpenProject();
        if (project == null)
        {
            applyNotConfiguredStatus();
            return;
        }
        selectedProject = project;
        Optional<ProjectRefreshInputs> inputs = RefreshInputsFactory.create(project);
        if (inputs.isEmpty())
        {
            applyNotConfiguredStatus();
            return;
        }
        ProjectRefreshInputs refreshInputs = inputs.get();
        boundProjectKey = refreshInputs.mappingProjectKey();
        boundPathPrefix = refreshInputs.mappingPathPrefix();
        IIssueProvider refreshedProvider = refreshInputs.provider();
        applyRunningStatus();
        showEngineDownloadHintIfNeeded();
        scheduleTracked(new RefreshIssuesJob(refreshedProvider, project, refreshInputs.binding(), sessionBranch,
            result -> onRefreshFinished(generation, result)));
    }

    /**
     * Shows a neutral in-progress status the moment a refresh is actually scheduled, clearing any error (or
     * stale success) text left over from a previous run so it does not linger on screen until this run
     * completes (issue #4 point 4).
     */
    private void applyRunningStatus()
    {
        lastErrorMessage = null;
        statusLabel.setText(Messages.IssuesView_Status_Running);
        statusLabel.setToolTipText(null);
        setErrorDetailsVisible(false);
        statusLabel.getParent().layout();
    }

    /**
     * Shows the "not configured" status, clearing any error tooltip and "Details" link a previous failed
     * refresh or analysis attempt may have left visible (review minor, issue #4/#5): every caller of this
     * method is a guard clause that skips scheduling a refresh or analysis job, so without this the stale
     * error state from an earlier attempt would otherwise linger on screen next to the unrelated
     * not-configured message.
     */
    private void applyNotConfiguredStatus()
    {
        lastErrorMessage = null;
        statusLabel.setText(Messages.IssuesView_Status_NotConfigured);
        statusLabel.setToolTipText(null);
        setErrorDetailsVisible(false);
        statusLabel.getParent().layout();
    }

    /**
     * Replaces the generic "Refreshing..." status with an explicit BSL Language Server download notice
     * before a local-analysis refresh job is scheduled, whenever the engine is not installed yet under the
     * plugin state directory - so the ~170 MB first-run download is visible immediately instead of silently
     * happening behind an unrelated-looking status line (issue #4 point 1). The check itself is cheap: it
     * only stats a file and reads a marker (see {@link BslServerInstaller#isInstalled}), never touches the
     * network. Whichever way the scheduled job ends, {@link #onRefreshFinished} overwrites this text once it
     * completes.
     */
    private void showEngineDownloadHintIfNeeded()
    {
        if (!isLocalMode())
        {
            return;
        }
        Path stateDir = Path.of(SonarqPlugin.getInstance().getStateLocation().toOSString());
        if (!BslServerInstaller.isInstalled(stateDir))
        {
            statusLabel.setText(Messages.IssuesView_Status_InstallingEngine);
            statusLabel.getParent().layout();
        }
    }

    /**
     * Cancels the previous refresh or analysis job before scheduling a new one, so consecutive user
     * actions cannot run concurrently and race on the same analyzer install, report and {@code
     * scannerwork} directories (a real hazard in local-analysis mode where each run spawns a process).
     *
     * @param job the job to schedule and track, not {@code null}
     */
    private void scheduleTracked(Job job)
    {
        if (inFlightJob != null)
        {
            inFlightJob.cancel();
        }
        inFlightJob = job;
        job.schedule();
    }

    /**
     * Launches a SonarQube analysis of the selected project on the requested branch.
     *
     * <p>Runs on the UI thread. In local analysis mode there is no separate "launch": every refresh runs
     * the BSL Language Server, so this simply delegates to {@link #refreshIssues()} and returns. In server
     * mode the project, binding and connection are resolved exactly as in {@link #refreshIssues()}; when
     * they are not configured the status line shows the not-configured hint and nothing is scheduled. A
     * confirmation dialog guards runs that would overwrite an existing server branch (see
     * {@link #needsConfirmation(boolean)}). The heavy work runs in an {@link AnalysisJob}; results and
     * progress are reported back to the status line, and a successful scanner run refreshes the issue tree.
     */
    private void launchAnalysis()
    {
        if (isLocalMode())
        {
            refreshIssues();
            return;
        }
        IProject project = selectedProject != null ? selectedProject : firstOpenProject();
        if (project == null || project.getLocation() == null)
        {
            applyNotConfiguredStatus();
            return;
        }
        selectedProject = project;
        ProjectBinding binding = new ProjectBindingStore().load(project);
        Optional<SonarConnection> connection = new SonarConnectionFactory().create();
        if (!binding.isConfigured() || connection.isEmpty())
        {
            applyNotConfiguredStatus();
            return;
        }
        String requested = resolveRequestedBranch(project, binding);
        boolean branchesSupported = branchState != null && branchState.branchesSupported();
        if (needsConfirmation(branchesSupported) && !confirmMainAnalysis(requested))
        {
            return;
        }
        scheduleAnalysis(project, binding, connection.get(), requested, branchesSupported);
    }

    private void scheduleAnalysis(IProject project, ProjectBinding binding, SonarConnection connection,
        String requested, boolean branchesSupported)
    {
        AnalysisLaunchConfig config = new AnalysisLaunchConfigFactory().create();
        String ciSecret = new SecureTokenStore().loadCiSecret(config.ciUrl());
        Path stateLocation = Path.of(SonarqPlugin.getInstance().getStateLocation().toOSString());
        ISonarServerClient client = new SonarHttpClient(connection);
        AnalysisRequest request = new AnalysisRequest(project, binding, connection, config, requested,
            branchesSupported, ciSecret, stateLocation, client);
        scheduleTracked(new AnalysisJob(request,
            () -> Display.getDefault().asyncExec(this::resetBranchAndRefresh),
            text -> Display.getDefault().asyncExec(() -> applyAnalysisStatus(text))));
    }

    private void resetBranchAndRefresh()
    {
        if (!viewer.getControl().isDisposed())
        {
            sessionBranch = null;
            refreshIssues();
        }
    }

    private void applyAnalysisStatus(String text)
    {
        if (!statusLabel.isDisposed())
        {
            // Clears any error tooltip/Details link left over from a previous refresh so it does not linger
            // next to an unrelated branch-analysis status line (see #applyErrorStatus).
            lastErrorMessage = null;
            statusLabel.setText(text);
            statusLabel.setToolTipText(null);
            setErrorDetailsVisible(false);
            statusLabel.getParent().layout();
        }
    }

    private String resolveRequestedBranch(IProject project, ProjectBinding binding)
    {
        if (sessionBranch != null && !sessionBranch.isEmpty())
        {
            return sessionBranch;
        }
        if (!binding.branchOverride().isEmpty())
        {
            return binding.branchOverride();
        }
        IPath location = project.getLocation();
        return location != null
            ? GitBranchDetector.detectBranch(location.toFile()).orElse(null)
            : null;
    }

    /**
     * Decides whether an overwrite confirmation is required before launching.
     *
     * <p>The confirmation is shown unless the analysis creates a new branch on the server, that is
     * unless the last refresh reported the requested branch as missing. It is also shown when the
     * server edition does not support branches, because the result then overwrites the single default
     * branch until the next CI run.
     *
     * @param branchesSupported whether the server edition supports branches
     * @return {@code true} when the user should confirm the run
     */
    private boolean needsConfirmation(boolean branchesSupported)
    {
        if (!branchesSupported)
        {
            return true;
        }
        return branchState == null || !branchState.missingOnServer();
    }

    /**
     * Tells whether the workspace is configured for local BSL Language Server analysis.
     *
     * @return {@code true} when {@link PreferenceConstants#PREF_MODE} is {@link PreferenceConstants#MODE_LOCAL}
     */
    private static boolean isLocalMode()
    {
        String mode = Platform.getPreferencesService().getString(SonarqPlugin.PLUGIN_ID,
            PreferenceConstants.PREF_MODE, PreferenceConstants.MODE_SERVER, null);
        return PreferenceConstants.MODE_LOCAL.equals(mode);
    }

    private boolean confirmMainAnalysis(String requested)
    {
        String displayBranch = requested != null ? requested
            : branchState != null && branchState.effectiveBranch() != null
                ? branchState.effectiveBranch() : "main"; //$NON-NLS-1$
        return MessageDialog.openConfirm(getSite().getShell(), Messages.Analysis_Confirm_MainTitle,
            NLS.bind(Messages.Analysis_Confirm_MainBody, displayBranch));
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
                applyErrorStatus(result.errorMessage());
                return;
            }
            setInput(result.snapshot(), result.branchState());
            // A previous sync's missing-file count no longer applies to this fresh snapshot; scheduleMarkerSync
            // reports the up-to-date count asynchronously once its background job completes.
            missingFileMarkerCount = 0;
            updateStatusAndBanner();
            scheduleMarkerSync();
        });
    }

    /**
     * Shows a refresh failure on the status line: only the message's first line, as the one-line headline
     * (see {@link #headlineOf}), with the full multi-line message (e.g. a {@code ProcessAnalyzeRunner}
     * failure carries a "Full log: ..." path and a log tail after the headline) set as the label's tooltip
     * and available on demand through the "Details" link (see {@link #showErrorDetails}). The link is never
     * opened automatically from here - only its own click handler does that - so a background refresh (see
     * {@link ru.jimmo.edt.sonarq.ui.sync.AutoSyncScheduler}) can never pop up a dialog.
     *
     * @param errorMessage the full error message reported by the refresh job, not {@code null}
     */
    private void applyErrorStatus(String errorMessage)
    {
        lastErrorMessage = errorMessage;
        statusLabel.setText(NLS.bind(Messages.IssuesView_Status_Error, headlineOf(errorMessage)));
        statusLabel.setToolTipText(errorMessage);
        setErrorDetailsVisible(true);
        statusLabel.getParent().layout();
    }

    /**
     * Extracts the first line of a (possibly multi-line) message, for use as a one-line status headline.
     *
     * @param message the full message, not {@code null}
     * @return the first line, or {@code message} unchanged if it has no line break
     */
    static String headlineOf(String message)
    {
        return message.lines().findFirst().orElse(""); //$NON-NLS-1$
    }

    /**
     * Opens the full error message in a read-only dialog. Called only from {@link #errorDetailsLink}'s own
     * {@link SelectionListener} - i.e. only in direct response to an explicit user click - never from
     * {@link #onRefreshFinished}, which can also run under the unattended background auto-sync timer.
     */
    private void showErrorDetails()
    {
        if (lastErrorMessage != null)
        {
            MessageDialog.openError(getSite().getShell(), Messages.IssuesView_Error_DetailsTitle, lastErrorMessage);
        }
    }

    /**
     * Shows or hides the "Details" link, which only makes sense while an error status is displayed.
     *
     * @param visible {@code true} to show the link, {@code false} to hide and exclude it from the layout
     */
    private void setErrorDetailsVisible(boolean visible)
    {
        ((GridData)errorDetailsLink.getLayoutData()).exclude = !visible;
        errorDetailsLink.setVisible(visible);
        errorDetailsLink.getParent().layout();
    }

    /**
     * Schedules a background job that replaces the workspace markers of {@link #selectedProject} with
     * markers derived from the current {@link #snapshot}, unless the user disabled editor markers.
     *
     * <p>Must run on the UI thread: it reads the view's fields once, into locals, before scheduling the
     * job so the job body never reads mutable view state from a background thread. Once the job completes,
     * its {@link MarkerSyncResult#missingFile()} count is posted back to {@link #missingFileMarkerCount} on
     * the UI thread and folded into the status line (see {@link #buildStatusText}), guarded by the same
     * {@link #refreshGeneration} check as {@link #onRefreshFinished} so a slow sync from a superseded
     * refresh cannot overwrite a newer one's status (issue #6).
     */
    private void scheduleMarkerSync()
    {
        IPreferencesService preferences = Platform.getPreferencesService();
        if (!preferences.getBoolean(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_SHOW_MARKERS, true, null))
        {
            return;
        }
        IProject project = selectedProject;
        IssueSnapshot markerSnapshot = snapshot;
        String projectKey = boundProjectKey;
        String pathPrefix = boundPathPrefix;
        long generation = refreshGeneration;
        WorkspaceJob job = new WorkspaceJob(Messages.MarkerSyncJob_Name)
        {
            @Override
            public IStatus runInWorkspace(IProgressMonitor monitor)
            {
                try
                {
                    List<IssueEntry> entries =
                        IssueTreeBuilder.toEntries(markerSnapshot.issues(), projectKey, pathPrefix);
                    MarkerSyncResult result = new IssueMarkerSynchronizer().sync(project, entries);
                    if (result.missingFile() > 0)
                    {
                        Platform.getLog(SonarIssuesView.class).warn(result.missingFile()
                            + " issue(s) resolved to a project file that does not exist even after a " //$NON-NLS-1$
                            + "workspace refresh; they are not shown as Problems-view markers"); //$NON-NLS-1$
                    }
                    Display.getDefault().asyncExec(() -> applyMarkerSyncResult(generation, result));
                }
                catch (CoreException e)
                {
                    Platform.getLog(SonarIssuesView.class).warn(e.getMessage(), e);
                }
                return Status.OK_STATUS;
            }
        };
        job.setRule(project);
        job.setSystem(true);
        job.schedule();
    }

    /**
     * Applies a completed marker sync's missing-file count to the status line, unless a newer refresh has
     * since started or the view has since been disposed.
     *
     * @param generation the {@link #refreshGeneration} the sync was started for
     * @param result the completed sync's outcome, not {@code null}
     */
    private void applyMarkerSyncResult(long generation, MarkerSyncResult result)
    {
        if (viewer.getControl().isDisposed() || generation != refreshGeneration)
        {
            return;
        }
        missingFileMarkerCount = result.missingFile();
        updateStatusAndBanner();
    }

    private void updateStatusAndBanner()
    {
        lastErrorMessage = null;
        statusLabel.setText(buildStatusText());
        statusLabel.setToolTipText(null);
        setErrorDetailsVisible(false);
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
        long unmapped = IssueTreeBuilder.countUnmapped(
            IssueTreeBuilder.toEntries(snapshot.issues(), boundProjectKey, boundPathPrefix));
        // Both counts describe issues shown in this tree that have no Problems-view marker: unmapped never
        // resolved to a project path at all, missingFileMarkerCount resolved to one that still doesn't exist
        // as a file (see #scheduleMarkerSync / MarkerSyncResult#missingFile, issue #6).
        long notShownInProblems = unmapped + missingFileMarkerCount;
        if (notShownInProblems > 0)
        {
            text += NLS.bind(Messages.IssuesView_Status_UnmappedCount, Long.valueOf(notShownInProblems));
        }
        return text;
    }

    private void updateBanner()
    {
        GridData data = (GridData)bannerComposite.getLayoutData();
        if (branchState.missingOnServer())
        {
            bannerLabel.setText(NLS.bind(Messages.IssuesView_BranchMissing, branchState.requestedBranch()));
            bannerLink.setText("<a>" + Messages.IssuesView_SendBranchToAnalysis + "</a>"); //$NON-NLS-1$ //$NON-NLS-2$
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

    @Override
    public void dispose()
    {
        // Cancel any refresh or analysis still running for this view, so closing the view does not leave an
        // ownerless download, analyzer or scanner process behind.
        if (inFlightJob != null)
        {
            inFlightJob.cancel();
            inFlightJob = null;
        }
        super.dispose();
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
