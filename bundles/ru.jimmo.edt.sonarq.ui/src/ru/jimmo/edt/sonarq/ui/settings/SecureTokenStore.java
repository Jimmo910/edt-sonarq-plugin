/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.settings;

import java.io.IOException;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;

/** Stores the SonarQube user token in the platform secure storage. */
public final class SecureTokenStore
{
    private static final String NODE_PATH = "ru.jimmo.edt.sonarq"; //$NON-NLS-1$

    private static final String KEY_TOKEN = "token"; //$NON-NLS-1$

    /**
     * Loads the stored user token.
     *
     * @return the token, or an empty string when none is stored or it cannot be read
     */
    public String loadToken()
    {
        ISecurePreferences node = SecurePreferencesFactory.getDefault().node(NODE_PATH);
        try
        {
            return node.get(KEY_TOKEN, ""); //$NON-NLS-1$
        }
        catch (StorageException e)
        {
            return ""; //$NON-NLS-1$
        }
    }

    /**
     * Stores the user token, encrypted.
     *
     * @param token the token to store, not {@code null}
     * @throws StorageException when the value cannot be encrypted
     * @throws IOException when the secure storage cannot be persisted to disk
     */
    public void saveToken(String token) throws StorageException, IOException
    {
        ISecurePreferences node = SecurePreferencesFactory.getDefault().node(NODE_PATH);
        node.put(KEY_TOKEN, token, true);
        node.flush();
    }
}
