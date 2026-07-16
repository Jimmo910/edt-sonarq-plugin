/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.preferences;

import java.io.IOException;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IPreferencesService;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.osgi.service.prefs.BackingStoreException;

import ru.jimmo.edt.sonarq.core.analysis.AnalysisLaunchMode;
import ru.jimmo.edt.sonarq.core.client.SonarConnection;
import ru.jimmo.edt.sonarq.core.client.SonarHttpClient;
import ru.jimmo.edt.sonarq.core.client.SonarServerException;
import ru.jimmo.edt.sonarq.ui.Messages;
import ru.jimmo.edt.sonarq.ui.SonarqPlugin;
import ru.jimmo.edt.sonarq.ui.settings.PreferenceConstants;
import ru.jimmo.edt.sonarq.ui.settings.SecureTokenStore;

/** Workspace-level SonarQube connection preferences. */
public class SonarPreferencePage extends PreferencePage implements IWorkbenchPreferencePage
{
    // The mode combo item order is coupled to these indices (see #createContents, #loadValues, #performOk).
    private static final int MODE_INDEX_SERVER = 0;

    private static final int MODE_INDEX_LOCAL = 1;

    private Combo modeCombo;

    private Text urlText;

    private Text tokenText;

    private Spinner timeoutSpinner;

    private Button testButton;

    private Label testResultLabel;

    private Combo launchModeCombo;

    private Text bslLsPathText;

    private Text scannerPathText;

    private Text ciUrlText;

    private Text ciSecretText;

    private Text extraArgsText;

    private Button showMarkersButton;

    private Button autoSyncButton;

    private Spinner autoSyncMinutesSpinner;

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

        new Label(composite, SWT.NONE).setText(Messages.PreferencePage_Token);
        tokenText = new Text(composite, SWT.BORDER | SWT.PASSWORD);
        tokenText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

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

        new Label(group, SWT.NONE).setText(Messages.PreferencePage_CiSecret);
        ciSecretText = new Text(group, SWT.BORDER | SWT.PASSWORD);
        ciSecretText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

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
        group.setLayout(new GridLayout(2, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        new Label(group, SWT.NONE).setText(Messages.PreferencePage_BslLsPath);
        bslLsPathText = new Text(group, SWT.BORDER);
        bslLsPathText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
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
        bslLsPathText.setEnabled(!server);
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
        bslLsPathText.setText(
            service.getString(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_BSL_LS_PATH, "", null)); //$NON-NLS-1$
        urlText.setText(
            service.getString(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_SERVER_URL, "", null)); //$NON-NLS-1$
        timeoutSpinner.setSelection(service.getInt(SonarqPlugin.PLUGIN_ID,
            PreferenceConstants.PREF_TIMEOUT_SECONDS, PreferenceConstants.DEFAULT_TIMEOUT_SECONDS, null));
        SecureTokenStore tokenStore = new SecureTokenStore();
        tokenText.setText(tokenStore.loadToken());

        AnalysisLaunchMode launchMode = AnalysisLaunchMode.fromKey(service.getString(SonarqPlugin.PLUGIN_ID,
            PreferenceConstants.PREF_LAUNCH_MODE, AnalysisLaunchMode.LOCAL_AUTO.name(), null));
        launchModeCombo.select(launchMode.ordinal());
        scannerPathText.setText(
            service.getString(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_SCANNER_PATH, "", null)); //$NON-NLS-1$
        ciUrlText.setText(
            service.getString(SonarqPlugin.PLUGIN_ID, PreferenceConstants.PREF_CI_URL, "", null)); //$NON-NLS-1$
        ciSecretText.setText(tokenStore.loadCiSecret());
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
            try
            {
                message = NLS.bind(Messages.PreferencePage_TestSuccess,
                    new SonarHttpClient(connection).serverVersion());
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
        node.put(PreferenceConstants.PREF_BSL_LS_PATH, bslLsPathText.getText().trim());
        node.put(PreferenceConstants.PREF_SERVER_URL, urlText.getText().trim());
        node.putInt(PreferenceConstants.PREF_TIMEOUT_SECONDS, timeoutSpinner.getSelection());
        // Selection index is coupled to AnalysisLaunchMode's declaration order (see #createLaunchGroup).
        AnalysisLaunchMode mode = AnalysisLaunchMode.values()[launchModeCombo.getSelectionIndex()];
        node.put(PreferenceConstants.PREF_LAUNCH_MODE, mode.name());
        node.put(PreferenceConstants.PREF_SCANNER_PATH, scannerPathText.getText().trim());
        node.put(PreferenceConstants.PREF_CI_URL, ciUrlText.getText().trim());
        node.put(PreferenceConstants.PREF_EXTRA_ARGS, extraArgsText.getText().trim());
        node.putBoolean(PreferenceConstants.PREF_SHOW_MARKERS, showMarkersButton.getSelection());
        node.putBoolean(PreferenceConstants.PREF_AUTO_SYNC, autoSyncButton.getSelection());
        node.putInt(PreferenceConstants.PREF_AUTO_SYNC_MINUTES, autoSyncMinutesSpinner.getSelection());
        try
        {
            node.flush();
            SecureTokenStore tokenStore = new SecureTokenStore();
            tokenStore.saveToken(tokenText.getText().trim());
            tokenStore.saveCiSecret(ciSecretText.getText().trim());
        }
        catch (BackingStoreException | StorageException | IOException e)
        {
            SonarqPlugin.getInstance().getLog().error(e.getMessage(), e);
            return false;
        }
        return true;
    }
}
