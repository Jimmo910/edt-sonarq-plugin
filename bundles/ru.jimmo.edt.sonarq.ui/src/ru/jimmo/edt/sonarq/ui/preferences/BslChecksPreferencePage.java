/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.preferences;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IPreferencesService;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.ICheckStateProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.osgi.service.prefs.BackingStoreException;

import ru.jimmo.edt.sonarq.core.analysis.TimeoutDownloads;
import ru.jimmo.edt.sonarq.core.checks.CategoryEntry;
import ru.jimmo.edt.sonarq.core.checks.DiagnosticCategories;
import ru.jimmo.edt.sonarq.core.checks.DiagnosticCategory;
import ru.jimmo.edt.sonarq.core.localanalysis.BslServerInstaller;
import ru.jimmo.edt.sonarq.core.localanalysis.BslUpdateChannel;
import ru.jimmo.edt.sonarq.core.localanalysis.DiagnosticsCatalog;
import ru.jimmo.edt.sonarq.core.localanalysis.ProcessAnalyzeRunner;
import ru.jimmo.edt.sonarq.core.localanalysis.SarifParser;
import ru.jimmo.edt.sonarq.core.localanalysis.SarifReport;
import ru.jimmo.edt.sonarq.ui.Messages;
import ru.jimmo.edt.sonarq.ui.SonarqPlugin;
import ru.jimmo.edt.sonarq.ui.settings.PreferenceConstants;

/**
 * Workspace preference page for choosing which BSL Language Server diagnostics local analysis reports.
 *
 * <p>The displayed set of diagnostics is the union of the bundled {@link DiagnosticCategories} catalog
 * (shipped with the plugin, always available) and any previously fetched catalog cached in the plugin
 * state directory by {@link DiagnosticsCatalog} - written automatically after every successful local
 * analysis run, or on demand by the <em>Fetch Checks List</em> button on this page, which runs the language
 * server against an empty temporary source directory (yielding zero issues but the full rule catalog).
 * Merging the two means this page works fully populated even before the user has ever fetched or analyzed
 * anything (see {@link #mergeDisplayedKeys(DiagnosticCategories, List)}). A diagnostic is disabled when its
 * key is <em>unchecked</em> in the tree; the set of disabled keys is stored, comma-joined, in
 * {@link PreferenceConstants#PREF_DISABLED_BSL_DIAGNOSTICS} - unchanged from the previous flat-table and
 * curated-category versions of this page, so existing preference values remain valid.
 *
 * <p>The tree groups diagnostics by the BSL Language Server's own taxonomy, chosen from the "Group by"
 * combo ({@link GroupBy}): by {@link GroupBy#TYPE type}, by {@link GroupBy#TAG tag} (a multi-tag diagnostic
 * appears once under each of its tags, as a distinct {@link TagLeaf} per tag), or {@link GroupBy#NONE not
 * grouped at all}. The curated {@link DiagnosticCategory} is no longer a visual tree group - it still drives
 * {@link #applyRecommendedProfile()} and the EDT-duplicate tooltip ({@link #rowTooltip(Object)}), read via
 * {@link DiagKey#category()}.
 *
 * <p>The checked state of every row is derived from a single {@code disabledKeys} model set through an
 * {@link ICheckStateProvider}, rather than tracked as independent widget state: this keeps <em>Enable
 * All</em>/<em>Disable All</em>/<em>Apply Recommended Profile</em> and a catalog refresh or grouping change
 * trivially correct regardless of the current text filter or tree expansion, since the filter only ever
 * hides rows in the underlying {@link Tree} widget and never touches the model. A group (parent) node
 * reports itself checked when at least one of its leaves is enabled, and grayed when its leaves are a mix
 * of enabled and disabled; toggling a group's checkbox enables or disables every diagnostic in that group.
 * Because a leaf's checked state always derives from the underlying diagnostic key rather than the leaf
 * object itself, toggling one {@link TagLeaf} of a multi-tag diagnostic and refreshing reflects under every
 * other tag it appears under too.
 */
public class BslChecksPreferencePage extends PreferencePage implements IWorkbenchPreferencePage
{
    private static final String DIAGNOSTICS_DOCS_URL =
        "https://1c-syntax.github.io/bsl-language-server/diagnostics/"; //$NON-NLS-1$
    private static final String EMPTY_TEXT = ""; //$NON-NLS-1$
    private static final String LIST_SEPARATOR = ","; //$NON-NLS-1$
    private static final String TAG_SEPARATOR = ", "; //$NON-NLS-1$
    private static final String TEMP_SRC_PREFIX = "sonarq-bsl-checks-src"; //$NON-NLS-1$
    private static final String TEMP_REPORT_PREFIX = "sonarq-bsl-checks-report"; //$NON-NLS-1$
    private static final String CATALOG_PROJECT_KEY = "bsl-checks-catalog"; //$NON-NLS-1$
    private static final String LINK_OPEN = "<a>"; //$NON-NLS-1$
    private static final String LINK_CLOSE = "</a>"; //$NON-NLS-1$

    private static final int KEY_COLUMN_WIDTH = 200;
    private static final int NAME_COLUMN_WIDTH = 320;
    private static final int DESCRIPTION_HEIGHT_HINT = 130;

    private final Set<String> disabledKeys = new HashSet<>();

    private final Map<String, String> descriptionByKey = new HashMap<>();

    private final DiagnosticCategories categories = DiagnosticCategories.load();

    private List<DiagnosticsCatalog.Entry> fetchedCatalog = List.of();

