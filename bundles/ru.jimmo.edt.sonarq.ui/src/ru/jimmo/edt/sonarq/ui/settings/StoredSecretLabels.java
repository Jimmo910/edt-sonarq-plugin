/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.settings;

import java.util.Collection;

import org.eclipse.osgi.util.NLS;

import ru.jimmo.edt.sonarq.ui.Messages;
import ru.jimmo.edt.sonarq.ui.settings.SecureTokenStore.SecretKind;
import ru.jimmo.edt.sonarq.ui.settings.SecureTokenStore.StoredSecret;

/**
 * Renders a {@link StoredSecret} as the one line the user reads in the stored-secrets dialog.
 *
 * <p>Kept out of the dialog itself, and free of SWT, so the wording rule can be exercised headlessly: what
 * the line says decides whether the user can tell which entry is safe to remove.
 */
public final class StoredSecretLabels
{
    private static final String SEPARATOR = ": "; //$NON-NLS-1$

    /** How much of the storage key is shown as an identifier when the entry's URL is not recorded. */
    private static final int SHORT_ID_LENGTH = 8;

    private StoredSecretLabels()
    {
    }

    /**
     * Describes one stored secret: what kind it is, which URL it belongs to, and whether that URL is the one
     * this workspace is configured with right now (the entry the user most likely wants to keep).
     *
     * @param entry the stored entry, not {@code null}
     * @param urlsInUse the URLs this workspace is currently configured with, not {@code null}
     * @return the display text, never {@code null}
     */
    public static String describe(StoredSecret entry, Collection<String> urlsInUse)
    {
        String kind = entry.kind() == SecretKind.TOKEN
            ? Messages.StoredSecrets_Kind_Token
            : Messages.StoredSecrets_Kind_CiSecret;
        if (entry.url().isEmpty())
        {
            return kind + SEPARATOR + NLS.bind(Messages.StoredSecrets_UnknownUrl, shortId(entry.key()));
        }
        String text = kind + SEPARATOR + entry.url();
        return urlsInUse.contains(entry.url()) ? text + ' ' + Messages.StoredSecrets_InUse : text;
    }

    /**
     * Shortens a storage key to an identifier the user can at least tell two unnamed entries apart by: the
     * leading characters of the URL hash the key is scoped with, or the whole key when it carries no hash.
     *
     * @param key the storage key, not {@code null}
     * @return the short identifier, never {@code null}
     */
    static String shortId(String key)
    {
        int dot = key.indexOf('.');
        if (dot < 0 || dot + 1 >= key.length())
        {
            return key;
        }
        String hash = key.substring(dot + 1);
        return hash.length() <= SHORT_ID_LENGTH ? hash : hash.substring(0, SHORT_ID_LENGTH);
    }
}
