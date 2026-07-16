/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.preferences;

import java.util.List;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.dialogs.PropertyPage;
import org.osgi.service.prefs.BackingStoreException;

import ru.jimmo.edt.sonarq.core.client.SonarConnection;
import ru.jimmo.edt.sonarq.core.client.SonarHttpClient;
import ru.jimmo.edt.sonarq.core.client.SonarServerException;
import ru.jimmo.edt.sonarq.core.model.ComponentInfo;
import ru.jimmo.edt.sonarq.core.settings.ProjectBinding;
import ru.jimmo.edt.sonarq.ui.Messages;
import ru.jimmo.edt.sonarq.ui.SonarqPlugin;
import ru.jimmo.edt.sonarq.ui.settings.ProjectBindingStore;
import ru.jimmo.edt.sonarq.ui.settings.SonarConnectionFactory;

/** Per-project SonarQube binding: project key, fixed branch, path prefix. */
public class SonarProjectPropertyPage extends PropertyPage
{
    private Text projectKeyText;

    private Text branchText;

    private Text prefixText;

    private Label findResultLabel;

    @Override
    protected Control createContents(Composite parent)
    {
        noDefaultAndApplyButton();
        Composite composite = new Composite(parent, SWT.NONE);
        composite.setLayout(new GridLayout(2, false));

        new Label(composite, SWT.NONE).setText(Messages.PropertyPage_ProjectKey);
        projectKeyText = new Text(composite, SWT.BORDER);
        projectKeyText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        projectKeyText.setMessage(Messages.PropertyPage_ProjectKey_Hint);
        projectKeyText.setToolTipText(Messages.PropertyPage_ProjectKey_Hint);

        Button findButton = new Button(composite, SWT.PUSH);
        findButton.setText(Messages.PropertyPage_FindKey);
        findButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> findKey()));
        findResultLabel = new Label(composite, SWT.WRAP);
        findResultLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(composite, SWT.NONE).setText(Messages.PropertyPage_Branch);
        branchText = new Text(composite, SWT.BORDER);
        branchText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Label hint = new Label(composite, SWT.WRAP);
        hint.setText(Messages.PropertyPage_BranchHint);
        hint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        new Label(composite, SWT.NONE).setText(Messages.PropertyPage_PathPrefix);
        prefixText = new Text(composite, SWT.BORDER);
        prefixText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        prefixText.setMessage(Messages.PropertyPage_PathPrefix_Hint);
        prefixText.setToolTipText(Messages.PropertyPage_PathPrefix_Hint);

        ProjectBinding binding = new ProjectBindingStore().load(getProject());
        projectKeyText.setText(binding.projectKey());
        branchText.setText(binding.branchOverride());
        prefixText.setText(binding.pathPrefix());
        return composite;
    }

    private IProject getProject()
    {
        return Adapters.adapt(getElement(), IProject.class);
    }

    private void findKey()
    {
        Optional<SonarConnection> connection = new SonarConnectionFactory().create();
        if (connection.isEmpty())
        {
            findResultLabel.setText(Messages.IssuesView_Status_NotConfigured);
            return;
        }
        String projectName = getProject().getName();
        Job job = Job.create(Messages.PropertyPage_FindKey, monitor ->
        {
            String key = null;
            String error = null;
            try
            {
                List<ComponentInfo> found = new SonarHttpClient(connection.get()).searchProjects(projectName);
                key = found.isEmpty() ? null : found.get(0).key();
            }
            catch (SonarServerException e)
            {
                error = e.getMessage();
            }
            String foundKey = key;
            String failure = error;
            Display.getDefault().asyncExec(() ->
            {
                if (findResultLabel.isDisposed())
                {
                    return;
                }
                if (failure != null)
                {
                    findResultLabel.setText(NLS.bind(Messages.PreferencePage_TestFailure, failure));
                }
                else if (foundKey == null)
                {
                    findResultLabel.setText(Messages.PropertyPage_FindKeyNoMatch);
                }
                else
                {
                    projectKeyText.setText(foundKey);
                    findResultLabel.setText(""); //$NON-NLS-1$
                }
                findResultLabel.getParent().layout();
            });
        });
        job.setSystem(true);
        job.schedule();
    }

    @Override
    public boolean performOk()
    {
        ProjectBinding binding = new ProjectBinding(projectKeyText.getText().trim(),
            branchText.getText().trim(), prefixText.getText().trim());
        try
        {
            new ProjectBindingStore().save(getProject(), binding);
        }
        catch (BackingStoreException e)
        {
            SonarqPlugin.getInstance().getLog().error(e.getMessage(), e);
            return false;
        }
        return true;
    }
}
