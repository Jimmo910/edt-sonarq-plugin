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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import ru.jimmo.edt.sonarq.core.localanalysis.DiagnosticsCatalog;
import ru.jimmo.edt.sonarq.core.localanalysis.ProcessAnalyzeRunner;
import ru.jimmo.edt.sonarq.core.localanalysis.SarifParser;
import ru.jimmo.edt.sonarq.core.localanalysis.SarifReport;
import ru.jimmo.edt.sonarq.ui.Messages;
import ru.jimmo.edt.sonarq.ui.SonarqPlugin;
import ru.jimmo.edt.sonarq.ui.settings.PreferenceConstants;

/**
 * Workspace preference page for choosing which BSL Language Server diagnostics local analysis reports,
 * grouped by {@link DiagnosticCategory}.
 *
 * <p>The displayed set of diagnostics is the union of the bundled {@link DiagnosticCategories} catalog
 * (shipped with the plugin, always available) and any previously fetched catalog cached in the plugin
 * state directory by {@link DiagnosticsCatalog} - written automatically after every successful local
 * analysis run, or on demand by the <em>Fetch Checks List</em> button on this page, which runs the language
 * server against an empty temporary source directory (yielding zero issues but the full rule catalog).
 * Merging the two means this page works fully grouped even before the user has ever fetched or analyzed
 * anything (see {@link #mergeDisplayedKeys(DiagnosticCategories, List)}). A diagnostic is disabled when its
 * key is <em>unchecked</em> in the tree; the set of disabled keys is stored, comma-joined, in
 * {@link PreferenceConstants#PREF_DISABLED_BSL_DIAGNOSTICS} - unchanged from the previous flat-table
 * version of this page, so existing preference values remain valid.
 *
 * <p>The checked state of every row is derived from a single {@code disabledKeys} model set through an
 * {@link ICheckStateProvider}, rather than tracked as independent widget state: this keeps <em>Enable
 * All</em>/<em>Disable All</em>/<em>Apply Recommended Profile</em> and a catalog refresh trivially correct
 * regardless of the current text filter or tree expansion, since the filter only ever hides rows in the
 * underlying {@link Tree} widget and never touches the model. A category (parent) node reports itself
 * checked when at least one child is enabled, and grayed when its children are a mix of enabled and
 * disabled; toggling a category's checkbox enables or disables every diagnostic in that category.
 */
public class BslChecksPreferencePage extends PreferencePage implements IWorkbenchPreferencePage
{
    private static final String DIAGNOSTICS_DOCS_URL =
        "https://1c-syntax.github.io/bsl-language-server/diagnostics/"; //$NON-NLS-1$
    private static final String EMPTY_TEXT = ""; //$NON-NLS-1$
    private static final String LIST_SEPARATOR = ","; //$NON-NLS-1$
    private static final String TEMP_SRC_PREFIX = "sonarq-bsl-checks-src"; //$NON-NLS-1$
    private static final String TEMP_REPORT_PREFIX = "sonarq-bsl-checks-report"; //$NON-NLS-1$
    private static final String CATALOG_PROJECT_KEY = "bsl-checks-catalog"; //$NON-NLS-1$
    private static final String LINK_OPEN = "<a>"; //$NON-NLS-1$
    private static final String LINK_CLOSE = "</a>"; //$NON-NLS-1$

    private static final int KEY_COLUMN_WIDTH = 200;
    private static final int NAME_COLUMN_WIDTH = 320;

    private final Set<String> disabledKeys = new HashSet<>();

    private final DiagnosticCategories categories = DiagnosticCategories.load();

    private List<DiagnosticsCatalog.Entry> fetchedCatalog = List.of();

    private List<DiagKey> allDiagKeys = List.of();

    private List<CategoryNode> categoryNodes = List.of();

    private Text filterText;

    private Composite treeArea;

    private CheckboxTreeViewer treeViewer;

    private Label hintLabel;

    private Label counterLabel;

    private Label statusLabel;

    private Button enableAllButton;

    private Button disableAllButton;

    private Button applyProfileButton;

    private Button fetchButton;

    /**
     * One diagnostic leaf tree node: a displayed key with its resolved name, category and (for
     * {@link DiagnosticCategory#EDT_DUPLICATE}) the id of the EDT built-in check it duplicates.
     *
     * @param key the diagnostic (rule) key, not {@code null}
     * @param name the resolved human-readable name, not {@code null}
     * @param category the resolved category, not {@code null}
     * @param edtCheck the duplicated EDT check id, or {@code null} when {@code category} is not
     *     {@link DiagnosticCategory#EDT_DUPLICATE}
     */
    private record DiagKey(String key, String name, DiagnosticCategory category, String edtCheck)
    {
    }

