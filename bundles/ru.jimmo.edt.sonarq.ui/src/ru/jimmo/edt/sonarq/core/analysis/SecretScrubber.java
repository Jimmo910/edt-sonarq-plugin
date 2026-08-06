/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Redacts credentials from text that is about to be shown to the user or written to the error log.
 *
 * <p>This exists because the CI trigger URL template carries its secret in plain sight: providers such
 * as GitLab expect the trigger token as a query parameter, so the token is part of the URL stored in the
 * plain preference store. A malformed template makes {@code URI.create} throw an
 * {@link IllegalArgumentException} whose message quotes the offending URL verbatim, token included, so
 * every message derived from such a failure must pass through here first.
 *
 * <p>Redaction happens in four passes:
 * <ol>
 * <li>literal occurrences of the known secrets handed to {@link #scrub(String, String...)};</li>
 * <li>the query string of every URL - everything from the first {@code ?} to the end of that line, since
 *     a well-formed query never contains whitespace and a malformed one is exactly the case at hand;
 *     the scheme, host and path survive, which is what makes the error actionable;</li>
 * <li>URL user information, as in {@code scheme://user:password@host};</li>
 * <li>any remaining {@code name=value} or {@code name: value} pair whose name looks credential-like.</li>
 * </ol>
 *
 * <p>All methods are pure: same input, same output, no state.
 */
public final class SecretScrubber
{
    /** The text substituted for every redacted value. */
    public static final String REDACTED = "***"; //$NON-NLS-1$

    private static final Pattern URL_QUERY =
        Pattern.compile("([a-zA-Z][a-zA-Z0-9+.\\-]*://[^\\s?#]*)\\?[^\\r\\n]*"); //$NON-NLS-1$
    private static final Pattern URL_USER_INFO =
        Pattern.compile("([a-zA-Z][a-zA-Z0-9+.\\-]*://)[^/?#\\s@]+@"); //$NON-NLS-1$
    private static final Pattern CREDENTIAL_ASSIGNMENT = Pattern.compile("(?i)([A-Za-z0-9_.\\-]*" //$NON-NLS-1$
        + "(?:token|secret|password|passwd|pwd|apikey|api_key|auth|credential|signature)" //$NON-NLS-1$
        + "[A-Za-z0-9_.\\-]*\\s*[=:]\\s*)[^\\s&;,)\\]}\"']+"); //$NON-NLS-1$

    private SecretScrubber()
    {
    }

    /**
     * Redacts credentials from the given text.
     *
     * @param text the text to scrub, may be {@code null}
     * @param knownSecrets literal secret values to blank out before the pattern passes run; {@code null},
     *     empty and blank entries are ignored
     * @return the scrubbed text, or {@code null} when {@code text} is {@code null}
     */
    public static String scrub(String text, String... knownSecrets)
    {
        if (text == null || text.isEmpty())
        {
            return text;
        }
        String result = replaceLiterals(text, knownSecrets);
        result = URL_QUERY.matcher(result).replaceAll("$1?" + REDACTED); //$NON-NLS-1$
        result = URL_USER_INFO.matcher(result).replaceAll("$1" + REDACTED + "@"); //$NON-NLS-1$ //$NON-NLS-2$
        return CREDENTIAL_ASSIGNMENT.matcher(result).replaceAll("$1" + REDACTED); //$NON-NLS-1$
    }

    /**
     * Replaces every literal occurrence of the given secrets, longest first so a secret that contains
     * another one is not left half-redacted.
     *
     * @param text the text to scrub, not {@code null}
     * @param knownSecrets the literal secrets, may be {@code null} or contain {@code null}/blank entries
     * @return the text with every known secret replaced, never {@code null}
     */
    private static String replaceLiterals(String text, String... knownSecrets)
    {
        if (knownSecrets == null || knownSecrets.length == 0)
        {
            return text;
        }
        List<String> secrets = new ArrayList<>();
        for (String secret : knownSecrets)
        {
            if (secret != null && !secret.isBlank())
            {
                secrets.add(secret);
            }
        }
        secrets.sort(Comparator.comparingInt(String::length).reversed());
        String result = text;
        for (String secret : secrets)
        {
            result = result.replace(secret, REDACTED);
        }
        return result;
    }
}
