/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import ru.jimmo.edt.sonarq.ui.settings.SecureTokenStore.SecretKind;
import ru.jimmo.edt.sonarq.ui.settings.SecureTokenStore.StoredSecret;

/**
 * Tests the URL scoping and the removal rule of {@link SecureTokenStore} (review minor M13, and the explicit
 * cleanup path added on top of it).
 *
 * <p>Driven against an in-memory node rather than the platform secure storage: resolving the real one
 * initializes the installation-wide storage and can pop a master-password prompt, which a headless build
 * must never hit.
 */
public class SecureTokenStoreTest
{
    private static final String URL_A = "https://sonar-a.example.com";
    private static final String URL_B = "https://sonar-b.example.com";

    /** Prefix of the companion entries that record a secret's URL; mirrors the store's own constant. */
    private static final String URL_PREFIX = "url.";

    private static final class FakeNode implements SecureTokenStore.ISecretNode
    {
        private final Map<String, String> stored = new HashMap<>();

        private final Map<String, Boolean> encrypted = new HashMap<>();

        @Override
        public String get(String key)
        {
            return stored.getOrDefault(key, "");
        }

        @Override
        public void put(String key, String value, boolean encrypt)
        {
            stored.put(key, value);
            encrypted.put(key, Boolean.valueOf(encrypt));
        }

        @Override
        public void remove(String key)
        {
            stored.remove(key);
            encrypted.remove(key);
        }

        @Override
        public List<String> keys()
        {
            return new ArrayList<>(stored.keySet());
        }

        /**
         * The stored secrets, without the companion entries that merely record which URL each belongs to.
         *
         * @return the secret keys and values, never {@code null}
         */
        Map<String, String> secrets()
        {
            Map<String, String> secrets = new HashMap<>(stored);
            secrets.keySet().removeIf(key -> key.startsWith(URL_PREFIX));
            return secrets;
        }

        /** Drops every companion URL entry, leaving the storage as an older plugin version wrote it. */
        void forgetUrls()
        {
            stored.keySet().removeIf(key -> key.startsWith(URL_PREFIX));
        }
    }

    @Test
    public void tokensOfDifferentUrlsDoNotOverwriteEachOther() throws Exception
    {
        FakeNode node = new FakeNode();
        SecureTokenStore store = new SecureTokenStore(node);

        store.saveToken(URL_A, "token-a");
        store.saveToken(URL_B, "token-b");

        assertEquals("token-a", store.loadToken(URL_A));
        assertEquals("token-b", store.loadToken(URL_B));
        assertEquals(2, node.secrets().size());
    }

    /**
     * The cleanup rule: clearing the token field for a URL and pressing OK stores the empty string, which
     * must drop the entry rather than leave an encrypted blank behind for a server the user is done with.
     */
    @Test
    public void clearingTheTokenRemovesTheEntryInsteadOfStoringAnEmptyValue() throws Exception
    {
        FakeNode node = new FakeNode();
        SecureTokenStore store = new SecureTokenStore(node);
        store.saveToken(URL_A, "token-a");
        assertEquals(1, node.secrets().size());

        store.saveToken(URL_A, "");

        assertTrue("the entry must be gone, not blank: " + node.stored, node.stored.isEmpty());
        assertEquals("", store.loadToken(URL_A));
    }

    /** Cleaning up one URL must not touch the secret of another - possibly another workspace's server. */
    @Test
    public void clearingOneUrlLeavesTheOtherUrlsTokenIntact() throws Exception
    {
        FakeNode node = new FakeNode();
        SecureTokenStore store = new SecureTokenStore(node);
        store.saveToken(URL_A, "token-a");
        store.saveToken(URL_B, "token-b");

        store.saveToken(URL_A, "");

        assertEquals("", store.loadToken(URL_A));
        assertEquals("token-b", store.loadToken(URL_B));
        assertEquals(1, node.secrets().size());
    }

    @Test
    public void clearingTheCiSecretRemovesItsEntryToo() throws Exception
    {
        FakeNode node = new FakeNode();
        SecureTokenStore store = new SecureTokenStore(node);
        store.saveCiSecret(URL_A, "ci-secret");

        store.saveCiSecret(URL_A, "");

        assertTrue("the entry must be gone, not blank: " + node.stored, node.stored.isEmpty());
    }