    /**
     * A category (parent) tree node grouping its child {@link DiagKey} leaves.
     *
     * @param category the grouped category, not {@code null}
     * @param children the diagnostics in this category, not {@code null}, not empty
     */
    private record CategoryNode(DiagnosticCategory category, List<DiagKey> children)
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

        filterText = new Text(composite, SWT.SEARCH | SWT.ICON_SEARCH | SWT.BORDER);
        filterText.setMessage(Messages.BslChecksPage_Filter_Hint);
        filterText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        filterText.addModifyListener(event -> treeViewer.refresh());

        createTreeArea(composite);

        counterLabel = new Label(composite, SWT.NONE);
        counterLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

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
     * Builds the checkbox tree (columns Key/Name; category parents grouping diagnostic leaves, filtered by
     * {@link #filterText}) and the hint label shown instead when no diagnostic is displayed, stacked in
     * {@link #treeArea}.
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
                if (element instanceof CategoryNode categoryNode)
                {
                    return categoryTitle(categoryNode.category());
                }
                return ((DiagKey)element).key();
            }

            @Override
            public String getToolTipText(Object element)
            {
                return evidenceTooltip(element);
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
                if (element instanceof CategoryNode categoryNode)
                {
                    return groupCounterText(categoryNode);
                }
                return ((DiagKey)element).name();
            }

            @Override
            public String getToolTipText(Object element)
            {
                return evidenceTooltip(element);
            }
        });

        treeViewer.setContentProvider(new ITreeContentProvider()
        {
            @Override
            public Object[] getElements(Object inputElement)
            {
                return categoryNodes.toArray();
            }

            @Override
            public Object[] getChildren(Object parentElement)
            {
                if (parentElement instanceof CategoryNode categoryNode)
                {
                    return categoryNode.children().toArray();
                }
                return new Object[0];
            }

            @Override
            public Object getParent(Object element)
            {
                if (!(element instanceof DiagKey diagKey))
                {
                    return null;
                }
                return categoryNodes.stream().filter(node -> node.children().contains(diagKey)).findFirst()
                    .orElse(null);
            }

            @Override
            public boolean hasChildren(Object element)
            {
                return element instanceof CategoryNode categoryNode && !categoryNode.children().isEmpty();
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
                if (element instanceof CategoryNode categoryNode)
                {
                    return categoryNode.children().stream().anyMatch(child -> matchesFilter(child, query));
                }
                return matchesFilter((DiagKey)element, query);
            }
        });
        treeViewer.setCheckStateProvider(new ICheckStateProvider()
        {
            @Override
            public boolean isChecked(Object element)
            {
                if (element instanceof CategoryNode categoryNode)
                {
                    return categoryNode.children().stream().anyMatch(child -> !disabledKeys.contains(child.key()));
                }
                return !disabledKeys.contains(((DiagKey)element).key());
            }

            @Override
            public boolean isGrayed(Object element)
            {
                if (!(element instanceof CategoryNode categoryNode))
                {
                    return false;
                }
                boolean anyEnabled =
                    categoryNode.children().stream().anyMatch(child -> !disabledKeys.contains(child.key()));
                boolean anyDisabled =
                    categoryNode.children().stream().anyMatch(child -> disabledKeys.contains(child.key()));
                return anyEnabled && anyDisabled;
            }
        });
        treeViewer.addCheckStateListener(event ->
        {
            Object element = event.getElement();
            boolean checked = event.getChecked();
            if (element instanceof CategoryNode categoryNode)
            {
                for (DiagKey child : categoryNode.children())
                {
                    setEnabled(child.key(), checked);
                }
            }
            else
            {
                setEnabled(((DiagKey)element).key(), checked);
            }
            // A single native checkbox toggle only updates the clicked row's own widget; refreshing keeps
            // the parent category's derived checked/grayed indicator (and, for a group toggle, every
            // sibling leaf) in sync with the disabledKeys model.
            treeViewer.refresh();
            updateCounter();
        });

