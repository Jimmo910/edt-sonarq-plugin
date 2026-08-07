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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

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
 * <p><b>The removal rule, in full.</b> A stored secret is removed in exactly two cases, both of them the
 * user's own explicit action:
 * <ol>
 * <li>the user clears the secret's field for that URL and presses OK, which stores an empty value: that
 * <em>removes</em> the entry instead of writing an empty one (review minor M13), so a server the user has
 * finished with does not keep a credential forever. An empty stored value was never distinguishable from a
 * missing one anyway ({@link #loadToken} answers {@code ""} for both), so this changes no other behaviour;</li>
 * <li>the user opens the stored-credentials dialog (Preferences &gt; SonarQube), ticks entries and confirms:
 * {@link #removeEntry} then deletes precisely those entries, one by one.</li>
 * </ol>
 * Nothing is ever pruned merely for not matching the URL configured right now — the secure storage is per
 * <em>installation</em>, shared by every workspace of that EDT, so a URL absent from this workspace's
 * preferences may well be the one another workspace is bound to. That is exactly why the cleanup is offered
 * as an explicit, enumerable choice rather than as an automatic sweep.
 *
 * <p><b>How enumeration can name the URLs.</b> A hash cannot be reversed, so {@link #saveToken} and
 * {@link #saveCiSecret} additionally record the URL each secret belongs to, as a companion entry keyed
 * {@code url.<secret key>} in the same node. The companion entry holds no secret and is stored
 * <em>unencrypted</em> on purpose: {@link #listEntries} can then build the list without decrypting anything,
 * so listing never triggers the master-password machinery. Entries written before this bookkeeping existed
 * carry no companion URL; {@link #listEntries} still lists them (with an empty {@link StoredSecret#url()}),
 * resolving the ones that belong to a URL the caller already knows. Removing a secret removes its companion
 * entry with it.
 */
public final class SecureTokenStore
{
    private static final String NODE_PATH = "ru.jimmo.edt.sonarq"; //$NON-NLS-1$

    private static final String KEY_TOKEN = "token"; //$NON-NLS-1$

    private static final String KEY_CI_SECRET = "ciSecret"; //$NON-NLS-1$

    /**
     * Prefix of the companion entries that record which URL a stored secret belongs to. Deliberately a
     * namespace of its own, so no secret key can ever be mistaken for a companion entry or the other way
     * round (see the class javadoc).
     */
    private static final String KEY_URL_PREFIX = "url."; //$NON-NLS-1$

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
        save(scopedKey(KEY_TOKEN, serverUrl), serverUrl, token);
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
        save(scopedKey(KEY_CI_SECRET, ciUrl), ciUrl, secret);
    }

    /**
     * Lists every secret this plugin owns in the secure storage, so the user can see what has accumulated
     * there and clean it up (see the class javadoc). The secrets themselves are never read, only their
     * keys and the companion URL entries; nothing is decrypted.
     *
     * @param knownUrls URLs the caller can name (typically the server and CI URLs this workspace is
     *     configured with), used to resolve entries stored before the companion URL bookkeeping existed;
     *     may be empty, not {@code null}
     * @return the stored entries, ordered by kind and then by URL, never {@code null}
     */
    public List<StoredSecret> listEntries(Collection<String> knownUrls)
    {
        Map<String, String> urlByKey = new HashMap<>();
        for (String url : knownUrls)
        {
            String trimmed = url == null ? EMPTY : url.trim();
            if (!trimmed.isEmpty())
            {
                urlByKey.put(scopedKey(KEY_TOKEN, trimmed), trimmed);
                urlByKey.put(scopedKey(KEY_CI_SECRET, trimmed), trimmed);
            }
        }
        List<StoredSecret> entries = new ArrayList<>();
        for (String key : node.keys())
        {
            SecretKind kind = kindOf(key);
            if (kind == null)
            {
                continue;
            }
            String storedUrl = node.get(KEY_URL_PREFIX + key);
            entries.add(new StoredSecret(key, kind,
                storedUrl.isEmpty() ? urlByKey.getOrDefault(key, EMPTY) : storedUrl));
        }
        entries.sort(Comparator.comparingInt((StoredSecret entry) -> entry.kind().ordinal())
            .thenComparing(StoredSecret::url)
            .thenComparing(StoredSecret::key));
        return entries;
    }

    /**
     * Removes one listed entry - its secret and the companion entry naming its URL - and nothing else.
     *
     * @param entry an entry obtained from {@link #listEntries}, not {@code null}
     * @throws IOException when the secure storage cannot be persisted to disk
     */
    public void removeEntry(StoredSecret entry) throws IOException
    {
        node.remove(entry.key());
        node.remove(KEY_URL_PREFIX + entry.key());
    }

    /**
     * Classifies a storage key found in this plugin's node.
     *
     * @param key the storage key, not {@code null}
     * @return the kind of secret stored under it, or {@code null} when the key is not a secret of ours (a
     *     companion URL entry, or anything a future version may add)
     */
    private static SecretKind kindOf(String key)
    {
        if (isSecretKey(key, KEY_TOKEN))
        {
            return SecretKind.TOKEN;
        }
        if (isSecretKey(key, KEY_CI_SECRET))
        {
            return SecretKind.CI_SECRET;
        }
        return null;
    }

    /**
     * Tells whether a storage key is the unscoped or a URL-scoped key of the given base (see
     * {@link #scopedKey}).
     *
     * @param key the storage key, not {@code null}
     * @param base the base key, not {@code null}
     * @return {@code true} when the key belongs to that base
     */
    private static boolean isSecretKey(String key, String base)
    {
        return key.equals(base) || key.startsWith(base + '.');
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

    /**
     * Writes (or removes) one secret together with the companion entry that names the URL it belongs to.
     *
     * @param key the scoped storage key of the secret, not {@code null}
     * @param url the URL the secret belongs to, may be {@code null} or empty
     * @param value the secret to store; empty or {@code null} removes the entry
     * @throws StorageException when the value cannot be encrypted
     * @throws IOException when the secure storage cannot be persisted to disk
     */
    private void save(String key, String url, String value) throws StorageException, IOException
    {
        String trimmedUrl = url == null ? EMPTY : url.trim();
        if (value == null || value.isEmpty())
        {
            node.remove(key);
            node.remove(KEY_URL_PREFIX + key);
        }
        else
        {
            node.put(key, value, true);
            if (!trimmedUrl.isEmpty())
            {
                // Not a secret, and never encrypted: listEntries must be able to name the URL without
                // decrypting anything (see the class javadoc).
                node.put(KEY_URL_PREFIX + key, trimmedUrl, false);
            }
        }
    }

    /** The kind of secret a stored entry holds. */
    public enum SecretKind
    {
        /** A SonarQube user token, belonging to a server URL. */
        TOKEN,
        /** A CI trigger secret, belonging to a CI webhook URL. */
        CI_SECRET
    }

    /**
     * One secret this plugin has in the secure storage, as shown to the user for cleanup.
     *
     * @param key the storage key it lives under, never {@code null}
     * @param kind what kind of secret it is, never {@code null}
     * @param url the URL it belongs to, or an empty string when that is not recorded (an entry written
     *     before the companion URL bookkeeping existed, for a URL this workspace does not know)
     */
    public record StoredSecret(String key, SecretKind kind, String url)
    {
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
         * Stores a value and persists the change.
         *
         * @param key the storage key, not {@code null}
         * @param value the value to store, not {@code null} and not empty
         * @param encrypt whether the value must be encrypted ({@code true} for secrets, {@code false} for
         *     the companion entries that merely name a URL)
         * @throws StorageException when the value cannot be encrypted
         * @throws IOException when the storage cannot be persisted
         */
        void put(String key, String value, boolean encrypt) throws StorageException, IOException;

        /**
         * Removes a stored value, if any, and persists the change.
         *
         * @param key the storage key, not {@code null}
         * @throws IOException when the storage cannot be persisted
         */
        void remove(String key) throws IOException;

        /**
         * Lists the keys stored in this node.
         *
         * @return the keys, never {@code null}
         */
        List<String> keys();
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
        public void put(String key, String value, boolean encrypt) throws StorageException, IOException
        {
            ISecurePreferences node = node();
            node.put(key, value, encrypt);
            node.flush();
        }

        @Override
        public void remove(String key) throws IOException
        {
            ISecurePreferences node = node();
            node.remove(key);
            node.flush();
        }

        @Override
        public List<String> keys()
        {
            return List.of(node().keys());
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
