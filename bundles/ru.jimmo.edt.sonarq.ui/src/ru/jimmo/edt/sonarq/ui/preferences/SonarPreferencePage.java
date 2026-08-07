/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.preferences;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IPreferencesService;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.FocusListener;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.osgi.service.prefs.BackingStoreException;

import ru.jimmo.edt.sonarq.core.analysis.AnalysisLaunchMode;
import ru.jimmo.edt.sonarq.core.analysis.ProcessOutput;
import ru.jimmo.edt.sonarq.core.client.SonarConnection;
import ru.jimmo.edt.sonarq.core.client.SonarHttpClient;
import ru.jimmo.edt.sonarq.core.client.SonarServerException;
import ru.jimmo.edt.sonarq.core.localanalysis.BslServerInstaller;
import ru.jimmo.edt.sonarq.ui.Messages;
import ru.jimmo.edt.sonarq.ui.SonarqPlugin;
import ru.jimmo.edt.sonarq.ui.SonarqStartup;
import ru.jimmo.edt.sonarq.ui.settings.PreferenceConstants;
import ru.jimmo.edt.sonarq.ui.settings.SecureTokenStore;
import ru.jimmo.edt.sonarq.ui.sync.AutoSyncScheduler;

/** Workspace-level SonarQube connection preferences. */
public class SonarPreferencePage extends PreferencePage implements IWorkbenchPreferencePage
{
    // The mode combo item order is coupled to these indices (see #createContents, #loadValues, #performOk).
    private static final int MODE_INDEX_SERVER = 0;

    private static final int MODE_INDEX_LOCAL = 1;

    // The BSL source combo item order is coupled to these indices (see #createLocalGroup, #loadValues,
    // #performOk): blank stored path means "download automatically".
    private static final int BSL_SOURCE_INDEX_DOWNLOAD = 0;

    private static final int BSL_SOURCE_INDEX_LOCAL = 1;

    // The update-channel combo item order matches BslUpdateChannel's declaration order (FIXED, STABLE,
    // PRERELEASE): the selection index is used directly as the enum ordinal (see #loadValues, #performOk),
    // and this array maps that same index back to the stored preference value.
    private static final String[] UPDATE_CHANNEL_VALUES = { PreferenceConstants.UPDATE_CHANNEL_FIXED,
        PreferenceConstants.UPDATE_CHANNEL_STABLE, PreferenceConstants.UPDATE_CHANNEL_PRERELEASE };

    /** How long {@link #probeBslExecutable} waits for the {@code --version} run to finish. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);

    private Combo modeCombo;

    private Text urlText;

    private Text tokenText;

    private Spinner timeoutSpinner;

    private Button testButton;

    private Label testResultLabel;

    private Combo launchModeCombo;

    private Combo bslSourceCombo;

    private Text bslLsPathText;

    private Button bslBrowseButton;

    private Button bslVerifyButton;

    private Label bslVerifyResultLabel;

    private Spinner bslMaxHeapSpinner;

    private Combo bslUpdateChannelCombo;

    private Label engineStatusLabel;

    private Button deleteEngineButton;

    private Text scannerPathText;

    private Text ciUrlText;

    private Text ciSecretText;

    private Text extraArgsText;

    private Button showMarkersButton;

    private Button autoSyncButton;

    private Spinner autoSyncMinutesSpinner;

    /** The server URL {@link #tokenText}'s current content was loaded for; see {@link #shouldReloadSecret}. */
    private String tokenLoadedForUrl = ""; //$NON-NLS-1$

    /** Whether the user typed in {@link #tokenText} since it was last filled from the secure store. */
    private boolean tokenEdited;

    /** Guards {@link #tokenEdited} against this page's own {@code setText} calls. */
    private boolean tokenUpdating;

    /** The CI URL {@link #ciSecretText}'s current content was loaded for; see {@link #shouldReloadSecret}. */
    private String ciSecretLoadedForUrl = ""; //$NON-NLS-1$

    /** Whether the user typed in {@link #ciSecretText} since it was last filled from the secure store. */
    private boolean ciSecretEdited;

    /** Guards {@link #ciSecretEdited} against this page's own {@code setText} calls. */
    private boolean ciSecretUpdating;

    @Override
    public void init(IWorkbench workbench)
    {
        setDescription(Messages.PreferencePage_Description);
        noDefaultAndApplyButton();
    }

