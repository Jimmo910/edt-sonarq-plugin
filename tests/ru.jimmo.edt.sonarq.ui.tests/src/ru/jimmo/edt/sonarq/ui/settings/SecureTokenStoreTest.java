/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Tests the URL scoping and the stale-entry cleanup rule of {@link SecureTokenStore} (review minor M13).
 *
 * <p>Driven against an in-memory node rather than the platform secure storage: resolving the real one
 * initializes the installation-wide storage and can pop a master-password prompt, which a headless build
 * must never hit.
 */
public class SecureTokenStoreTest
{
    private static final String URL_A = "https://sonar-a.example.com";
    private static final String URL_B = "https://sonar-b.example.com";

    private static final class FakeNode implements SecureTokenStore.ISecretNode
    {
        private final Map<String, String> stored = new HashMap<>();

        @Override
        public String get(String key)
        {
            return stored.getOrDefault(key, "");
        }

        @Override
        public void put(String key, String value)
        {
            stored.put(key, value);
        }

        @Override
        public void remove(String key)
        {
            stored.remove(key);
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
        assertEquals(2, node.stored.size());
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
        assertEquals(1, node.stored.size());

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
        assertEquals(1, node.stored.size());
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

        assertEquals(2, node.stored.size());
        assertEquals("token-a", store.loadToken(URL_A));
        assertEquals("ci-secret", store.loadCiSecret(URL_A));
    }

    @Test
    public void anUnknownUrlReadsAsNoSecret()
    {
        assertEquals("", new SecureTokenStore(new FakeNode()).loadToken(URL_A));
    }
}