        hintLabel = new Label(treeArea, SWT.WRAP);
        hintLabel.setText(Messages.BslChecksPage_Empty);
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
     * The tooltip for an {@link DiagnosticCategory#EDT_DUPLICATE} leaf, naming the EDT check it duplicates.
     *
     * @param element the tree element, may be a {@link CategoryNode} or a {@link DiagKey}
     * @return the tooltip text, or {@code null} when {@code element} is not an EDT-duplicate leaf
     */
    private static String evidenceTooltip(Object element)
    {
        if (element instanceof DiagKey diagKey && diagKey.category() == DiagnosticCategory.EDT_DUPLICATE)
        {
            return NLS.bind(Messages.BslChecksPage_EvidenceTooltip, diagKey.edtCheck());
        }
        return null;
    }

    /**
     * The localized title of a category, shown in the Key column of its parent tree row.
     *
     * @param category the category, not {@code null}
     * @return the localized title, never {@code null}
     */
    private static String categoryTitle(DiagnosticCategory category)
    {
        switch (category)
        {
            case EDT_DUPLICATE:
                return Messages.BslChecksPage_Cat_EdtDuplicate;
            case NEEDS_TUNING:
                return Messages.BslChecksPage_Cat_NeedsTuning;
            case INAPPROPRIATE:
                return Messages.BslChecksPage_Cat_Inappropriate;
            default:
                return Messages.BslChecksPage_Cat_General;
        }
    }

    /**
     * The "disabled n of m" counter shown in the Name column of a category's parent tree row.
     *
     * @param categoryNode the category node, not {@code null}
     * @return the localized counter text, never {@code null}
     */
    private String groupCounterText(CategoryNode categoryNode)
    {
        long disabled = categoryNode.children().stream().filter(child -> disabledKeys.contains(child.key())).count();
        return NLS.bind(Messages.BslChecksPage_GroupCounter, disabled, categoryNode.children().size());
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
     * Merges the bundled category catalog with {@link #fetchedCatalog} into {@link #allDiagKeys}, groups
     * the result into {@link #categoryNodes} and reloads the tree.
     */
    private void rebuildDisplayedKeys()
    {
        allDiagKeys = mergeDisplayedKeys(categories, fetchedCatalog);
        categoryNodes = groupByCategory(allDiagKeys);
        reloadTree();
    }

    /**
     * Merges the bundled category catalog with a previously fetched diagnostics catalog into the flat,
     * key-sorted list of diagnostics to display.
     *
     * <p>The bundled catalog contributes every known key's category and (for
     * {@link DiagnosticCategory#EDT_DUPLICATE}) its EDT evidence; a diagnostic's name comes from the
     * bundled catalog when known, otherwise from the fetched catalog, falling back to the key itself. A key
     * present only in the fetched catalog is displayed under {@link DiagnosticCategory#GENERAL}, since the
     * bundled catalog has no classification for it.
     *
     * @param categories the bundled category catalog, not {@code null}
     * @param fetched the previously fetched diagnostics catalog, not {@code null}
     * @return the merged, key-sorted list of displayed diagnostics, never {@code null}
     */
    private static List<DiagKey> mergeDisplayedKeys(DiagnosticCategories categories,
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
            result.add(new DiagKey(key, name, categories.categoryOf(key), categories.edtCheckOf(key)));
        }
        return result;
    }

    /**
     * Groups a key-sorted diagnostic list into category tree nodes, in {@link DiagnosticCategory}
     * declaration order, omitting categories with no displayed diagnostic.
     *
     * @param diagKeys the diagnostics to group, not {@code null}
     * @return the non-empty category nodes, in category declaration order, never {@code null}
     */
    private static List<CategoryNode> groupByCategory(List<DiagKey> diagKeys)
    {
        Map<DiagnosticCategory, List<DiagKey>> byCategory = new EnumMap<>(DiagnosticCategory.class);
        for (DiagKey diagKey : diagKeys)
        {
            byCategory.computeIfAbsent(diagKey.category(), key -> new ArrayList<>()).add(diagKey);
        }
        List<CategoryNode> nodes = new ArrayList<>();
        for (DiagnosticCategory category : DiagnosticCategory.values())
        {
            List<DiagKey> children = byCategory.get(category);
            if (children != null && !children.isEmpty())
            {
                nodes.add(new CategoryNode(category, List.copyOf(children)));
            }
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
     * Re-populates the tree from {@link #categoryNodes}. Since the checked state is derived entirely from
     * {@link #disabledKeys} (a set of diagnostic keys, not tree rows), replacing the input this way
     * automatically preserves whichever disabled keys are still present in the new displayed set.
     */
    private void reloadTree()
    {
        treeViewer.setInput(categoryNodes);
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
     * set.
     */
    private void updateCounter()
    {
        counterLabel.setText(
            NLS.bind(Messages.BslChecksPage_Counter, disabledKeys.size(), allDiagKeys.size()));
    }

    /**
     * Clears the disabled set, checking every diagnostic regardless of the active text filter.
     */
    private void enableAll()
    {
        disabledKeys.clear();
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
            Path executable = BslServerInstaller.ensureServer(stateDir, TimeoutDownloads::open, monitor);
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
