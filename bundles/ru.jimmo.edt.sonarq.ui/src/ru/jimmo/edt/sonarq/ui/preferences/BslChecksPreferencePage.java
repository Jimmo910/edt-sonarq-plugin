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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ICheckStateProvider;
import org.eclipse.jface.viewers.TableViewerColumn;
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
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.osgi.service.prefs.BackingStoreException;

import ru.jimmo.edt.sonarq.core.analysis.TimeoutDownloads;
import ru.jimmo.edt.sonarq.core.localanalysis.BslServerInstaller;
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
 * <p>The catalog of known diagnostics (key/name pairs) is cached in the plugin state directory by
 * {@link DiagnosticsCatalog} - written automatically after every successful local analysis run, or on
 * demand by the <em>Fetch Checks List</em> button on this page, which runs the language server against an
 * empty temporary source directory (yielding zero issues but the full rule catalog) so the list can be
 * populated even before the user has analyzed a real project. A diagnostic is disabled when its key is
 * <em>unchecked</em> in the table; the set of disabled keys is stored, comma-joined, in
 * {@link PreferenceConstants#PREF_DISABLED_BSL_DIAGNOSTICS}.
 *
 * <p>The checked state of every row is derived from a single {@code disabledKeys} model set through an
 * {@link ICheckStateProvider}, rather than tracked as independent widget state: this keeps <em>Enable
 * All</em>/<em>Disable All</em> and a catalog refresh trivially correct regardless of the current text
 * filter, since the filter only ever hides rows in the underlying {@link Table} widget and never touches
 * the model.
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

    private List<DiagnosticsCatalog.Entry> entries = List.of();

    private Text filterText;

    private Composite tableArea;

    private CheckboxTableViewer tableViewer;

    private Label hintLabel;

    private Label counterLabel;

    private Label statusLabel;

    private Button enableAllButton;

    private Button disableAllButton;

    private Button fetchButton;

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
        filterText.addModifyListener(event -> tableViewer.refresh());

        createTableArea(composite);

        counterLabel = new Label(composite, SWT.NONE);
        counterLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        createButtonBar(composite);

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
     * Builds the checkbox table (columns Key/Name, filtered by {@link #filterText}) and the hint label
     * shown instead when the catalog is empty, stacked in {@link #tableArea}.
     *
     * @param parent the page composite, not {@code null}
     */
    private void createTableArea(Composite parent)
    {
        tableArea = new Composite(parent, SWT.NONE);
        tableArea.setLayout(new StackLayout());
        tableArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));

        tableViewer = CheckboxTableViewer.newCheckList(tableArea, SWT.BORDER | SWT.FULL_SELECTION);
        Table table = tableViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);

        TableViewerColumn keyColumn = new TableViewerColumn(tableViewer, SWT.NONE);
        keyColumn.getColumn().setText(Messages.BslChecksPage_Column_Key);
        keyColumn.getColumn().setWidth(KEY_COLUMN_WIDTH);
        keyColumn.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return ((DiagnosticsCatalog.Entry)element).key();
            }
        });

        TableViewerColumn nameColumn = new TableViewerColumn(tableViewer, SWT.NONE);
        nameColumn.getColumn().setText(Messages.BslChecksPage_Column_Name);
        nameColumn.getColumn().setWidth(NAME_COLUMN_WIDTH);
        nameColumn.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return ((DiagnosticsCatalog.Entry)element).name();
            }
        });

        tableViewer.setContentProvider(ArrayContentProvider.getInstance());
        tableViewer.addFilter(new ViewerFilter()
        {
            @Override
            public boolean select(Viewer viewer, Object parentElement, Object element)
            {
                String query = filterText.getText().trim().toLowerCase(Locale.ROOT);
                if (query.isEmpty())
                {
                    return true;
                }
                DiagnosticsCatalog.Entry entry = (DiagnosticsCatalog.Entry)element;
                return entry.key().toLowerCase(Locale.ROOT).contains(query)
                    || entry.name().toLowerCase(Locale.ROOT).contains(query);
            }
        });
        tableViewer.setCheckStateProvider(new ICheckStateProvider()
        {
            @Override
            public boolean isChecked(Object element)
            {
                return !disabledKeys.contains(((DiagnosticsCatalog.Entry)element).key());
            }

            @Override
            public boolean isGrayed(Object element)
            {
                return false;
            }
        });
        tableViewer.addCheckStateListener(event ->
        {
            String key = ((DiagnosticsCatalog.Entry)event.getElement()).key();
            if (event.getChecked())
            {
                disabledKeys.remove(key);
            }
            else
            {
                disabledKeys.add(key);
            }
            updateCounter();
        });

        hintLabel = new Label(tableArea, SWT.WRAP);
        hintLabel.setText(Messages.BslChecksPage_Empty);
    }

    /**
     * Builds the <em>Enable All</em>/<em>Disable All</em>/<em>Fetch Checks List</em> button row.
     *
     * @param parent the page composite, not {@code null}
     */
    private void createButtonBar(Composite parent)
    {
        Composite bar = new Composite(parent, SWT.NONE);
        bar.setLayout(new GridLayout(3, false));
        bar.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));

        enableAllButton = new Button(bar, SWT.PUSH);
        enableAllButton.setText(Messages.BslChecksPage_EnableAll);
        enableAllButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(event -> enableAll()));

        disableAllButton = new Button(bar, SWT.PUSH);
        disableAllButton.setText(Messages.BslChecksPage_DisableAll);
        disableAllButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(event -> disableAll()));

        fetchButton = new Button(bar, SWT.PUSH);
        fetchButton.setText(Messages.BslChecksPage_FetchCatalog);
        fetchButton.setToolTipText(Messages.BslChecksPage_FetchWarning);
        fetchButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(event -> fetchCatalog()));
    }

    /**
     * Loads the disabled-diagnostics preference into {@link #disabledKeys} and the cached catalog into the
     * table.
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
        entries = DiagnosticsCatalog.load(stateDir.resolve(DiagnosticsCatalog.CATALOG_FILE_NAME));
        reloadTable();
    }

    /**
     * Re-populates the table from {@link #entries}. Since the checked state is derived entirely from
     * {@link #disabledKeys} (a set of diagnostic keys, not table rows), replacing the catalog this way
     * automatically preserves whichever disabled keys are still present in the new catalog.
     */
    private void reloadTable()
    {
        tableViewer.setInput(entries);
        updateEmptyState();
        updateCounter();
    }

    /**
     * Shows the table when the catalog has entries, or {@link #hintLabel} when it is empty.
     */
    private void updateEmptyState()
    {
        StackLayout layout = (StackLayout)tableArea.getLayout();
        layout.topControl = entries.isEmpty() ? hintLabel : tableViewer.getTable();
        tableArea.layout();
    }

    /**
     * Refreshes the "Disabled N of M" counter label from the current catalog and disabled set.
     */
    private void updateCounter()
    {
        counterLabel.setText(
            NLS.bind(Messages.BslChecksPage_Counter, disabledKeys.size(), entries.size()));
    }

    /**
     * Clears the disabled set, checking every diagnostic regardless of the active text filter.
     */
    private void enableAll()
    {
        disabledKeys.clear();
        tableViewer.refresh();
        updateCounter();
    }

    /**
     * Disables every diagnostic in the catalog, regardless of the active text filter.
     */
    private void disableAll()
    {
        for (DiagnosticsCatalog.Entry entry : entries)
        {
            disabledKeys.add(entry.key());
        }
        tableViewer.refresh();
        updateCounter();
    }

    /**
     * Schedules the user-visible job that fetches the full diagnostics catalog: ensures the language
     * server is installed, runs it against an empty temporary source directory (yielding the full rule
     * catalog with zero issues), persists the catalog and reloads the table. Errors are reported on
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
     * table on the UI thread. Tolerates the page having been closed in the meantime (see
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
     * Applies a freshly fetched catalog to the table on the UI thread, guarded against the page having
     * been closed (widgets disposed) in the meantime.
     *
     * @param fetched the fetched catalog entries, not {@code null}
     */
    private void applyFetchedCatalog(List<DiagnosticsCatalog.Entry> fetched)
    {
        Display.getDefault().asyncExec(() ->
        {
            if (tableViewer.getTable().isDisposed())
            {
                return;
            }
            entries = fetched;
            reloadTable();
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
        tableViewer.refresh();
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