    @Override
    protected Control createContents(Composite parent)
    {
        Composite composite = new Composite(parent, SWT.NONE);
        composite.setLayout(new GridLayout(2, false));

        new Label(composite, SWT.NONE).setText(Messages.PreferencePage_Mode);
        modeCombo = new Combo(composite, SWT.READ_ONLY);
        // Item order is coupled to MODE_INDEX_SERVER / MODE_INDEX_LOCAL (see the field declarations).
        modeCombo.setItems(Messages.PreferencePage_Mode_Server, Messages.PreferencePage_Mode_Local);
        modeCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        modeCombo.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> updateModeEnablement()));

        new Label(composite, SWT.NONE).setText(Messages.PreferencePage_ServerUrl);
        urlText = new Text(composite, SWT.BORDER);
        urlText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        // Reload the token stored for whichever server the URL now names, so an old server's token is never
        // left in the field to be sent to (or saved for) a different server after the URL is changed - but
        // never over a token the user typed (see #shouldReloadSecret).
        urlText.addFocusListener(FocusListener.focusLostAdapter(e -> reloadTokenForCurrentUrl()));

        new Label(composite, SWT.NONE).setText(Messages.PreferencePage_Token);
        tokenText = new Text(composite, SWT.BORDER | SWT.PASSWORD);
        tokenText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        tokenText.addModifyListener(e ->
        {
            if (!tokenUpdating)
            {
                tokenEdited = true;
            }
        });

        new Label(composite, SWT.NONE).setText(Messages.PreferencePage_TimeoutSeconds);
        timeoutSpinner = new Spinner(composite, SWT.BORDER);
        timeoutSpinner.setValues(PreferenceConstants.DEFAULT_TIMEOUT_SECONDS, 5, 300, 0, 5, 30);

        testButton = new Button(composite, SWT.PUSH);
        testButton.setText(Messages.PreferencePage_TestConnection);
        testButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> testConnection()));
        testResultLabel = new Label(composite, SWT.WRAP);
        testResultLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        createLaunchGroup(composite);
        createLocalGroup(composite);
        createMarkersGroup(composite);

        loadValues();
        return composite;
    }

    private void createLaunchGroup(Composite parent)
    {
        Group group = new Group(parent, SWT.NONE);
        group.setText(Messages.PreferencePage_LaunchGroup);
        group.setLayout(new GridLayout(2, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        new Label(group, SWT.NONE).setText(Messages.PreferencePage_LaunchMode);
        launchModeCombo = new Combo(group, SWT.READ_ONLY);
        // Item order must match AnalysisLaunchMode's declaration order: the selection index is used
        // directly as the enum ordinal (see #updateLaunchEnablement and #performOk).
        launchModeCombo.setItems(Messages.PreferencePage_Mode_LocalAuto, Messages.PreferencePage_Mode_LocalPath,
            Messages.PreferencePage_Mode_CiTrigger);
        launchModeCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        launchModeCombo.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> updateLaunchEnablement()));

        new Label(group, SWT.NONE).setText(Messages.PreferencePage_ScannerPath);
        scannerPathText = new Text(group, SWT.BORDER);
        scannerPathText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(group, SWT.NONE).setText(Messages.PreferencePage_CiUrl);
        ciUrlText = new Text(group, SWT.BORDER);
        ciUrlText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        ciUrlText.addFocusListener(FocusListener.focusLostAdapter(e -> reloadCiSecretForCurrentUrl()));

        new Label(group, SWT.NONE).setText(Messages.PreferencePage_CiSecret);
        ciSecretText = new Text(group, SWT.BORDER | SWT.PASSWORD);
        ciSecretText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        ciSecretText.addModifyListener(e ->
        {
            if (!ciSecretUpdating)
            {
                ciSecretEdited = true;
            }
        });

        new Label(group, SWT.NONE).setText(Messages.PreferencePage_ExtraArgs);
        extraArgsText = new Text(group, SWT.BORDER);
        extraArgsText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    /**
     * Enables the launch-mode-specific fields, matching the selected {@link AnalysisLaunchMode}.
     */
    private void updateLaunchEnablement()
    {
        int index = launchModeCombo.getSelectionIndex();
        AnalysisLaunchMode mode = index >= 0 ? AnalysisLaunchMode.values()[index] : AnalysisLaunchMode.LOCAL_AUTO;
        scannerPathText.setEnabled(mode == AnalysisLaunchMode.LOCAL_PATH);
        ciUrlText.setEnabled(mode == AnalysisLaunchMode.CI_TRIGGER);
        ciSecretText.setEnabled(mode == AnalysisLaunchMode.CI_TRIGGER);
        extraArgsText.setEnabled(mode == AnalysisLaunchMode.LOCAL_AUTO || mode == AnalysisLaunchMode.LOCAL_PATH);
    }

    private void createLocalGroup(Composite parent)
    {
        Group group = new Group(parent, SWT.NONE);
        group.setText(Messages.PreferencePage_LocalGroup);
        group.setLayout(new GridLayout(4, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        new Label(group, SWT.NONE).setText(Messages.PreferencePage_BslSource);
        bslSourceCombo = new Combo(group, SWT.READ_ONLY);
        // Item order is coupled to BSL_SOURCE_INDEX_DOWNLOAD / BSL_SOURCE_INDEX_LOCAL (field declarations).
        bslSourceCombo.setItems(Messages.PreferencePage_BslSource_Download, Messages.PreferencePage_BslSource_Local);
        bslSourceCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        bslSourceCombo.addSelectionListener(
            SelectionListener.widgetSelectedAdapter(e -> updateBslSourceEnablement()));

        new Label(group, SWT.NONE).setText(Messages.PreferencePage_BslLsPath);
        bslLsPathText = new Text(group, SWT.BORDER);
        bslLsPathText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        bslLsPathText.addModifyListener(e -> validateBslPath());

        bslBrowseButton = new Button(group, SWT.PUSH);
        bslBrowseButton.setText(Messages.PreferencePage_Browse);
        bslBrowseButton.addSelectionListener(
            SelectionListener.widgetSelectedAdapter(e -> browseBslExecutable()));

        bslVerifyButton = new Button(group, SWT.PUSH);
        bslVerifyButton.setText(Messages.PreferencePage_BslVerify);
        bslVerifyButton.addSelectionListener(
            SelectionListener.widgetSelectedAdapter(e -> verifyBslExecutable()));

        bslVerifyResultLabel = new Label(group, SWT.WRAP);
        bslVerifyResultLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 4, 1));

        new Label(group, SWT.NONE).setText(Messages.PreferencePage_BslLsMaxHeap);
        bslMaxHeapSpinner = new Spinner(group, SWT.BORDER);
        bslMaxHeapSpinner.setValues(PreferenceConstants.DEFAULT_BSL_LS_MAX_HEAP_GB, 1, 64, 0, 1, 4);
        bslMaxHeapSpinner.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        Label maxHeapHint = new Label(group, SWT.WRAP);
        maxHeapHint.setText(Messages.PreferencePage_BslLsMaxHeapHint);
        maxHeapHint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        new Label(group, SWT.NONE).setText(Messages.SonarPrefs_BslLsUpdateChannel_Label);
        bslUpdateChannelCombo = new Combo(group, SWT.READ_ONLY);
        // Item order is coupled to BslUpdateChannel's declaration order (see the UPDATE_CHANNEL_VALUES field).
        bslUpdateChannelCombo.setItems(Messages.SonarPrefs_BslLsUpdateChannel_Fixed,
            Messages.SonarPrefs_BslLsUpdateChannel_Stable, Messages.SonarPrefs_BslLsUpdateChannel_Prerelease);
        bslUpdateChannelCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        engineStatusLabel = new Label(group, SWT.NONE);
        engineStatusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        deleteEngineButton = new Button(group, SWT.PUSH);
        deleteEngineButton.setText(Messages.PreferencePage_DeleteEngine);
        deleteEngineButton.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));
        deleteEngineButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> deleteEngine()));
    }

    /**
     * Enables the BSL source widgets: the combo follows the page mode, the path row (text, browse and
     * verify buttons) is active only when the user chose a local executable over the automatic download,
     * and the max-heap spinner and update-channel combo only while local mode is selected AND the managed
     * download is in effect (see {@link #heapSpinnerEnabled}) - {@link BslServerInstaller#configureHeap}
     * rewrites only that downloaded engine's own launcher configuration file, and the update channel only
     * governs which release the same managed download resolves, so neither has any effect at all on a
     * user-supplied executable and must not invite the user to believe otherwise.
     */
    private void updateBslSourceEnablement()
    {
        boolean local = modeCombo.getSelectionIndex() == MODE_INDEX_LOCAL;
        boolean ownExecutable = bslSourceCombo.getSelectionIndex() == BSL_SOURCE_INDEX_LOCAL;
        bslSourceCombo.setEnabled(local);
        bslLsPathText.setEnabled(local && ownExecutable);
        bslBrowseButton.setEnabled(local && ownExecutable);
        bslVerifyButton.setEnabled(local && ownExecutable);
        bslVerifyResultLabel.setEnabled(local && ownExecutable);
        bslMaxHeapSpinner.setEnabled(heapSpinnerEnabled(local, ownExecutable));
        bslUpdateChannelCombo.setEnabled(heapSpinnerEnabled(local, ownExecutable));
        engineStatusLabel.setEnabled(local);
        deleteEngineButton.setEnabled(local);
        validateBslPath();
    }

    /**
     * Decides whether the max-heap spinner should be enabled: only in local mode, and only while the BSL
     * source is the managed automatic download rather than a user-supplied executable. {@link
     * BslServerInstaller#configureHeap} rewrites the pinned heap limit of the downloaded engine's own
     * launcher configuration file under the plugin state directory; it never touches a user-supplied
     * executable, so the spinner is meaningless (and must stay disabled) whenever one is selected (review
     * minor, issue #4/#5).
     *
     * @param localMode {@code true} when the page mode combo selects local analysis
     * @param ownExecutable {@code true} when the BSL source combo selects a user-supplied executable
     * @return {@code true} when the max-heap spinner should be enabled
     */
    static boolean heapSpinnerEnabled(boolean localMode, boolean ownExecutable)
    {
        return localMode && !ownExecutable;
    }

    /**
     * Decides whether a secret field (the server token, the CI secret) may be refilled from the secure store
     * for the URL it is paired with.
     *
     * <p>Refilling is what keeps a previous server's token from being sent to - or saved for - a different
     * server once its URL is edited. It must, however, never throw away a secret the user typed: the URL
     * field's focus-lost event fires <em>before</em> the OK button's own handler, so the sequence "type the
     * token, click into the URL field, press OK" used to overwrite the freshly typed token (usually with the
     * empty string stored for that URL) and save that instead, silently. Hence both conditions: the URL has
     * to have actually changed since the field was filled, and the field must not have been edited by hand
     * since then (review minor M1).
     *
     * <p>Pure and SWT-free by design, so it can be unit-tested without a display.
     *
     * @param loadedForUrl the URL the field's current content was loaded for, not {@code null}
     * @param currentUrl the URL now typed in the paired URL field, not {@code null}
     * @param fieldEdited {@code true} when the user typed in the secret field since it was last loaded
     * @return {@code true} when the stored secret for {@code currentUrl} may replace the field's content
     */
    static boolean shouldReloadSecret(String loadedForUrl, String currentUrl, boolean fieldEdited)
    {
        return !fieldEdited && !loadedForUrl.equals(currentUrl);
    }

    /**
     * Refills the token field from the secure store for the URL currently typed, when that is safe.
     */
    private void reloadTokenForCurrentUrl()
    {
        String url = urlText.getText().trim();
        if (shouldReloadSecret(tokenLoadedForUrl, url, tokenEdited))
        {
            setTokenText(new SecureTokenStore().loadToken(url), url);
        }
    }

    /**
     * Refills the CI secret field from the secure store for the CI URL currently typed, when that is safe.
     */
    private void reloadCiSecretForCurrentUrl()
    {
        String url = ciUrlText.getText().trim();
        if (shouldReloadSecret(ciSecretLoadedForUrl, url, ciSecretEdited))
        {
            setCiSecretText(new SecureTokenStore().loadCiSecret(url), url);
        }
    }

    /**
     * Fills the token field programmatically, recording which URL the value belongs to and clearing the
     * "edited by the user" flag that {@link #shouldReloadSecret} consults.
     *
     * @param token the token to show, not {@code null}
     * @param url the trimmed server URL the token was loaded for, not {@code null}
     */
    private void setTokenText(String token, String url)
    {
        tokenUpdating = true;
        try
        {
            tokenText.setText(token);
        }
        finally
        {
            tokenUpdating = false;
        }
        tokenLoadedForUrl = url;
        tokenEdited = false;
    }

    /**
     * Fills the CI secret field programmatically; the counterpart of {@link #setTokenText}.
     *
     * @param secret the secret to show, not {@code null}
     * @param url the trimmed CI trigger URL the secret was loaded for, not {@code null}
     */
    private void setCiSecretText(String secret, String url)
    {
        ciSecretUpdating = true;
        try
        {
            ciSecretText.setText(secret);
        }
        finally
        {
            ciSecretUpdating = false;
        }
        ciSecretLoadedForUrl = url;
        ciSecretEdited = false;
    }

    /**
     * Blocks the page while local mode expects a user-supplied executable that does not point to a file.
     */
    private void validateBslPath()
    {
        boolean local = modeCombo.getSelectionIndex() == MODE_INDEX_LOCAL;
        boolean ownExecutable = bslSourceCombo.getSelectionIndex() == BSL_SOURCE_INDEX_LOCAL;
        boolean invalid = local && ownExecutable && !isExistingFile(bslLsPathText.getText().trim());
        setErrorMessage(invalid ? Messages.PreferencePage_BslPathRequired : null);
        setValid(!invalid);
    }

    private static boolean isExistingFile(String path)
    {
        if (path.isEmpty())
        {
            return false;
        }
        try
        {
            return Files.isRegularFile(Path.of(path));
        }
        catch (InvalidPathException e)
        {
            return false;
        }
    }

    private void browseBslExecutable()
    {
        FileDialog dialog = new FileDialog(getShell(), SWT.OPEN);
        dialog.setFilterExtensions(new String[] { "*.exe;*.bat", "*.*" }); //$NON-NLS-1$ //$NON-NLS-2$
        dialog.setFilterNames(
            new String[] { Messages.PreferencePage_BslFilter_Executables, Messages.PreferencePage_BslFilter_All });
        String selected = dialog.open();
        if (selected != null)
        {
            bslLsPathText.setText(selected);
        }
    }

    private void verifyBslExecutable()
    {
        String path = bslLsPathText.getText().trim();
        bslVerifyResultLabel.setText(Messages.RulePanel_Loading);
        Job job = Job.create(Messages.PreferencePage_BslVerify, monitor ->
        {
            String message = probeBslExecutable(path);
            Display.getDefault().asyncExec(() ->
            {
                if (!bslVerifyResultLabel.isDisposed())
                {
                    bslVerifyResultLabel.setText(message);
                    bslVerifyResultLabel.getParent().layout();
                }
            });
        });
        job.setSystem(true);
        job.schedule();
    }

    /**
     * Runs the given file with {@code --version} and reports whether the output looks like BSL Language
     * Server.
     *
     * <p>The wait is bounded, and the output is drained <em>while</em> the process runs (see
     * {@link ProcessOutput}): reading it only after the exit deadlocked against any executable printing more
     * than one OS pipe buffer on {@code --version} - a JVM launcher with a verbose {@code _JAVA_OPTIONS} or
     * GC logging does exactly that - and the deadlock was then reported to the user as a timeout (review
     * minor M6).
     *
     * @param path the executable path chosen by the user, not {@code null}
     * @return the localized result message, never {@code null}
     */
    private static String probeBslExecutable(String path)
    {
        try
        {
            Process process = new ProcessBuilder(path, "--version").redirectErrorStream(true).start(); //$NON-NLS-1$
            Optional<String> captured =
                ProcessOutput.awaitMergedOutput(process, PROBE_TIMEOUT, Charset.defaultCharset());
            if (captured.isEmpty())
            {
                return NLS.bind(Messages.PreferencePage_BslVerifyFail, "timeout"); //$NON-NLS-1$
            }
            String output = captured.get();
            // The native launcher prints JVM warnings first; prefer the "version: ..." line for display.
            String display = output.lines().map(String::trim)
                .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("version")) //$NON-NLS-1$
                .findFirst()
                .orElseGet(() -> output.lines().map(String::trim)
                    .filter(line -> !line.isEmpty()).findFirst().orElse("")); //$NON-NLS-1$
            if (output.toLowerCase(Locale.ROOT).contains("bsl")) //$NON-NLS-1$
            {
                return NLS.bind(Messages.PreferencePage_BslVerifyOk, display);
            }
            return NLS.bind(Messages.PreferencePage_BslVerifyFail, display);
        }
        catch (IOException e)
        {
            return NLS.bind(Messages.PreferencePage_BslVerifyFail, e.getMessage());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return NLS.bind(Messages.PreferencePage_BslVerifyFail, e.getMessage());
        }
    }

    /**
     * Refreshes {@link #engineStatusLabel} to reflect whether the managed BSL Language Server distribution
     * is currently installed under the plugin state directory (issue #4 point 1), showing the installed
     * version when it is known. Cheap: only scans the version directories under the state directory (see
     * {@link BslServerInstaller#installedVersion}), never touches the network - and scans them exactly once,
     * since "installed" is precisely "a version was found" ({@code isInstalled} is that same scan).
     */
    private void refreshEngineStatus()
    {
        Path stateDir = Path.of(SonarqPlugin.getInstance().getStateLocation().toOSString());
        Optional<String> version = BslServerInstaller.installedVersion(stateDir);
        engineStatusLabel.setText(version.isPresent()
            ? NLS.bind(Messages.SonarPrefs_BslEngine_InstalledVersion, version.get())
            : Messages.PreferencePage_EngineNotInstalled);
    }

    /**
     * Deletes the downloaded BSL Language Server distribution after an explicit user confirmation, so a user
     * who no longer needs local analysis can reclaim the ~170 MB it occupies (issue #4 point 1). A failure
     * to delete is reported in an error dialog rather than propagated, since this runs directly from a button
     * click on the UI thread and must never crash the preferences page. Either way, {@link #engineStatusLabel}
     * is refreshed afterwards so the page always reflects the actual on-disk state.
     *
     * <p>{@link BslServerInstaller#deleteServer} walks the distribution tree with {@link
     * java.nio.file.Files#walk}, which throws the unchecked {@link UncheckedIOException} instead of
     * {@link IOException} when the tree mutates mid-walk (for example a delete racing an install); that is
     * caught alongside {@link IOException} so such a race is still reported in the same error dialog rather
     * than escaping and crashing the page (review minor, issue #4/#5).
     */
    private void deleteEngine()
    {
        boolean confirmed = MessageDialog.openConfirm(getShell(), Messages.PreferencePage_DeleteEngineTitle,
            Messages.PreferencePage_DeleteEngineConfirm);
        if (confirmed)
        {
            Path stateDir = Path.of(SonarqPlugin.getInstance().getStateLocation().toOSString());
            try
            {
                BslServerInstaller.deleteServer(stateDir);
            }
            catch (IOException | UncheckedIOException e)
            {
                MessageDialog.openError(getShell(), Messages.PreferencePage_DeleteEngineTitle, e.getMessage());
            }
        }
        refreshEngineStatus();
    }

    /**
     * Enables the widgets that belong to the selected {@link PreferenceConstants#PREF_MODE}: the connection
     * fields and the analysis-launch group in server mode, the BSL Language Server path in local mode. The
     * <em>Show issues in editor</em> checkbox stays enabled in either mode, but background auto-sync (its
     * checkbox and interval spinner) is disabled in local mode because the background timer never runs a
     * local analysis; in server mode they are re-enabled, honouring the auto-sync checkbox for the spinner.
     */
    private void updateModeEnablement()
    {
        boolean server = modeCombo.getSelectionIndex() != MODE_INDEX_LOCAL;
        urlText.setEnabled(server);
        tokenText.setEnabled(server);
        timeoutSpinner.setEnabled(server);
        testButton.setEnabled(server);
        testResultLabel.setEnabled(server);
        launchModeCombo.setEnabled(server);
        if (server)
        {
            updateLaunchEnablement();
        }
        else
        {
            scannerPathText.setEnabled(false);
            ciUrlText.setEnabled(false);
            ciSecretText.setEnabled(false);
            extraArgsText.setEnabled(false);
        }
        updateBslSourceEnablement();
        autoSyncButton.setEnabled(server);
        if (server)
        {
            updateAutoSyncEnablement();
        }
        else
        {
            autoSyncMinutesSpinner.setEnabled(false);
        }
    }

    private void createMarkersGroup(Composite parent)
    {
        Group group = new Group(parent, SWT.NONE);
        group.setText(Messages.PreferencePage_MarkersGroup);
        group.setLayout(new GridLayout(2, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        showMarkersButton = new Button(group, SWT.CHECK);
        showMarkersButton.setText(Messages.PreferencePage_ShowMarkers);
        showMarkersButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        Label showMarkersHint = new Label(group, SWT.WRAP);
        showMarkersHint.setText(Messages.PreferencePage_ShowMarkersHint);
        showMarkersHint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        autoSyncButton = new Button(group, SWT.CHECK);
        autoSyncButton.setText(Messages.PreferencePage_AutoSync);
        autoSyncButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        autoSyncButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> updateAutoSyncEnablement()));

        new Label(group, SWT.NONE).setText(Messages.PreferencePage_AutoSyncMinutes);
        autoSyncMinutesSpinner = new Spinner(group, SWT.BORDER);
        autoSyncMinutesSpinner.setValues(PreferenceConstants.DEFAULT_AUTO_SYNC_MINUTES, 1, 1440, 0, 1, 10);
        autoSyncMinutesSpinner.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    /**
     * Enables the auto-sync interval spinner only while background auto-sync is checked.
     */
    private void updateAutoSyncEnablement()
    {
        autoSyncMinutesSpinner.setEnabled(autoSyncButton.getSelection());
    }

    private void loadValues()
    {
        IPreferencesService service = Platform.getPreferencesService();
        String mode = service.getString(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_MODE,
            PreferenceConstants.MODE_SERVER, null);
        modeCombo.select(
            PreferenceConstants.MODE_LOCAL.equals(mode) ? MODE_INDEX_LOCAL : MODE_INDEX_SERVER);
        String bslPath = service.getString(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_BSL_LS_PATH,
            "", null); //$NON-NLS-1$
        bslLsPathText.setText(bslPath);
        // Blank stored path means automatic download (see BSL_SOURCE_INDEX_DOWNLOAD).
        bslSourceCombo.select(bslPath.isBlank() ? BSL_SOURCE_INDEX_DOWNLOAD : BSL_SOURCE_INDEX_LOCAL);
        bslMaxHeapSpinner.setSelection(service.getInt(SonarqPlugin.PLUGIN_ID,
            PreferenceConstants.PREF_BSL_LS_MAX_HEAP_GB, PreferenceConstants.DEFAULT_BSL_LS_MAX_HEAP_GB, null));
        String updateChannel = service.getString(SonarqPlugin.PLUGIN_ID,
            PreferenceConstants.PREF_BSL_LS_UPDATE_CHANNEL, PreferenceConstants.DEFAULT_BSL_LS_UPDATE_CHANNEL, null);
        bslUpdateChannelCombo.select(PreferenceConstants.channelFromPreference(updateChannel).ordinal());
        refreshEngineStatus();
        String serverUrl =
            service.getString(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_SERVER_URL, "", null); //$NON-NLS-1$
        urlText.setText(serverUrl);
        timeoutSpinner.setSelection(service.getInt(SonarqPlugin.PLUGIN_ID,
            PreferenceConstants.PREF_TIMEOUT_SECONDS, PreferenceConstants.DEFAULT_TIMEOUT_SECONDS, null));
        SecureTokenStore tokenStore = new SecureTokenStore();
        setTokenText(tokenStore.loadToken(serverUrl.trim()), serverUrl.trim());

        AnalysisLaunchMode launchMode = AnalysisLaunchMode.fromKey(service.getString(SonarqPlugin.PLUGIN_ID,
            PreferenceConstants.PREF_LAUNCH_MODE, AnalysisLaunchMode.LOCAL_AUTO.name(), null));
        launchModeCombo.select(launchMode.ordinal());
        scannerPathText.setText(
            service.getString(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_SCANNER_PATH, "", null)); //$NON-NLS-1$
        String ciUrl =
            service.getString(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_CI_URL, "", null); //$NON-NLS-1$
        ciUrlText.setText(ciUrl);
        setCiSecretText(tokenStore.loadCiSecret(ciUrl.trim()), ciUrl.trim());
        extraArgsText.setText(
            service.getString(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_EXTRA_ARGS, "", null)); //$NON-NLS-1$
        updateModeEnablement();

        showMarkersButton.setSelection(
            service.getBoolean(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_SHOW_MARKERS, true, null));
        autoSyncButton.setSelection(
            service.getBoolean(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_AUTO_SYNC, false, null));
        autoSyncMinutesSpinner.setSelection(service.getInt(SonarqPlugin.PLUGIN_ID,
            PreferenceConstants.PREF_AUTO_SYNC_MINUTES, PreferenceConstants.DEFAULT_AUTO_SYNC_MINUTES, null));
        updateAutoSyncEnablement();
    }

    private void testConnection()
    {
        SonarConnection connection =
            SonarConnection.of(urlText.getText().trim(), tokenText.getText().trim(), timeoutSpinner.getSelection());
        testResultLabel.setText(Messages.RulePanel_Loading);
        Job job = Job.create(Messages.PreferencePage_TestConnection, monitor ->
        {
            String message;
            // A one-shot probe of settings the user has not saved yet, so it gets a client of its own
            // (rather than the shared one, which would be keyed to a half-typed URL) and closes it here
            // instead of leaving its selector thread to the garbage collector.
            try (SonarHttpClient client = new SonarHttpClient(connection))
            {
                message = NLS.bind(Messages.PreferencePage_TestSuccess, client.serverVersion());
            }
            catch (SonarServerException e)
            {
                message = NLS.bind(Messages.PreferencePage_TestFailure, e.getMessage());
            }
            String text = message;
            Display.getDefault().asyncExec(() ->
            {
                if (!testResultLabel.isDisposed())
                {
                    testResultLabel.setText(text);
                    testResultLabel.getParent().layout();
                }
            });
        });
        job.setSystem(true);
        job.schedule();
    }

    @Override
    public boolean performOk()
    {
        IEclipsePreferences node = InstanceScope.INSTANCE.getNode(SonarqPlugin.PLUGIN_ID);
        // Selection index is coupled to MODE_INDEX_SERVER / MODE_INDEX_LOCAL (see the field declarations).
        node.put(PreferenceConstants.PREF_MODE, modeCombo.getSelectionIndex() == MODE_INDEX_LOCAL
            ? PreferenceConstants.MODE_LOCAL : PreferenceConstants.MODE_SERVER);
        // Download source stores a blank path (see BSL_SOURCE_INDEX_DOWNLOAD coupling).
        boolean ownExecutable = bslSourceCombo.getSelectionIndex() == BSL_SOURCE_INDEX_LOCAL;
        node.put(PreferenceConstants.PREF_BSL_LS_PATH,
            ownExecutable ? bslLsPathText.getText().trim() : ""); //$NON-NLS-1$
        node.putInt(PreferenceConstants.PREF_BSL_LS_MAX_HEAP_GB, bslMaxHeapSpinner.getSelection());
        node.put(PreferenceConstants.PREF_BSL_LS_UPDATE_CHANNEL,
            UPDATE_CHANNEL_VALUES[bslUpdateChannelCombo.getSelectionIndex()]);
        String serverUrl = urlText.getText().trim();
        String ciUrl = ciUrlText.getText().trim();
        node.put(PreferenceConstants.PREF_SERVER_URL, serverUrl);
        node.putInt(PreferenceConstants.PREF_TIMEOUT_SECONDS, timeoutSpinner.getSelection());
        // Selection index is coupled to AnalysisLaunchMode's declaration order (see #createLaunchGroup).
        AnalysisLaunchMode mode = AnalysisLaunchMode.values()[launchModeCombo.getSelectionIndex()];
        node.put(PreferenceConstants.PREF_LAUNCH_MODE, mode.name());
        node.put(PreferenceConstants.PREF_SCANNER_PATH, scannerPathText.getText().trim());
        node.put(PreferenceConstants.PREF_CI_URL, ciUrl);
        node.put(PreferenceConstants.PREF_EXTRA_ARGS, extraArgsText.getText().trim());
        node.putBoolean(PreferenceConstants.PREF_SHOW_MARKERS, showMarkersButton.getSelection());
        node.putBoolean(PreferenceConstants.PREF_AUTO_SYNC, autoSyncButton.getSelection());
        node.putInt(PreferenceConstants.PREF_AUTO_SYNC_MINUTES, autoSyncMinutesSpinner.getSelection());
        try
        {
            node.flush();
            applyAutoSyncIfUnwatched();
            SecureTokenStore tokenStore = new SecureTokenStore();
            tokenStore.saveToken(serverUrl, tokenText.getText().trim());
            tokenStore.saveCiSecret(ciUrl, ciSecretText.getText().trim());
        }
        catch (BackingStoreException | StorageException | IOException e)
        {
            SonarqPlugin.getInstance().getLog().error(e.getMessage(), e);
            return false;
        }
        return true;
    }

    /**
     * Applies the just-saved auto-sync preferences to the background scheduler when nothing else will.
     *
     * <p>The scheduler is normally armed by {@link SonarqStartup#earlyStartup()} and re-armed by the
     * preference listener it registers. A user who turns this plug-in's early start-up off (Preferences &gt;
     * General &gt; Startup and Shutdown) gets neither, and the auto-sync check box on this page would then do
     * nothing at all until EDT is restarted. Applying it here closes that hole; the
     * {@link SonarqStartup#isWatchingPreferences()} guard keeps the normal case a single arming, since the
     * listener has already reacted to the {@code node.put} calls above.
     */
    private static void applyAutoSyncIfUnwatched()
    {
        if (!SonarqStartup.isWatchingPreferences())
        {
            AutoSyncScheduler.applyPreferences();
        }
    }
}
