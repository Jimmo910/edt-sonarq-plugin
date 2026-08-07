/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.preferences;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import ru.jimmo.edt.sonarq.ui.Messages;
import ru.jimmo.edt.sonarq.ui.SonarqPlugin;
import ru.jimmo.edt.sonarq.ui.settings.SecureTokenStore;
import ru.jimmo.edt.sonarq.ui.settings.SecureTokenStore.StoredSecret;
import ru.jimmo.edt.sonarq.ui.settings.StoredSecretLabels;

/**
 * Lists the secrets this plugin has in the platform secure storage and removes the ones the user ticks.
 *
 * <p>This is the only place that removes a secret other than clearing its field on the preference page (see
 * {@link SecureTokenStore} for the removal rule in full). It exists because the secure storage is per EDT
 * <em>installation</em>: every server URL a workspace has ever pointed at keeps its token there, and nothing
 * may prune those automatically, since an entry this workspace does not recognize may be exactly the one
 * another workspace of the same installation is bound to. So the entries are shown, none is ticked to begin
 * with, and removal additionally asks for a confirmation.
 *
 * <p>Opened only from an explicit button press on the preference page — never from a background or
 * unattended path — which is also why it may report a failed removal in a message dialog, exactly as the
 * page's own "delete downloaded engine" button does. Listing reads storage on the UI thread, as the page
 * already does when it fills the token field; no secret is decrypted for the list (only the keys and the
 * unencrypted companion entries naming the URLs are read).
 */
final class StoredSecretsDialog extends Dialog
{
    private static final int DESCRIPTION_WIDTH_HINT = 480;

    private static final int TABLE_HEIGHT_HINT = 180;

    private final SecureTokenStore store;

    private final List<String> urlsInUse;

    private final List<StoredSecret> entries;

    private CheckboxTableViewer viewer;

    private boolean removedAny;

    /**
     * Creates the dialog over an already-read list of entries.
     *
     * @param shell the parent shell, not {@code null}
     * @param store the store to remove the ticked entries from, not {@code null}
     * @param urlsInUse the URLs this workspace is configured with, used to mark the entries in use and to
     *     name entries stored before the URL bookkeeping existed, not {@code null}
     */
    StoredSecretsDialog(Shell shell, SecureTokenStore store, Collection<String> urlsInUse)
    {
        super(shell);
        this.store = store;
        this.urlsInUse = List.copyOf(urlsInUse);
        this.entries = new ArrayList<>(store.listEntries(this.urlsInUse));
    }

    /**
     * Tells whether at least one entry was actually removed, so the caller can refresh whatever it shows of
     * the storage.
     *
     * @return {@code true} when this dialog removed at least one entry
     */
    boolean removedAny()
    {
        return removedAny;
    }

    @Override
    protected boolean isResizable()
    {
        return true;
    }

    @Override
    protected void configureShell(Shell newShell)
    {
        super.configureShell(newShell);
        newShell.setText(Messages.StoredSecrets_Title);
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        Composite area = (Composite)super.createDialogArea(parent);

        Label description = new Label(area, SWT.WRAP);
        description.setText(entries.isEmpty() ? Messages.StoredSecrets_Empty : Messages.StoredSecrets_Description);
        GridData descriptionData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        descriptionData.widthHint = DESCRIPTION_WIDTH_HINT;
        description.setLayoutData(descriptionData);

        viewer = CheckboxTableViewer.newCheckList(area, SWT.BORDER | SWT.V_SCROLL);
        GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableData.heightHint = TABLE_HEIGHT_HINT;
        viewer.getTable().setLayoutData(tableData);
        viewer.setContentProvider(ArrayContentProvider.getInstance());
        viewer.setLabelProvider(new LabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return StoredSecretLabels.describe((StoredSecret)element, urlsInUse);
            }
        });
        viewer.setInput(entries);
        viewer.addCheckStateListener(event -> updateRemoveEnablement());
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, IDialogConstants.OK_ID, Messages.StoredSecrets_Remove, true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
        updateRemoveEnablement();
    }

    /** Keeps the removal button disabled until the user ticks something: nothing is removed by default. */
    private void updateRemoveEnablement()
    {
        Button ok = getButton(IDialogConstants.OK_ID);
        if (ok != null && viewer != null)
        {
            ok.setEnabled(viewer.getCheckedElements().length > 0);
        }
    }

    @Override
    protected void okPressed()
    {
        Object[] checked = viewer.getCheckedElements();
        if (checked.length == 0)
        {
            super.okPressed();
            return;
        }
        boolean confirmed = MessageDialog.openConfirm(getShell(), Messages.StoredSecrets_ConfirmTitle,
            NLS.bind(Messages.StoredSecrets_ConfirmBody, Integer.valueOf(checked.length)));
        if (!confirmed)
        {
            return;
        }
        for (Object element : checked)
        {
            try
            {
                store.removeEntry((StoredSecret)element);
                removedAny = true;
            }
            catch (IOException e)
            {
                // Storage that cannot be written will not be written for the next entry either: report the
                // failure once and stop, rather than repeating the same dialog per ticked entry.
                SonarqPlugin.getInstance().getLog().error(e.getMessage(), e);
                MessageDialog.openError(getShell(), Messages.StoredSecrets_Title,
                    NLS.bind(Messages.StoredSecrets_RemoveFailed, e.getMessage()));
                break;
            }
        }
        super.okPressed();
    }
}
