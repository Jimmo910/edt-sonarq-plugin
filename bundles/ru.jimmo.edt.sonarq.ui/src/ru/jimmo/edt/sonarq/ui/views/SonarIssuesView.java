/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.views;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
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
import org.eclipse.jface.viewers.StructuredSelection;
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
import ru.jimmo.edt.sonarq.core.client.SonarHttpClients;
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
import ru.jimmo.edt.sonarq.core.suppress.SuppressionOutcome;
import ru.jimmo.edt.sonarq.ui.Messages;
import ru.jimmo.edt.sonarq.ui.SonarqPlugin;
import ru.jimmo.edt.sonarq.ui.markers.MarkerSyncJob;
import ru.jimmo.edt.sonarq.ui.markers.MarkerSyncResult;
import ru.jimmo.edt.sonarq.ui.resources.IssueAnchors;
import ru.jimmo.edt.sonarq.ui.resources.WorkspaceFiles;
import ru.jimmo.edt.sonarq.ui.settings.AnalysisLaunchConfigFactory;
import ru.jimmo.edt.sonarq.ui.settings.PreferenceConstants;
import ru.jimmo.edt.sonarq.ui.settings.ProjectBindingStore;
import ru.jimmo.edt.sonarq.ui.settings.SecureTokenStore;
import ru.jimmo.edt.sonarq.ui.settings.SonarConnectionFactory;
import ru.jimmo.edt.sonarq.ui.suppress.SuppressionApplier;
import ru.jimmo.edt.sonarq.ui.suppress.SuppressionMessages;
import ru.jimmo.edt.sonarq.ui.sync.ProjectRefreshInputs;
import ru.jimmo.edt.sonarq.ui.sync.RefreshInputsFactory;

/** The SonarQube Issues view: a full-height tree of issues grouped by file, rule or severity. */
public class SonarIssuesView extends ViewPart
{
    /** The view id. */
    public static final String VIEW_ID = "ru.jimmo.edt.sonarq.ui.views.issues"; //$NON-NLS-1$

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss"); //$NON-NLS-1$

    /** The EDT nature of a 1C configuration project (see {@link #isOneCProject}). */
    private static final String V8_CONFIGURATION_NATURE = "com._1c.g5.v8.dt.core.V8ConfigurationNature"; //$NON-NLS-1$

    /** The EDT nature of a 1C extension project (see {@link #isOneCProject}). */
    private static final String V8_EXTENSION_NATURE = "com._1c.g5.v8.dt.core.V8ExtensionNature"; //$NON-NLS-1$

