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
 *
 * <p>Storing an empty secret <em>removes</em> the entry instead of writing an empty value (review minor
 * M13). That is the whole cleanup rule, and it is deliberately the only one: clearing the token field and
 * pressing OK drops the stored token for that URL, so a server the user has finished with does not keep a
 * credential in the secure storage forever. Nothing is pruned merely for not matching the URL configured
 * right now — the secure storage is per <em>installation</em>, shared by every workspace of that EDT, so a
 * URL absent from this workspace's preferences may well be the one another workspace is bound to. An empty
 * stored value was never distinguishable from a missing one anyway ({@link #loadToken} answers {@code ""}
 * for both), so the rule removes stale entries without changing any observable behaviour.
 */
public final class SecureTokenStore
{
    private static final String NODE_PATH = "ru.jimmo.edt.sonarq"; //$NON-NLS-1$

    private static final String KEY_TOKEN = "token"; //$NON-NLS-1$

    private static final String KEY_CI_SECRET = "ciSecret"; //$NON-NLS-1$

    private static final String EMPTY = ""; //$NON-NLS-1$

    private final ISecretNode node;

    /** Creates a store backed by the platform secure storage. */
    public SecureTokenStore()
    {
        this(new PlatformSecretNode());
    }

    /**
     * Creates a store over the given backing node. Package-private: it exists so the headless test fragment
     * can drive the scoping and cleanup rules without touching the platform secure storage, which may
     * prompt for a master password.
     *
     * @param node the backing node, not {@code null}
     */
    SecureTokenStore(ISecretNode node)
    {
        this.node = node;
    }

    /**
     * Loads the user token stored for the given server URL.
     *
     * @param serverUrl the server URL the token belongs to, may be empty
     * @return the token, or an empty string when none is stored for that URL or it cannot be read
     */
    public String loadToken(String serverUrl)
    {
        return node.get(scopedKey(KEY_TOKEN, serverUrl));
    }

    /**
     * Stores the user token for the given server URL, encrypted; an empty token removes the stored entry
     * (see the class javadoc).
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
        return node.get(scopedKey(KEY_CI_SECRET, ciUrl));
    }

    /**
     * Stores the CI trigger secret for the given CI webhook URL, encrypted; an empty secret removes the
     * stored entry (see the class javadoc).
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
        String trimmed = url == null ? EMPTY : url.trim();
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

    private void save(String key, String value) throws StorageException, IOException
    {
        if (value == null || value.isEmpty())
        {
            node.remove(key);
        }
        else
        {
            node.put(key, value);
        }
    }

    /**
     * The slice of the platform secure storage this store uses, as a seam for headless tests.
     *
     * <p>Every method persists immediately, as the platform node's own {@code flush} does: the preference
     * page has no second "commit" step to hang a deferred write on.
     */
    interface ISecretNode
    {
        /**
         * Reads a stored value.
         *
         * @param key the storage key, not {@code null}
         * @return the value, or an empty string when absent or unreadable
         */
        String get(String key);

        /**
         * Stores a value, encrypted, and persists the change.
         *
         * @param key the storage key, not {@code null}
         * @param value the value to store, not {@code null} and not empty
         * @throws StorageException when the value cannot be encrypted
         * @throws IOException when the storage cannot be persisted
         */
        void put(String key, String value) throws StorageException, IOException;

        /**
         * Removes a stored value, if any, and persists the change.
         *
         * @param key the storage key, not {@code null}
         * @throws IOException when the storage cannot be persisted
         */
        void remove(String key) throws IOException;
    }

    /** {@link ISecretNode} over the platform secure storage. */
    private static final class PlatformSecretNode implements ISecretNode
    {
        @Override
        public String get(String key)
        {
            try
            {
                return node().get(key, EMPTY);
            }
            catch (StorageException e)
            {
                return EMPTY;
            }
        }

        @Override
        public void put(String key, String value) throws StorageException, IOException
        {
            ISecurePreferences node = node();
            node.put(key, value, true);
            node.flush();
        }

        @Override
        public void remove(String key) throws IOException
        {
            ISecurePreferences node = node();
            node.remove(key);
            node.flush();
        }

        /**
         * Resolves the storage node per call, rather than once per store instance: a
         * {@link SecureTokenStore} is created eagerly on paths that may never touch a secret, and resolving
         * the node initializes the platform secure storage.
         *
         * @return the plug-in's secure storage node, never {@code null}
         */
        private static ISecurePreferences node()
        {
            return SecurePreferencesFactory.getDefault().node(NODE_PATH);
        }
    }
}
