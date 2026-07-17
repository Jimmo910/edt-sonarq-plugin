/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;

/**
 * Stores the SonarQube user token and CI trigger secret in the platform secure storage.
 *
 * <p>Each secret is scoped to the URL it belongs to (the server URL for the token, the CI webhook URL for
 * the secret): the storage key is derived from a hash of that URL. This keeps one workspace's server token
 * from being sent to a different server after the URL is changed — pointing the plugin at a new URL simply
 * finds no secret stored for it, rather than reusing the previous server's.
 */
public final class SecureTokenStore
{
    private static final String NODE_PATH = "ru.jimmo.edt.sonarq"; //$NON-NLS-1$

    private static final String KEY_TOKEN = "token"; //$NON-NLS-1$

    private static final String KEY_CI_SECRET = "ciSecret"; //$NON-NLS-1$

    /**
     * Loads the user token stored for the given server URL.
     *
     * @param serverUrl the server URL the token belongs to, may be empty
     * @return the token, or an empty string when none is stored for that URL or it cannot be read
     */
    public String loadToken(String serverUrl)
    {
        return load(scopedKey(KEY_TOKEN, serverUrl));
    }

    /**
     * Stores the user token for the given server URL, encrypted.
     *
     * @param serverUrl the server URL the token belongs to, may be empty
     * @param token the token to store, not {@code null}
     * @throws StorageException when the value cannot be encrypted
     * @throws IOException when the secure storage cannot be persisted to disk
     */
    public void saveToken(String serverUrl, String token) throws StorageException, IOException
    {
        save(scopedKey(KEY_TOKEN, serverUrl), token);
    }

    /**
     * Loads the CI trigger secret stored for the given CI webhook URL.
     *
     * @param ciUrl the CI webhook URL the secret belongs to, may be empty
     * @return the secret, or an empty string when none is stored for that URL or it cannot be read
     */
    public String loadCiSecret(String ciUrl)
    {
        return load(scopedKey(KEY_CI_SECRET, ciUrl));
    }

    /**
     * Stores the CI trigger secret for the given CI webhook URL, encrypted.
     *
     * @param ciUrl the CI webhook URL the secret belongs to, may be empty
     * @param secret the secret to store, not {@code null}
     * @throws StorageException when the value cannot be encrypted
     * @throws IOException when the secure storage cannot be persisted to disk
     */
    public void saveCiSecret(String ciUrl, String secret) throws StorageException, IOException
    {
        save(scopedKey(KEY_CI_SECRET, ciUrl), secret);
    }

    /**
     * Builds a storage key for a secret scoped to a URL: the base key when the URL is empty, otherwise the
     * base key plus a hash of the trimmed URL (so the key is stable and free of path-separator characters).
     *
     * @param base the base key, not {@code null}
     * @param url the URL to scope by, may be {@code null} or empty
     * @return the scoped storage key, never {@code null}
     */
    private static String scopedKey(String base, String url)
    {
        String trimmed = url == null ? "" : url.trim(); //$NON-NLS-1$
        if (trimmed.isEmpty())
        {
            return base;
        }
        return base + '.' + sha256Hex(trimmed);
    }

    private static String sha256Hex(String value)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256") //$NON-NLS-1$
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException e)
        {
            // SHA-256 is a mandated JDK algorithm; this cannot happen at runtime.
            throw new IllegalStateException(e);
        }
    }

    private String load(String key)
    {
        ISecurePreferences node = SecurePreferencesFactory.getDefault().node(NODE_PATH);
        try
        {
            return node.get(key, ""); //$NON-NLS-1$
        }
        catch (StorageException e)
        {
            return ""; //$NON-NLS-1$
        }
    }

    private void save(String key, String value) throws StorageException, IOException
    {
        ISecurePreferences node = SecurePreferencesFactory.getDefault().node(NODE_PATH);
        node.put(key, value, true);
        node.flush();
    }
}