    private List<DiagKey> allDiagKeys = List.of();

    private List<Object> rootNodes = List.of();

    private GroupBy groupBy = GroupBy.TYPE;

    private String currentRuleKey;

    private Text filterText;

    private Combo groupByCombo;

    private Composite treeArea;

    private CheckboxTreeViewer treeViewer;

    private Label hintLabel;

    private Label counterLabel;

    private Text descriptionText;

    private Link ruleDocsLink;

    private Label statusLabel;

    private Button enableAllButton;

    private Button disableAllButton;

    private Button applyProfileButton;

    private Button fetchButton;

    /**
     * How the tree groups displayed diagnostics: a purely visual choice made from the "Group by" combo,
     * distinct from the curated {@link DiagnosticCategory}, which no longer groups the tree but still
     * drives {@link #applyRecommendedProfile()} and the EDT-duplicate tooltip.
     */
    private enum GroupBy
    {
        /** No parent nodes: every diagnostic is a root-level leaf. */
        NONE,

        /** One parent per distinct BSL Language Server diagnostic type. */
        TYPE,

        /** One parent per distinct BSL Language Server diagnostic tag; a multi-tag diagnostic appears once
         *  under each of its tags. */
        TAG
    }

    /**
     * One diagnostic leaf: a displayed key with its resolved name, category, BSL Language Server type and
     * tags, and (for {@link DiagnosticCategory#EDT_DUPLICATE}) the id of the EDT built-in check it
     * duplicates.
     *
     * @param key the diagnostic (rule) key, not {@code null}
     * @param name the resolved human-readable name, not {@code null}
     * @param category the resolved reason category, not {@code null}; not a visual tree group (see
     *     {@link GroupBy}), but still drives {@link #applyRecommendedProfile()} and the EDT-duplicate
     *     tooltip
     * @param edtCheck the duplicated EDT check id, or {@code null} when {@code category} is not
     *     {@link DiagnosticCategory#EDT_DUPLICATE}
     * @param type the BSL Language Server's own diagnostic type, e.g. {@code Code smell}, not {@code null};
     *     {@code ""} when unknown
     * @param tags the BSL Language Server's own diagnostic tags, not {@code null}; empty when unknown
     */
    record DiagKey(String key, String name, DiagnosticCategory category, String edtCheck, String type,
        List<String> tags)
    {
    }

    /**
     * A group (parent) tree node, used for {@link GroupBy#TYPE} and {@link GroupBy#TAG}: its label is the
     * grouped type or tag, and its children are {@link DiagKey} leaves ({@link GroupBy#TYPE}) or
     * {@link TagLeaf} leaves ({@link GroupBy#TAG}).
     *
     * @param label the group label: a raw BSL type, a raw tag, or {@link Messages#BslChecksPage_NoTags},
     *     not {@code null}
     * @param children the leaves in this group, not {@code null}, not empty
     */
    private record GroupNode(String label, List<Object> children)
    {
    }

    /**
     * One (tag, diagnostic) pairing: a distinct leaf node object used for {@link GroupBy#TAG}, so a
     * multi-tag diagnostic can appear as several distinct tree elements (one per tag) while its checkbox
     * state is always derived from the single underlying {@link #diagKey()}.
     *
     * @param tag the tag this leaf appears under, not {@code null}
     * @param diagKey the underlying diagnostic, not {@code null}
     */
    private record TagLeaf(String tag, DiagKey diagKey)
    {
    }

    @Override
    public void init(IWorkbench workbench)
    {
        setDescription(Messages.BslChecksPage_Description);
    }

    @Override
    protected Control createContents(Composite parent)
    {
        Composite composite = new Composite(parent, SWT.NONE);
        composite.setLayout(new GridLayout(2, false));

        Label groupByLabel = new Label(composite, SWT.NONE);
        groupByLabel.setText(Messages.BslChecksPage_GroupByLabel);

        groupByCombo = new Combo(composite, SWT.READ_ONLY);
        groupByCombo.setItems(new String[] {Messages.BslChecksPage_GroupBy_None, Messages.BslChecksPage_GroupBy_Type,
            Messages.BslChecksPage_GroupBy_Tag});
        groupByCombo.select(groupBy.ordinal());
        groupByCombo.addSelectionListener(SelectionListener.widgetSelectedAdapter(event -> onGroupByChanged()));

        filterText = new Text(composite, SWT.SEARCH | SWT.ICON_SEARCH | SWT.BORDER);
        filterText.setMessage(Messages.BslChecksPage_Filter_Hint);
        filterText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        filterText.addModifyListener(event -> treeViewer.refresh());

        createTreeArea(composite);

        counterLabel = new Label(composite, SWT.NONE);
        counterLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        createDescriptionArea(composite);

        createButtonBar(composite);

        Label profileHintLabel = new Label(composite, SWT.WRAP);
        profileHintLabel.setText(Messages.BslChecksPage_ProfileHint);
        profileHintLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        Label fetchWarningLabel = new Label(composite, SWT.WRAP);
        fetchWarningLabel.setText(Messages.BslChecksPage_FetchWarning);
        fetchWarningLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        statusLabel = new Label(composite, SWT.WRAP);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        Link docsLink = new Link(composite, SWT.NONE);
        docsLink.setText(LINK_OPEN + Messages.BslChecksPage_DocsLink + LINK_CLOSE);
        docsLink.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        docsLink.addSelectionListener(
            SelectionListener.widgetSelectedAdapter(event -> Program.launch(DIAGNOSTICS_DOCS_URL)));

        loadValues();
        return composite;
    }

