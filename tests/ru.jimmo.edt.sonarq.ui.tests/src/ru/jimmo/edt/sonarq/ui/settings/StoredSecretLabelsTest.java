/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import ru.jimmo.edt.sonarq.ui.Messages;
import ru.jimmo.edt.sonarq.ui.settings.SecureTokenStore.SecretKind;
import ru.jimmo.edt.sonarq.ui.settings.SecureTokenStore.StoredSecret;

/**
 * Tests the wording of the stored-secrets list: the line has to let the user tell which entry is safe to
 * remove, since the secure storage is shared by every workspace of the EDT installation.
 */
public class StoredSecretLabelsTest
{
    private static final String URL_A = "https://sonar-a.example.com";
    private static final String URL_B = "https://sonar-b.example.com";

    private static final String KEY = "token.0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcd";

    @Test
    public void anEntryOfTheConfiguredUrlIsMarkedAsInUse()
    {
        String text = StoredSecretLabels.describe(new StoredSecret(KEY, SecretKind.TOKEN, URL_A), List.of(URL_A));

        assertTrue(text, text.startsWith(Messages.StoredSecrets_Kind_Token));
        assertTrue(text, text.contains(URL_A));
        assertTrue(text, text.endsWith(Messages.StoredSecrets_InUse));
    }

    @Test
    public void anEntryOfAnotherUrlIsNotMarkedAsInUse()
    {
        String text = StoredSecretLabels.describe(new StoredSecret(KEY, SecretKind.CI_SECRET, URL_B), List.of(URL_A));

        assertTrue(text, text.startsWith(Messages.StoredSecrets_Kind_CiSecret));
        assertTrue(text, text.contains(URL_B));
        assertFalse(text, text.contains(Messages.StoredSecrets_InUse));
    }

    /**
     * An entry whose URL was never recorded (written before that bookkeeping existed, by a URL this
     * workspace does not know) must still be distinguishable from the next one: it is shown with the
     * leading characters of the hash its key is scoped with.
     */
    @Test
    public void anEntryWithoutAKnownUrlIsShownWithAShortIdentifier()
    {
        String text = StoredSecretLabels.describe(new StoredSecret(KEY, SecretKind.TOKEN, ""), List.of(URL_A));

        assertTrue(text, text.contains("01234567"));
        assertFalse("the whole hash is noise, not an identifier: " + text, text.contains(KEY));
    }

    @Test
    public void aKeyWithoutAHashIsItsOwnIdentifier()
    {
        assertEquals("token", StoredSecretLabels.shortId("token"));
    }
}