    /** The token and the CI secret of one and the same URL are separate entries. */
    @Test
    public void tokenAndCiSecretOfTheSameUrlAreStoredSeparately() throws Exception
    {
        FakeNode node = new FakeNode();
        SecureTokenStore store = new SecureTokenStore(node);

        store.saveToken(URL_A, "token-a");
        store.saveCiSecret(URL_A, "ci-secret");

        assertEquals(2, node.secrets().size());
        assertEquals("token-a", store.loadToken(URL_A));
        assertEquals("ci-secret", store.loadCiSecret(URL_A));
    }

    @Test
    public void anUnknownUrlReadsAsNoSecret()
    {
        assertEquals("", new SecureTokenStore(new FakeNode()).loadToken(URL_A));
    }

    /**
     * The URL a secret belongs to is recorded next to it, unencrypted, so the cleanup dialog can list what
     * has accumulated in the installation-wide storage without decrypting anything (a hash cannot be
     * reversed, so without this bookkeeping the list would be unreadable).
     */
    @Test
    public void savingASecretRecordsTheUrlItBelongsToUnencrypted() throws Exception
    {
        FakeNode node = new FakeNode();
        SecureTokenStore store = new SecureTokenStore(node);

        store.saveToken(URL_A, "token-a");

        String secretKey = node.secrets().keySet().iterator().next();
        assertEquals(URL_A, node.get(URL_PREFIX + secretKey));
        assertEquals(Boolean.TRUE, node.encrypted.get(secretKey));
        assertEquals(Boolean.FALSE, node.encrypted.get(URL_PREFIX + secretKey));
    }

    /** Every secret this plugin owns is listed, named by kind and URL; companion entries are not secrets. */
    @Test
    public void listEntriesNamesEverySecretByKindAndUrl() throws Exception
    {
        FakeNode node = new FakeNode();
        SecureTokenStore store = new SecureTokenStore(node);
        store.saveToken(URL_A, "token-a");
        store.saveCiSecret(URL_B, "ci-secret");

        List<StoredSecret> entries = store.listEntries(List.of());

        assertEquals("companion URL entries must not be listed as secrets: " + entries, 2, entries.size());
        assertEquals(SecretKind.TOKEN, entries.get(0).kind());
        assertEquals(URL_A, entries.get(0).url());
        assertEquals(SecretKind.CI_SECRET, entries.get(1).kind());
        assertEquals(URL_B, entries.get(1).url());
    }

    /**
     * Entries written before the URL bookkeeping existed carry no companion entry; they must still be
     * listed, and named whenever the caller knows the URL they belong to.
     */
    @Test
    public void listEntriesResolvesOlderEntriesFromTheUrlsTheCallerKnows() throws Exception
    {
        FakeNode node = new FakeNode();
        SecureTokenStore store = new SecureTokenStore(node);
        store.saveToken(URL_A, "token-a");
        node.forgetUrls();

        assertEquals(URL_A, store.listEntries(List.of(URL_A)).get(0).url());
        assertEquals("", store.listEntries(List.of(URL_B)).get(0).url());
        assertEquals(1, store.listEntries(List.of()).size());
    }

    /**
     * The explicit cleanup rule: removing an entry the user ticked drops that secret and the companion
     * entry naming its URL, and nothing else - the storage is shared with every other workspace of this EDT
     * installation, so a secret the user did not tick must survive untouched.
     */
    @Test
    public void removeEntryDropsOnlyThatSecretAndItsUrlEntry() throws Exception
    {
        FakeNode node = new FakeNode();
        SecureTokenStore store = new SecureTokenStore(node);
        store.saveToken(URL_A, "token-a");
        store.saveToken(URL_B, "token-b");
        store.saveCiSecret(URL_A, "ci-secret");

        StoredSecret target = store.listEntries(List.of()).stream()
            .filter(entry -> entry.kind() == SecretKind.TOKEN && URL_A.equals(entry.url()))
            .findFirst()
            .orElseThrow();
        store.removeEntry(target);

        assertEquals("", store.loadToken(URL_A));
        assertEquals("token-b", store.loadToken(URL_B));
        assertEquals("ci-secret", store.loadCiSecret(URL_A));
        assertEquals(2, node.secrets().size());
        assertFalse("the companion URL entry must go with its secret: " + node.stored,
            node.stored.containsKey(URL_PREFIX + target.key()));
    }
}