    /**
     * Reacts to a "Group by" combo selection change: rebuilds the tree under the newly selected grouping
     * mode. Neither the displayed diagnostic set nor {@link #disabledKeys} is affected.
     */
    private void onGroupByChanged()
    {
        groupBy = GroupBy.values()[groupByCombo.getSelectionIndex()];
        rebuildDisplayedKeys();
    }

    /**
     * Builds the checkbox tree (columns Key/Name; group parents grouping diagnostic leaves per
     * {@link #groupBy}, filtered by {@link #filterText}) and the hint label shown instead when no
     * diagnostic is displayed, stacked in {@link #treeArea}.
     *
     * @param parent the page composite, not {@code null}
     */
    private void createTreeArea(Composite parent)
    {
        treeArea = new Composite(parent, SWT.NONE);
        treeArea.setLayout(new StackLayout());
        treeArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));

        treeViewer = new CheckboxTreeViewer(treeArea, SWT.BORDER | SWT.FULL_SELECTION);
        Tree tree = treeViewer.getTree();
        tree.setHeaderVisible(true);
        tree.setLinesVisible(true);
        ColumnViewerToolTipSupport.enableFor(treeViewer);

        TreeViewerColumn keyColumn = new TreeViewerColumn(treeViewer, SWT.NONE);
        keyColumn.getColumn().setText(Messages.BslChecksPage_Column_Key);
        keyColumn.getColumn().setWidth(KEY_COLUMN_WIDTH);
        keyColumn.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (element instanceof GroupNode groupNode)
                {
                    return groupNode.label();
                }
                return diagKeyOf(element).key();
            }

            @Override
            public String getToolTipText(Object element)
            {
                return rowTooltip(element);
            }
        });

        TreeViewerColumn nameColumn = new TreeViewerColumn(treeViewer, SWT.NONE);
        nameColumn.getColumn().setText(Messages.BslChecksPage_Column_Name);
        nameColumn.getColumn().setWidth(NAME_COLUMN_WIDTH);
        nameColumn.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (element instanceof GroupNode groupNode)
                {
                    return groupCounterText(groupNode);
                }
                return diagKeyOf(element).name();
            }

            @Override
            public String getToolTipText(Object element)
            {
                return rowTooltip(element);
            }
        });

        treeViewer.setContentProvider(new ITreeContentProvider()
        {
            @Override
            public Object[] getElements(Object inputElement)
            {
                return rootNodes.toArray();
            }

            @Override
            public Object[] getChildren(Object parentElement)
            {
                if (parentElement instanceof GroupNode groupNode)
                {
                    return groupNode.children().toArray();
                }
                return new Object[0];
            }

            @Override
            public Object getParent(Object element)
            {
                if (element instanceof GroupNode)
                {
                    return null;
                }
                for (Object root : rootNodes)
                {
                    if (root instanceof GroupNode groupNode && groupNode.children().contains(element))
                    {
                        return groupNode;
                    }
                }
                return null;
            }

            @Override
            public boolean hasChildren(Object element)
            {
                return element instanceof GroupNode groupNode && !groupNode.children().isEmpty();
            }
        });
        treeViewer.addFilter(new ViewerFilter()
        {
            @Override
            public boolean select(Viewer viewer, Object parentElement, Object element)
            {
                String query = filterText.getText().trim().toLowerCase(Locale.ROOT);
                if (query.isEmpty())
                {
                    return true;
                }
                if (element instanceof GroupNode groupNode)
                {
                    return groupNode.children().stream()
                        .anyMatch(child -> matchesFilter(diagKeyOf(child), query));
                }
                return matchesFilter(diagKeyOf(element), query);
            }
        });
        treeViewer.setCheckStateProvider(new ICheckStateProvider()
        {
            @Override
            public boolean isChecked(Object element)
            {
                if (element instanceof GroupNode groupNode)
                {
                    return groupNode.children().stream()
                        .anyMatch(child -> !disabledKeys.contains(diagKeyOf(child).key()));
                }
                return !disabledKeys.contains(diagKeyOf(element).key());
            }

            @Override
            public boolean isGrayed(Object element)
            {
                if (!(element instanceof GroupNode groupNode))
                {
                    return false;
                }
                boolean anyEnabled = groupNode.children().stream()
                    .anyMatch(child -> !disabledKeys.contains(diagKeyOf(child).key()));
                boolean anyDisabled = groupNode.children().stream()
                    .anyMatch(child -> disabledKeys.contains(diagKeyOf(child).key()));
                return anyEnabled && anyDisabled;
            }
        });
        treeViewer.addCheckStateListener(event ->
        {
            Object element = event.getElement();
            boolean checked = event.getChecked();
            if (element instanceof GroupNode groupNode)
            {
                for (Object child : groupNode.children())
                {
                    setEnabled(diagKeyOf(child).key(), checked);
                }
            }
            else
            {
                setEnabled(diagKeyOf(element).key(), checked);
            }
            // A single native checkbox toggle only updates the clicked row's own widget; refreshing keeps
            // the parent group's derived checked/grayed indicator (and, for a group toggle, every sibling
            // leaf - plus, in tag grouping, every other tag the same diagnostic appears under) in sync with
            // the disabledKeys model.
            treeViewer.refresh();
            updateCounter();
        });

        treeViewer.addSelectionChangedListener(event ->
        {
            Object element = ((IStructuredSelection)event.getSelection()).getFirstElement();
            if (element == null || element instanceof GroupNode)
            {
                clearRuleDescription();
            }
            else
            {
                showRuleDescription(diagKeyOf(element));
            }
        });

        hintLabel = new Label(treeArea, SWT.WRAP);
        hintLabel.setText(Messages.BslChecksPage_Empty);
    }

    /**
     * Builds the rule-description area shown below the tree: a title label, a read-only multi-line
     * description text, and a link to the diagnostic's online documentation, hidden until a leaf is
     * selected in {@link #treeViewer}.
     *
     * @param parent the page composite, not {@code null}
     */
    private void createDescriptionArea(Composite parent)
    {
        Label descriptionTitleLabel = new Label(parent, SWT.NONE);
        descriptionTitleLabel.setText(Messages.BslChecksPage_DescriptionTitle);
        descriptionTitleLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        descriptionText = new Text(parent, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.BORDER | SWT.V_SCROLL);
        GridData descriptionTextData = new GridData(SWT.FILL, SWT.FILL, true, false, 2, 1);
        descriptionTextData.heightHint = DESCRIPTION_HEIGHT_HINT;
        descriptionText.setLayoutData(descriptionTextData);

        ruleDocsLink = new Link(parent, SWT.NONE);
        ruleDocsLink.setText(LINK_OPEN + Messages.BslChecksPage_RuleDocsLink + LINK_CLOSE);
        GridData ruleDocsLinkData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        ruleDocsLinkData.exclude = true;
        ruleDocsLink.setLayoutData(ruleDocsLinkData);
        ruleDocsLink.setVisible(false);
        ruleDocsLink.addSelectionListener(SelectionListener.widgetSelectedAdapter(
            event -> Program.launch(DIAGNOSTICS_DOCS_URL + currentRuleKey + "/"))); //$NON-NLS-1$
    }

    /**
     * Shows the given diagnostic's description in {@link #descriptionText} and reveals
     * {@link #ruleDocsLink} targeting it.
     *
     * @param diagKey the selected diagnostic leaf, not {@code null}
     */
    private void showRuleDescription(DiagKey diagKey)
    {
        currentRuleKey = diagKey.key();
        descriptionText.setText(descriptionBody(diagKey, descriptionByKey.get(diagKey.key())));
        setRuleDocsLinkVisible(true);
    }

    /**
     * Clears {@link #descriptionText} and hides {@link #ruleDocsLink}, used when the tree selection is
     * empty or a {@link GroupNode}.
     */
    private void clearRuleDescription()
    {
        currentRuleKey = null;
        descriptionText.setText(EMPTY_TEXT);
        setRuleDocsLinkVisible(false);
    }

    /**
     * Shows or hides {@link #ruleDocsLink}, excluding it from the layout when hidden so it does not leave a
     * blank gap below the description text.
     *
     * @param visible {@code true} to show the link, {@code false} to hide it
     */
    private void setRuleDocsLinkVisible(boolean visible)
    {
        ((GridData)ruleDocsLink.getLayoutData()).exclude = !visible;
        ruleDocsLink.setVisible(visible);
        ruleDocsLink.getParent().layout();
    }

    /**
     * Resolves the underlying {@link DiagKey} of a tree leaf, whichever grouping mode produced it.
     *
     * @param leaf a tree leaf: a {@link DiagKey} ({@link GroupBy#NONE}/{@link GroupBy#TYPE}) or a
     *     {@link TagLeaf} ({@link GroupBy#TAG})
     * @return the underlying diagnostic, never {@code null}
     */
    private static DiagKey diagKeyOf(Object leaf)
    {
        if (leaf instanceof TagLeaf tagLeaf)
        {
            return tagLeaf.diagKey();
        }
        return (DiagKey)leaf;
    }

    /**
     * Whether a leaf's key or name contains the (already lower-cased) filter query.
     *
     * @param diagKey the leaf to test, not {@code null}
     * @param lowerCaseQuery the non-empty, already lower-cased filter query, not {@code null}
     * @return {@code true} when the key or name contains {@code lowerCaseQuery}
     */
    private static boolean matchesFilter(DiagKey diagKey, String lowerCaseQuery)
    {
        return diagKey.key().toLowerCase(Locale.ROOT).contains(lowerCaseQuery)
            || diagKey.name().toLowerCase(Locale.ROOT).contains(lowerCaseQuery);
    }

    /**
     * The tooltip for a diagnostic leaf: its BSL Language Server type and tags, plus (for
     * {@link DiagnosticCategory#EDT_DUPLICATE}) the EDT check it duplicates.
     *
     * @param element the tree element, a {@link GroupNode} or a leaf
     * @return the tooltip text, or {@code null} for a group node
     */
    private static String rowTooltip(Object element)
    {
        if (element instanceof GroupNode)
        {
            return null;
        }
        DiagKey diagKey = diagKeyOf(element);
        StringBuilder tooltip = new StringBuilder();
        tooltip.append(NLS.bind(Messages.BslChecksPage_RowTooltip_Type, diagKey.type()));
        tooltip.append('\n');
        tooltip.append(NLS.bind(Messages.BslChecksPage_RowTooltip_Tags, tagsDisplay(diagKey.tags())));
        if (diagKey.category() == DiagnosticCategory.EDT_DUPLICATE)
        {
            tooltip.append('\n');
            tooltip.append(NLS.bind(Messages.BslChecksPage_EvidenceTooltip, diagKey.edtCheck()));
        }
        return tooltip.toString();
    }

    /**
     * Renders a diagnostic's tags for display, reusing the same localized {@link
     * Messages#BslChecksPage_NoTags} label the tag-grouping "no tags" bucket parent uses (review minor,
     * issue #4/#5): the tooltip previously showed a hardcoded, non-localized {@code "-"} for this case.
     *
     * @param tags the diagnostic's tags, not {@code null}
     * @return {@link Messages#BslChecksPage_NoTags} when {@code tags} is empty, otherwise its elements
     *     joined with {@link #TAG_SEPARATOR}
     */
    private static String tagsDisplay(List<String> tags)
    {
        return tags.isEmpty() ? Messages.BslChecksPage_NoTags : String.join(TAG_SEPARATOR, tags);
    }

    /**
     * Builds the multi-line text shown in the description area for a selected diagnostic leaf: its name,
     * then its BSL Language Server type and tags, then its rule description (or a hint to fetch the checks
     * list/run an analysis first, when the description is not yet known). Pure and SWT-free, so it is unit
     * tested directly.
     *
     * @param diagKey the selected diagnostic, not {@code null}
     * @param description the diagnostic's rule description from {@link #descriptionByKey}, or {@code null}
     *     or blank when not yet known
     * @return the description area text, never {@code null}
     */
    static String descriptionBody(DiagKey diagKey, String description)
    {
        StringBuilder body = new StringBuilder();
        body.append(diagKey.name());
        body.append('\n');
        body.append('\n');
        body.append(NLS.bind(Messages.BslChecksPage_RowTooltip_Type, diagKey.type()));
        body.append("  "); //$NON-NLS-1$
        body.append(NLS.bind(Messages.BslChecksPage_RowTooltip_Tags, tagsDisplay(diagKey.tags())));
        body.append('\n');
        body.append('\n');
        boolean hasDescription = description != null && !description.isBlank();
        body.append(hasDescription ? description : Messages.BslChecksPage_Description_Empty);
        return body.toString();
    }

    /**
     * The "disabled n of m" counter shown in the Name column of a group's parent tree row.
     *
     * @param groupNode the group node, not {@code null}
     * @return the localized counter text, never {@code null}
     */
    private String groupCounterText(GroupNode groupNode)
    {
        long disabled =
            groupNode.children().stream().filter(child -> disabledKeys.contains(diagKeyOf(child).key())).count();
        return NLS.bind(Messages.BslChecksPage_GroupCounter, disabled, groupNode.children().size());
    }

    /**
     * Enables or disables a single diagnostic key in {@link #disabledKeys}.
     *
     * @param key the diagnostic key, not {@code null}
     * @param enabled {@code true} to enable (remove from the disabled set), {@code false} to disable
     */
    private void setEnabled(String key, boolean enabled)
    {
        if (enabled)
        {
            disabledKeys.remove(key);
        }
        else
        {
            disabledKeys.add(key);
        }
    }

    /**
     * Builds the <em>Enable All</em>/<em>Disable All</em>/<em>Apply Recommended Profile</em>/<em>Fetch
     * Checks List</em> button row.
     *
     * @param parent the page composite, not {@code null}
     */
    private void createButtonBar(Composite parent)
    {
        Composite bar = new Composite(parent, SWT.NONE);
        bar.setLayout(new GridLayout(4, false));
        bar.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));

        enableAllButton = new Button(bar, SWT.PUSH);
        enableAllButton.setText(Messages.BslChecksPage_EnableAll);
        enableAllButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(event -> enableAll()));

        disableAllButton = new Button(bar, SWT.PUSH);
        disableAllButton.setText(Messages.BslChecksPage_DisableAll);
        disableAllButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(event -> disableAll()));

        applyProfileButton = new Button(bar, SWT.PUSH);
        applyProfileButton.setText(Messages.BslChecksPage_ApplyProfile);
        applyProfileButton.setToolTipText(Messages.BslChecksPage_ProfileHint);
        applyProfileButton.addSelectionListener(
            SelectionListener.widgetSelectedAdapter(event -> applyRecommendedProfile()));

        fetchButton = new Button(bar, SWT.PUSH);
        fetchButton.setText(Messages.BslChecksPage_FetchCatalog);
        fetchButton.setToolTipText(Messages.BslChecksPage_FetchWarning);
        fetchButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(event -> fetchCatalog()));
    }

    /**
     * Loads the disabled-diagnostics preference into {@link #disabledKeys} and the cached fetched catalog,
     * then (re)builds the displayed, grouped diagnostic set.
     */
    private void loadValues()
    {
        IPreferencesService service = Platform.getPreferencesService();
        String stored = service.getString(SonarqPlugin.PLUGIN_ID,
            PreferenceConstants.PREF_DISABLED_BSL_DIAGNOSTICS, EMPTY_TEXT, null);
        disabledKeys.clear();
        for (String key : stored.split(LIST_SEPARATOR))
        {
            String trimmed = key.trim();
            if (!trimmed.isEmpty())
            {
                disabledKeys.add(trimmed);
            }
        }
        Path stateDir = Path.of(SonarqPlugin.getInstance().getStateLocation().toOSString());
        fetchedCatalog = DiagnosticsCatalog.load(stateDir.resolve(DiagnosticsCatalog.CATALOG_FILE_NAME));
        rebuildDisplayedKeys();
    }

    /**
     * Merges the bundled category catalog with {@link #fetchedCatalog} into {@link #allDiagKeys}, arranges
     * the result into {@link #rootNodes} per the current {@link #groupBy}, rebuilds
     * {@link #descriptionByKey} from {@link #fetchedCatalog} and reloads the tree.
     */
    private void rebuildDisplayedKeys()
    {
        allDiagKeys = mergeDisplayedKeys(categories, fetchedCatalog);
        rootNodes = buildRootNodes(allDiagKeys, groupBy);
        descriptionByKey.clear();
        for (DiagnosticsCatalog.Entry entry : fetchedCatalog)
        {
            descriptionByKey.put(entry.key(), entry.description());
        }
        reloadTree();
    }

    /**
     * Merges the bundled category catalog with a previously fetched diagnostics catalog into the flat,
     * key-sorted list of diagnostics to display.
     *
     * <p>The bundled catalog contributes every known key's category, BSL Language Server type/tags and
     * (for {@link DiagnosticCategory#EDT_DUPLICATE}) its EDT evidence; a diagnostic's name comes from the
     * bundled catalog when known, otherwise from the fetched catalog, falling back to the key itself. A key
     * present only in the fetched catalog is displayed under {@link DiagnosticCategory#GENERAL} with an
     * empty type and no tags, since the bundled catalog has no classification for it.
     *
     * @param categories the bundled category catalog, not {@code null}
     * @param fetched the previously fetched diagnostics catalog, not {@code null}
     * @return the merged, key-sorted list of displayed diagnostics, never {@code null}
     */
    static List<DiagKey> mergeDisplayedKeys(DiagnosticCategories categories,
        List<DiagnosticsCatalog.Entry> fetched)
    {
        Map<String, String> fetchedNames = new HashMap<>();
        for (DiagnosticsCatalog.Entry entry : fetched)
        {
            fetchedNames.put(entry.key(), entry.name());
        }
        Set<String> keys = new TreeSet<>(fetchedNames.keySet());
        for (CategoryEntry entry : categories.all())
        {
            keys.add(entry.key());
        }
        List<DiagKey> result = new ArrayList<>();
        for (String key : keys)
        {
            String name = categories.nameOf(key);
            if (name == null)
            {
                name = fetchedNames.getOrDefault(key, key);
            }
            result.add(new DiagKey(key, name, categories.categoryOf(key), categories.edtCheckOf(key),
                categories.typeOf(key), categories.tagsOf(key)));
        }
        return result;
    }

    /**
     * Groups diagnostic keys by their BSL Language Server type: the pure, SWT-free core of the
     * {@link GroupBy#TYPE} tree. A diagnostic contributes to exactly one entry.
     *
     * @param diagKeys the diagnostics to group, not {@code null}
     * @return the diagnostic keys grouped by type, type-sorted, never {@code null}
     */
    static Map<String, List<String>> groupKeysByType(List<DiagKey> diagKeys)
    {
        Map<String, List<String>> result = new TreeMap<>();
        for (DiagKey diagKey : diagKeys)
        {
            result.computeIfAbsent(diagKey.type(), type -> new ArrayList<>()).add(diagKey.key());
        }
        return result;
    }

    /**
     * Groups diagnostic keys by their BSL Language Server tags: the pure, SWT-free core of the
     * {@link GroupBy#TAG} tree. A diagnostic with several tags contributes to each of their entries; a
     * diagnostic with no tags contributes to the {@link Messages#BslChecksPage_NoTags} entry instead.
     *
     * @param diagKeys the diagnostics to group, not {@code null}
     * @return the diagnostic keys grouped by tag (plus the no-tags bucket), tag-sorted, never {@code null}
     */
    static Map<String, List<String>> groupKeysByTag(List<DiagKey> diagKeys)
    {
        Map<String, List<String>> result = new TreeMap<>();
        for (DiagKey diagKey : diagKeys)
        {
            List<String> tags = diagKey.tags();
            if (tags.isEmpty())
            {
                result.computeIfAbsent(Messages.BslChecksPage_NoTags, tag -> new ArrayList<>()).add(diagKey.key());
            }
            else
            {
                for (String tag : tags)
                {
                    result.computeIfAbsent(tag, key -> new ArrayList<>()).add(diagKey.key());
                }
            }
        }
        return result;
    }

    /**
     * Indexes diagnostics by key, for {@link #buildGroupNodes(Map, List, boolean)} to resolve a grouped key
     * back to its full {@link DiagKey}.
     *
     * @param diagKeys the diagnostics to index, not {@code null}
     * @return the diagnostics keyed by {@link DiagKey#key()}, never {@code null}
     */
    private static Map<String, DiagKey> indexByKey(List<DiagKey> diagKeys)
    {
        Map<String, DiagKey> byKey = new HashMap<>();
        for (DiagKey diagKey : diagKeys)
        {
            byKey.put(diagKey.key(), diagKey);
        }
        return byKey;
    }

    /**
     * Builds the tree's root nodes for the current {@link GroupBy} mode: group parents for
     * {@link GroupBy#TYPE} and {@link GroupBy#TAG}, or the flat, key-sorted leaves themselves for
     * {@link GroupBy#NONE}.
     *
     * @param diagKeys the diagnostics to arrange, not {@code null}
     * @param groupBy the grouping mode, not {@code null}
     * @return the root-level tree elements, never {@code null}
     */
    private static List<Object> buildRootNodes(List<DiagKey> diagKeys, GroupBy groupBy)
    {
        switch (groupBy)
        {
            case TYPE:
                return buildGroupNodes(groupKeysByType(diagKeys), diagKeys, false);
            case TAG:
                return buildGroupNodes(groupKeysByTag(diagKeys), diagKeys, true);
            default:
                return List.copyOf(diagKeys);
        }
    }

    /**
     * Converts a label-to-keys grouping into {@link GroupNode}s with fully resolved leaves.
     *
     * @param keysByLabel the diagnostic keys grouped by (type or tag) label, label-sorted, not {@code null}
     * @param diagKeys the full diagnostic list, used to resolve each key back to its {@link DiagKey}, not
     *     {@code null}
     * @param wrapAsTagLeaf {@code true} to wrap each leaf as a {@link TagLeaf} (tag grouping, where the same
     *     diagnostic can appear under several labels), {@code false} to use the {@link DiagKey} itself
     *     directly (type grouping, single membership)
     * @return the group nodes, in {@code keysByLabel} order, never {@code null}
     */
    private static List<Object> buildGroupNodes(Map<String, List<String>> keysByLabel, List<DiagKey> diagKeys,
        boolean wrapAsTagLeaf)
    {
        Map<String, DiagKey> byKey = indexByKey(diagKeys);
        List<Object> nodes = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : keysByLabel.entrySet())
        {
            String label = entry.getKey();
            List<Object> children = new ArrayList<>();
            for (String key : entry.getValue())
            {
                DiagKey diagKey = byKey.get(key);
                children.add(wrapAsTagLeaf ? new TagLeaf(label, diagKey) : diagKey);
            }
            nodes.add(new GroupNode(label, children));
        }
        return nodes;
    }

    /**
     * Computes the set of displayed diagnostic keys the recommended profile would disable: the pure,
     * SWT-free core of {@link #applyRecommendedProfile()}.
     *
     * @param displayedKeys the diagnostic keys currently displayed on the page, not {@code null}
     * @param categories the bundled category catalog, not {@code null}
     * @return the subset of {@code displayedKeys} the recommended profile disables, never {@code null}
     */
    static Set<String> recommendedToDisable(Set<String> displayedKeys, DiagnosticCategories categories)
    {
        return categories.recommendedDisabledKeys(displayedKeys);
    }

    /**
     * Re-populates the tree from {@link #rootNodes}. Since the checked state is derived entirely from
     * {@link #disabledKeys} (a set of diagnostic keys, not tree rows), replacing the input this way
     * automatically preserves whichever disabled keys are still present in the new displayed set.
     */
    private void reloadTree()
    {
        treeViewer.setInput(rootNodes);
        updateEmptyState();
        updateCounter();
    }

    /**
     * Shows the tree when at least one diagnostic is displayed, or {@link #hintLabel} when none is.
     */
    private void updateEmptyState()
    {
        StackLayout layout = (StackLayout)treeArea.getLayout();
        layout.topControl = allDiagKeys.isEmpty() ? hintLabel : treeViewer.getTree();
        treeArea.layout();
    }

    /**
     * Refreshes the "Disabled N of M" counter label from the currently displayed diagnostics and disabled
     * set. N counts only disabled keys that are part of the currently displayed set, so N never exceeds M
     * even when {@link #disabledKeys} also holds keys hidden from the current bundled/fetched catalog
     * merge (e.g. a diagnostic disabled by a previous version of this page that is absent from both the
     * bundled catalog and the cached fetched catalog).
     */
    private void updateCounter()
    {
        long displayedDisabled =
            allDiagKeys.stream().filter(diagKey -> disabledKeys.contains(diagKey.key())).count();
        counterLabel.setText(NLS.bind(Messages.BslChecksPage_Counter, displayedDisabled, allDiagKeys.size()));
    }

    /**
     * Removes only the currently displayed diagnostic keys from the disabled set, checking every displayed
     * diagnostic regardless of the active text filter. Unlike {@link #performDefaults()}, this leaves
     * disabled keys outside the displayed set untouched (e.g. a diagnostic absent from both the bundled and
     * cached fetched catalog), so they are not silently discarded on OK.
     */
    private void enableAll()
    {
        Set<String> displayedKeys = new HashSet<>();
        for (DiagKey diagKey : allDiagKeys)
        {
            displayedKeys.add(diagKey.key());
        }
        disabledKeys.removeAll(displayedKeys);
        treeViewer.refresh();
        updateCounter();
    }

    /**
     * Disables every displayed diagnostic, regardless of the active text filter.
     */
    private void disableAll()
    {
        for (DiagKey diagKey : allDiagKeys)
        {
            disabledKeys.add(diagKey.key());
        }
        treeViewer.refresh();
        updateCounter();
    }

    /**
     * Disables every displayed diagnostic the recommended profile flags (EDT duplicates, needs-tuning and
     * inappropriate categories), regardless of the active text filter. Diagnostics already disabled for
     * some other reason, and diagnostics outside the recommended set, are left untouched: this button only
     * ever adds to {@link #disabledKeys}, it never re-enables anything.
     */
    private void applyRecommendedProfile()
    {
        Set<String> displayedKeys = new HashSet<>();
        for (DiagKey diagKey : allDiagKeys)
        {
            displayedKeys.add(diagKey.key());
        }
        disabledKeys.addAll(recommendedToDisable(displayedKeys, categories));
        treeViewer.refresh();
        updateCounter();
    }

    /**
     * Schedules the user-visible job that fetches the full diagnostics catalog: ensures the language
     * server is installed, runs it against an empty temporary source directory (yielding the full rule
     * catalog with zero issues), persists the catalog and reloads the tree. Errors are reported on
     * {@link #statusLabel}; the page never opens a modal dialog. The fetch button is disabled for the
     * duration of the job (re-enabled from the job's done listener) so a double-click cannot schedule two
     * overlapping fetch jobs.
     */
    private void fetchCatalog()
    {
        statusLabel.setText(EMPTY_TEXT);
        Path stateDir = Path.of(SonarqPlugin.getInstance().getStateLocation().toOSString());
        Job job = Job.create(Messages.BslChecksPage_FetchJob_Name, monitor ->
        {
            return runFetchJob(stateDir, monitor);
        });
        job.setUser(true);
        job.addJobChangeListener(new JobChangeAdapter()
        {
            @Override
            public void done(IJobChangeEvent event)
            {
                Display.getDefault().asyncExec(() ->
                {
                    if (!fetchButton.isDisposed())
                    {
                        fetchButton.setEnabled(true);
                    }
                });
            }
        });
        fetchButton.setEnabled(false);
        job.schedule();
    }

    /**
     * Runs on the job thread: installs the language server if needed, analyzes an empty temporary source
     * directory, parses the resulting SARIF report into a catalog and persists it, then applies it to the
     * tree on the UI thread. Tolerates the page having been closed in the meantime (see
     * {@link #applyFetchedCatalog(List)} and {@link #reportFetchError(String)}).
     *
     * @param stateDir the plugin state directory, not {@code null}
     * @param monitor the job's progress monitor, not {@code null}
     * @return {@link Status#CANCEL_STATUS} if the user cancelled the job, {@link Status#OK_STATUS} otherwise
     *     (including on a reported failure - the failure itself is surfaced on {@link #statusLabel}, not as
     *     job status)
     */
    private IStatus runFetchJob(Path stateDir, IProgressMonitor monitor)
    {
        Path emptySrcDir = null;
        Path reportDir = null;
        try
        {
            // Task C replaces this temporary constant with the resolved PREF_BSL_LS_UPDATE_CHANNEL preference.
            Path executable =
                BslServerInstaller.ensureServer(stateDir, TimeoutDownloads::open, BslUpdateChannel.STABLE, monitor);
            emptySrcDir = Files.createTempDirectory(TEMP_SRC_PREFIX);
            reportDir = Files.createTempDirectory(TEMP_REPORT_PREFIX);
            Path sarif = new ProcessAnalyzeRunner().analyze(executable, emptySrcDir, reportDir, null, monitor);
            SarifReport report =
                SarifParser.parse(Files.readString(sarif, StandardCharsets.UTF_8), CATALOG_PROJECT_KEY);
            List<DiagnosticsCatalog.Entry> fetched = DiagnosticsCatalog.fromReport(report);
            DiagnosticsCatalog.save(stateDir.resolve(DiagnosticsCatalog.CATALOG_FILE_NAME), fetched);
            applyFetchedCatalog(fetched);
            return Status.OK_STATUS;
        }
        catch (OperationCanceledException e)
        {
            // The user cancelled the job from the progress dialog; nothing to report, but the job status
            // must reflect the cancellation rather than reporting OK.
            return Status.CANCEL_STATUS;
        }
        catch (IOException e)
        {
            reportFetchError(e.getMessage());
            return Status.OK_STATUS;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            reportFetchError(e.getMessage());
            return Status.OK_STATUS;
        }
        finally
        {
            deleteQuietly(emptySrcDir);
            deleteQuietly(reportDir);
        }
    }

    /**
     * Applies a freshly fetched catalog to the tree on the UI thread, guarded against the page having
     * been closed (widgets disposed) in the meantime.
     *
     * @param fetched the fetched catalog entries, not {@code null}
     */
    private void applyFetchedCatalog(List<DiagnosticsCatalog.Entry> fetched)
    {
        Display.getDefault().asyncExec(() ->
        {
            if (treeViewer.getTree().isDisposed())
            {
                return;
            }
            fetchedCatalog = fetched;
            rebuildDisplayedKeys();
            statusLabel.setText(EMPTY_TEXT);
        });
    }

    /**
     * Reports a fetch failure on {@link #statusLabel} on the UI thread, guarded against the page having
     * been closed (widgets disposed) in the meantime.
     *
     * @param message the failure detail, may be {@code null}
     */
    private void reportFetchError(String message)
    {
        Display.getDefault().asyncExec(() ->
        {
            if (!statusLabel.isDisposed())
            {
                statusLabel.setText(NLS.bind(Messages.BslChecksPage_FetchFailed, message));
            }
        });
    }

    /**
     * Recursively deletes a throwaway temporary directory, best-effort: a failure to clean up leftover
     * temp files must never fail the fetch job itself.
     *
     * @param dir the directory to delete, or {@code null}
     */
    private static void deleteQuietly(Path dir)
    {
        if (dir == null || !Files.isDirectory(dir))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
        catch (IOException e)
        {
            // Best-effort cleanup of a throwaway temp directory; nothing actionable to report here.
        }
    }

    @Override
    protected void performDefaults()
    {
        disabledKeys.clear();
        treeViewer.refresh();
        updateCounter();
        super.performDefaults();
    }

    @Override
    public boolean performOk()
    {
        List<String> sorted = new ArrayList<>(new TreeSet<>(disabledKeys));
        IEclipsePreferences node = InstanceScope.INSTANCE.getNode(SonarqPlugin.PLUGIN_ID);
        node.put(PreferenceConstants.PREF_DISABLED_BSL_DIAGNOSTICS, String.join(LIST_SEPARATOR, sorted));
        try
        {
            node.flush();
        }
        catch (BackingStoreException e)
        {
            SonarqPlugin.getInstance().getLog().error(e.getMessage(), e);
            return false;
        }
        return true;
    }
}