    /** The conventional source folder of a 1C project (see {@link #isOneCProject}). */
    private static final String SOURCE_FOLDER_NAME = "src"; //$NON-NLS-1$

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
    private final RefreshGeneration refreshGeneration = new RefreshGeneration();
    private int missingFileMarkerCount;
    /** Whether the snapshot on screen came from a local analysis; see {@link #truncationMessage}. */
    private boolean lastRefreshWasLocal;
    private Job inFlightJob;
    private TreeColumn severityColumn;
    private TreeColumn ruleColumn;
    private final IssueColumnWidths columnWidths = new IssueColumnWidths();

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
                if (selectedProject != null
                    && IssueNavigation.open(getSite().getPage(), selectedProject, entry)
                        == IssueNavigation.OpenOutcome.FILE_UNAVAILABLE)
                {
                    applyFileUnavailableStatus(entry);
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
     *
     * <p>Runs on every {@link #rebuildTree()}, i.e. after every refresh - including the unattended auto-sync
     * ones - so it must not touch a column whose hidden state did not change: re-applying the designed width
     * would throw away whatever width the user dragged the column to. {@link IssueColumnWidths} owns that
     * decision and the remembered widths.
     */
    private void applyColumnVisibility()
    {
        Set<IssueColumn> columnsToHide = hiddenColumnFor(grouping);
        applyColumnHidden(IssueColumn.SEVERITY, severityColumn, SEVERITY_COLUMN_WIDTH,
            columnsToHide.contains(IssueColumn.SEVERITY));
        applyColumnHidden(IssueColumn.RULE, ruleColumn, RULE_COLUMN_WIDTH,
            columnsToHide.contains(IssueColumn.RULE));
    }

    /**
     * Applies one column's requested hidden state, leaving its width alone when the state did not change.
     *
     * @param id the column's identity in {@link #columnWidths}, not {@code null}
     * @param column the tree column to update, not {@code null}
     * @param defaultWidth the column's designed width, used when no user width was ever remembered
     * @param hide {@code true} to hide the column, {@code false} to show it
     */
    private void applyColumnHidden(IssueColumn id, TreeColumn column, int defaultWidth, boolean hide)
    {
        OptionalInt width = columnWidths.widthFor(id, hide, column.getWidth(), defaultWidth);
        if (width.isEmpty())
        {
            return;
        }
        column.setWidth(width.getAsInt());
        column.setResizable(!hide);
    }

    private void createColumns()
    {
        addColumn(Messages.IssuesView_Column_Location, LOCATION_COLUMN_WIDTH, element ->
        {
            // The counts must describe the rows actually shown under the node, not every row it holds: the
            // tree's IssueViewerFilter hides entries rejected by the same state, so an unfiltered count would
            // claim "File.bsl (12)" over two visible children.
            if (element instanceof IssueSuperGroup superGroup)
            {
                return superGroup.label() + " (" + state.countMatching(superGroup) + ')'; //$NON-NLS-1$
            }
            if (element instanceof IssueGroup group)
            {
                return group.label() + " (" + state.countMatching(group) + ')'; //$NON-NLS-1$
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

    /**
     * Rebuilds the tree from the current snapshot, keeping the user's expanded nodes and selection.
     *
     * <p>{@link TreeViewer#setInput} resets both, and this runs on every refresh - including the unattended
     * background auto-sync ones, while the user is reading the tree - and on the in-place update after a
     * single quick-suppress. Both used to collapse everything (review minor M8).
     *
     * <p>Restoration is keyed on {@link #elementKey}, not on the elements themselves: the tree nodes are
     * records, so their equality is structural, and both paths that rebuild the tree change that structure.
     * A suppression renumbers the issues below it in the file, and a refresh brings fresh issues altogether,
     * so the rebuilt nodes are not {@code equals} to the captured ones even where they represent the same
     * file, rule or issue - JFace's own element-identity restoration would drop exactly the nodes the user is
     * looking at.
     */
    private void rebuildTree()
    {
        applyColumnVisibility();
        Set<String> expandedKeys = elementKeys(viewer.getExpandedElements());
        Set<String> selectedKeys = elementKeys(viewer.getStructuredSelection().toArray());
        if (snapshot == null)
        {
            viewer.setInput(List.of());
            return;
        }
        List<Object> roots = IssueTreeBuilder.build(snapshot.issues(), boundProjectKey, boundPathPrefix, grouping);
        viewer.setInput(roots);
        viewer.setExpandedElements(elementsForKeys(roots, expandedKeys, false).toArray());
        List<Object> selection = elementsForKeys(roots, selectedKeys, true);
        if (!selection.isEmpty())
        {
            viewer.setSelection(new StructuredSelection(selection), true);
        }
    }

    /**
     * Maps tree elements to their {@link #elementKey} identities.
     *
     * @param elements the elements, not {@code null}
     * @return their keys, never {@code null}
     */
    private static Set<String> elementKeys(Object[] elements)
    {
        Set<String> keys = new HashSet<>();
        for (Object element : elements)
        {
            keys.add(elementKey(element));
        }
        return keys;
    }

    /**
     * Computes the stable identity of an issue-tree node, used to carry expansion and selection across a
     * rebuild (see {@link #rebuildTree}).
     *
     * <p>Stable means: unchanged by anything a rebuild does to the node's contents. A group is therefore
     * keyed on its label alone - the file path, rule key or severity name it groups by - and not on the
     * issues it holds, whose count and line numbers change on every refresh. An issue is keyed on its
     * {@link SonarIssue#key()}, which local analysis derives from rule and location and a server assigns; its
     * line number is deliberately not part of the key, since a quick-suppress shifts it.
     *
     * <p>The type prefix keeps the three node kinds apart, so a file named like a rule cannot inherit the
     * other's expansion state. Two same-labelled groups under different parents (the by-severity tree nests
     * rule groups under each severity, and one rule can report issues at more than one severity) do share a
     * key and are therefore expanded together - harmless, and the alternative, keying on the parent chain,
     * would break as soon as a rule's issues move between severities.
     *
     * <p>Pure and SWT-free by design, so it can be unit-tested without a display.
     *
     * @param element a tree node ({@link IssueSuperGroup}, {@link IssueGroup} or {@link IssueEntry}), not
     *     {@code null}
     * @return the node's stable key, never {@code null}
     */
    static String elementKey(Object element)
    {
        if (element instanceof IssueSuperGroup superGroup)
        {
            return "S:" + superGroup.label(); //$NON-NLS-1$
        }
        if (element instanceof IssueGroup group)
        {
            return "G:" + group.label(); //$NON-NLS-1$
        }
        if (element instanceof IssueEntry entry)
        {
            return "E:" + entry.issue().key(); //$NON-NLS-1$
        }
        return "?:" + element; //$NON-NLS-1$
    }

    /**
     * Collects the nodes of a freshly built tree whose {@link #elementKey} is in {@code keys}.
     *
     * <p>Walks the whole tree, which is at most three levels deep (see {@link IssueTreeBuilder#build}).
     * Leaves are only collected when asked for: expansion applies to group nodes alone, while selection can
     * name an individual issue.
     *
     * <p>Pure and SWT-free by design, so it can be unit-tested without a display.
     *
     * @param roots the rebuilt tree's top-level nodes, not {@code null}
     * @param keys the keys to look for, not {@code null}
     * @param includeEntries {@code true} to also match {@link IssueEntry} leaves
     * @return the matching nodes, never {@code null}
     */
    static List<Object> elementsForKeys(List<Object> roots, Set<String> keys, boolean includeEntries)
    {
        List<Object> found = new ArrayList<>();
        if (keys.isEmpty())
        {
            return found;
        }
        for (Object root : roots)
        {
            collectMatching(root, keys, includeEntries, found);
        }
        return found;
    }

    /**
     * Adds {@code node} and, recursively, its children to {@code found} when their key is in {@code keys}.
     *
     * @param node the node to test, not {@code null}
     * @param keys the keys to look for, not {@code null}
     * @param includeEntries {@code true} to also match {@link IssueEntry} leaves
     * @param found the accumulator, not {@code null}, mutated in place
     */
    private static void collectMatching(Object node, Set<String> keys, boolean includeEntries, List<Object> found)
    {
        boolean isEntry = node instanceof IssueEntry;
        if ((includeEntries || !isEntry) && keys.contains(elementKey(node)))
        {
            found.add(node);
        }
        if (node instanceof IssueSuperGroup superGroup)
        {
            for (IssueGroup group : superGroup.groups())
            {
                collectMatching(group, keys, includeEntries, found);
            }
        }
        else if (includeEntries && node instanceof IssueGroup group)
        {
            // Only the selection ever names a leaf; expansion never does, and a large snapshot holds tens of
            // thousands of them.
            for (IssueEntry entry : group.entries())
            {
                collectMatching(entry, keys, includeEntries, found);
            }
        }
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
     * <p>The in-memory renumbering only runs when {@link SuppressionApplier#apply} reports it really wrote
     * the comment pair. A refused or guarded-away edit leaves the file unchanged, so shifting the model
     * after it would desynchronize the two and make the next suppression in the same file wrap the wrong
     * lines; on such a no-op this action simply does nothing.
     *
     * @param entry the issue entry to suppress, not {@code null}
     */
    private void suppressIssue(IssueEntry entry)
    {
        IFile file = selectedProject.getFile(entry.relativePath());
        try
        {
            // A file changed behind the workspace's back (a git checkout run outside EDT) is not in the
            // resource tree until something refreshes it. That used to happen only inside marker
            // synchronization, which is skipped entirely when editor markers are switched off, so this action
            // silently did nothing for such a file (review minor M11).
            if (!WorkspaceFiles.existsAfterRefresh(file))
            {
                applyFileUnavailableStatus(entry);
                return;
            }
            SuppressionOutcome outcome = SuppressionApplier.apply(file, entry.issue().line(),
                entry.issue().ruleKey(), entry.issue().lineAnchor(), getSite().getPage());
            if (outcome.inserted())
            {
                applySuppressionLineShift(entry.issue());
            }
            else
            {
                applySuppressionRefusedStatus(outcome);
            }
        }
        catch (CoreException | BadLocationException e)
        {
            SonarqPlugin.getInstance().getLog().error(e.getMessage(), e);
        }
    }

    /**
     * Tells the user, on the status line, that an issue's file is not in the project - instead of leaving a
     * double-click or a "Suppress issue" click looking like it did nothing at all (review minor M11).
     *
     * <p>Deliberately not a dialog: nothing in this view may pop up a modal window outside an explicit user
     * click on the "Details" link (see {@link #showErrorDetails}). This is a note, not a failure, so
     * {@link #lastErrorMessage} is left alone and the link is hidden with it.
     *
     * @param entry the entry whose file could not be resolved, not {@code null}
     */
    private void applyFileUnavailableStatus(IssueEntry entry)
    {
        String path = entry.relativePath() != null ? entry.relativePath() : entry.issue().componentKey();
        statusLabel.setText(NLS.bind(Messages.IssuesView_FileUnavailable, path));
        statusLabel.setToolTipText(null);
        setErrorDetailsVisible(false);
        statusLabel.getParent().layout();
    }

    /**
     * Tells the user, on the status line, that the suppression wrote nothing and why - most importantly when
     * the flagged line could no longer be verified, which is a refusal by design and not a failure.
     *
     * <p>Same channel and same reasoning as {@link #applyFileUnavailableStatus}: a note, never a dialog, so
     * that nothing in this view can pop up a modal window outside an explicit click on the "Details" link.
     *
     * @param outcome the refusal, not {@code null}
     */
    private void applySuppressionRefusedStatus(SuppressionOutcome outcome)
    {
        statusLabel.setText(SuppressionMessages.describe(outcome));
        statusLabel.setToolTipText(null);
        setErrorDetailsVisible(false);
        statusLabel.getParent().layout();
    }

    /**
     * Applies the same in-place model update {@link #suppressIssue} performs, for a suppression that was
     * applied from outside this view - the Problems view's "Suppress" quick fix (see
     * {@code ru.jimmo.edt.sonarq.ui.suppress.SuppressMarkerResolution}). Without it the file would grow by
     * the two comment lines while this view kept the old line numbers, which is the same desynchronization
     * as shifting after a no-op, only from the other direction.
     *
     * <p>Does nothing when the view holds no issues yet, when its controls are gone, or when the suppressed
     * issue is not part of the current snapshot (a refresh has since replaced it). Must be called on the UI
     * thread, as marker resolutions are.
     *
     * @param issueKey the {@link SonarIssue#key()} of the issue that was just suppressed, may be
     *     {@code null} or empty, in which case nothing happens
     */
    public void issueSuppressedExternally(String issueKey)
    {
        if (issueKey == null || issueKey.isEmpty() || snapshot == null || viewer == null
            || viewer.getControl().isDisposed())
        {
            return;
        }
        snapshot.issues()
            .stream()
            .filter(issue -> issueKey.equals(issue.key()))
            .findFirst()
            .ifPresent(this::applySuppressionLineShift);
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
     * follow-up). Only ever called once an insertion is known to have happened.
     *
     * <p>The edit also retires the current {@link RefreshGeneration}. A refresh may well be in flight - in
     * local-analysis mode a large configuration takes minutes - and its results were computed from the
     * sources as they were before these two comment lines existed. Letting such a result through would
     * replace the snapshot just shifted here with pre-edit line numbers, and re-sync pre-edit markers on top
     * of an already-edited file: the very desynchronization this method exists to prevent, only reintroduced
     * from behind. {@link #scheduleMarkerSync} reads the generation after the bump, so the marker sync
     * started right below is still applied; the dropped refresh leaves the status line without its
     * completion callback, so it is refreshed here instead (unless an error message is on screen, which
     * outranks a plain issue count).
     *
     * @param issue the issue that was just suppressed, not {@code null}
     */
    private void applySuppressionLineShift(SonarIssue issue)
    {
        if (snapshot == null)
        {
            return;
        }
        List<SonarIssue> adjusted = SuppressionLineShift.applyAfterSuppress(snapshot.issues(), issue);
        snapshot = new IssueSnapshot(snapshot.query(), adjusted, adjusted.size(), snapshot.loadedAt());
        refreshGeneration.invalidate();
        rebuildTree();
        scheduleMarkerSync();
        if (lastErrorMessage == null)
        {
            updateStatusAndBanner();
        }
    }

    private void refreshIssues()
    {
        long generation = refreshGeneration.start();
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
        // Recorded from the provider actually used, rather than re-read from the preferences when the status
        // line is built: the mode can be switched while a refresh is in flight, and the advice on screen has
        // to describe the run that produced the issues (see #truncationMessage).
        lastRefreshWasLocal = RefreshIssuesJob.isLocalProvider(refreshedProvider);
        applyRunningStatus();
        showEngineDownloadHintIfNeeded();
        scheduleTracked(new RefreshIssuesJob(refreshedProvider, project, refreshInputs.binding(), sessionBranch,
            result -> onRefreshFinished(generation, project, refreshInputs, result)));
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
     * only lists the version directories under the state directory and stats a launcher in them (see
     * {@link BslServerInstaller#isInstalled}), never touches the network. Whichever way the scheduled job
     * ends, {@link #onRefreshFinished} overwrites this text once it completes.
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
     * Asks the previous refresh or analysis job to cancel, then schedules and tracks the new one.
     *
     * <p>What this actually guarantees, precisely:
     *
     * <ul>
     * <li>the previous job is <em>asked</em> to stop - {@link Job#cancel()} does not wait for it, and a job
     * already running keeps running until its own body observes the cancelled monitor (in local-analysis mode
     * that is bounded by {@code ProcessAnalyzeRunner}'s 500 ms poll, plus the time it takes to destroy the
     * language-server process);</li>
     * <li>the new job does not <em>start</em> while a job of the same project is still running, because both
     * carry a {@link ru.jimmo.edt.sonarq.ui.sync.ProjectAnalysisRule} and the job manager holds the newcomer
     * until the rule is free;</li>
     * <li>a superseded job's result cannot reach the tree, because {@link #onRefreshFinished} drops any
     * result whose {@link #refreshGeneration} is no longer current.</li>
     * </ul>
     *
     * <p>What it does <em>not</em> guarantee: cross-project exclusion. Switching the selected project
     * schedules a job under a different {@link ru.jimmo.edt.sonarq.ui.sync.ProjectAnalysisRule}, which does
     * not conflict with the outgoing one, so the two can overlap for as long as the cancelled job takes to
     * notice. That overlap is safe rather than merely tolerated: the report directory is per project key (see
     * {@code LocalIssueProvider}), and the one genuinely shared resource - the managed BSL Language Server
     * installation - is serialized by {@code BslServerInstaller}'s own install lock.
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
        ISonarServerClient client = SonarHttpClients.shared(connection);
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

    /**
     * Receives a finished refresh <em>in the refresh job's thread</em>, fingerprints the issues' source lines
     * there, and applies the result on the UI thread.
     *
     * <p>The anchoring has to happen here, before the snapshot reaches the view: it reads every file the
     * issues point at, which must not happen on the UI thread, and it is what later lets a quick-suppress
     * verify the line it is about to edit instead of trusting a number that a local edit - or the server's
     * own memory of its last analysis - may already have invalidated (see {@link IssueAnchors}). The mapping
     * inputs are the ones this refresh was scheduled with, passed in rather than read from the view's fields,
     * because those fields belong to the UI thread.
     *
     * @param generation the refresh generation this result belongs to
     * @param project the project the refresh ran for, not {@code null}
     * @param inputs the inputs the refresh was scheduled with, not {@code null}
     * @param result the refresh outcome, not {@code null}
     */
    private void onRefreshFinished(long generation, IProject project, ProjectRefreshInputs inputs,
        RefreshResult result)
    {
        IssueSnapshot anchored = result.isError() ? null
            : IssueAnchors.anchor(project, inputs.mappingProjectKey(), inputs.mappingPathPrefix(),
                result.snapshot());
        Display.getDefault().asyncExec(() ->
        {
            if (viewer.getControl().isDisposed())
            {
                return;
            }
            if (!refreshGeneration.isCurrent(generation))
            {
                return;
            }
            if (result.isError())
            {
                applyErrorStatus(result.errorMessage());
                return;
            }
            setInput(anchored, result.branchState());
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
     * Schedules a {@link MarkerSyncJob} that replaces the workspace markers of {@link #selectedProject} with
     * markers derived from the current {@link #snapshot}, unless the user disabled editor markers.
     *
     * <p>The synchronization has to happen in a job of its own, scoped to the project's resource rule,
     * rather than in the refresh job that produced the snapshot: that job runs under a
     * {@link ru.jimmo.edt.sonarq.ui.sync.ProjectAnalysisRule}, which contains no resource rule (see
     * {@link MarkerSyncJob}).
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
        long generation = refreshGeneration.current();
        new MarkerSyncJob(project,
            () -> IssueTreeBuilder.toEntries(markerSnapshot.issues(), projectKey, pathPrefix),
            result -> Display.getDefault().asyncExec(() -> applyMarkerSyncResult(generation, result)))
                .schedule();
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
        if (viewer.getControl().isDisposed() || !refreshGeneration.isCurrent(generation))
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

    /**
     * Picks the "showing first N of M" message that matches where the issues came from.
     *
     * <p>Local analysis caps its own report at {@code LocalIssueProvider#MAX_ISSUES} just as the server-mode
     * search caps its paging, so both modes can render a truncated snapshot - but the server-mode advice,
     * "narrow the filters on the server side", is meaningless when nothing was fetched from a server. What
     * actually shortens a local run is a narrower analysis scope (the project's Analysis scope properties) or
     * fewer enabled checks.
     *
     * <p>Pure and SWT-free by design, so it can be unit-tested without a display.
     *
     * @param localMode {@code true} when the snapshot came from a local BSL Language Server analysis
     * @return the message pattern to bind the counts into, never {@code null}
     */
    static String truncationMessage(boolean localMode)
    {
        return localMode ? Messages.IssuesView_Status_TruncatedLocal : Messages.IssuesView_Status_Truncated;
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
            text += "  " + NLS.bind(truncationMessage(lastRefreshWasLocal), //$NON-NLS-1$
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

    /**
     * Picks the project to work on when the user has not chosen one yet, preferring a 1C project.
     *
     * <p>The workspace of an EDT installation holds more than configuration projects (test fragments, plain
     * Java or documentation projects), and the projects come back in alphabetical order, so taking the first
     * open one would routinely bind the view to a project that has no BSL sources at all. An open project is
     * therefore only taken as-is if nothing 1C-shaped is open; the check itself stays cheap - a nature lookup
     * and, failing that, the existence of a {@code src} folder (see {@link #isOneCProject}) - because it runs
     * on the UI thread before every refresh.
     *
     * @return the preferred project, or {@code null} when the workspace has no open project at all
     */
    private static IProject firstOpenProject()
    {
        IProject firstOpen = null;
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
        {
            if (!project.isOpen())
            {
                continue;
            }
            if (isOneCProject(project))
            {
                return project;
            }
            if (firstOpen == null)
            {
                firstOpen = project;
            }
        }
        return firstOpen;
    }

    /**
     * Tells whether an open project looks like a 1C project this plug-in can analyze.
     *
     * <p>Recognizes the two EDT natures a 1C project carries - {@code V8ConfigurationNature} for a
     * configuration and {@code V8ExtensionNature} for an extension - and, as a fallback for a project whose
     * natures cannot be read (EDT not installed in this workbench, description not yet loaded), the
     * conventional {@code src} source folder a 1C project keeps its BSL modules in.
     *
     * <p>Package-private and taking the project as an argument so the headless test fragment can drive it
     * with real workspace projects. Never throws: an unreadable project simply is not preferred.
     *
     * @param project the open workspace project to test, not {@code null}
     * @return {@code true} when the project carries an EDT nature or has a {@code src} folder
     */
    static boolean isOneCProject(IProject project)
    {
        try
        {
            if (project.hasNature(V8_CONFIGURATION_NATURE) || project.hasNature(V8_EXTENSION_NATURE))
            {
                return true;
            }
        }
        catch (CoreException e)
        {
            // The project closed underneath us, or its description cannot be read: fall through to the
            // layout check, which needs neither.
        }
        return project.getFolder(SOURCE_FOLDER_NAME).exists();
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
