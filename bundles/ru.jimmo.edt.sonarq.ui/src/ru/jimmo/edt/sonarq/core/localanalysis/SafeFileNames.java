/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

/**
 * Turns arbitrary SonarQube project keys into safe, unique file-name segments for the per-project files the
 * local analysis writes under the plugin state directory (the report directory of
 * {@link LocalIssueProvider} and the generated configuration of {@link BslConfigWriter}).
 */
final class SafeFileNames
{
    private static final int MAX_SEGMENT_LENGTH = 80;

    private SafeFileNames()
    {
    }

    /**
     * Turns a SonarQube project key into a safe single path segment. Real Sonar keys routinely contain
     * characters that are illegal or dangerous in a file name ({@code :}, {@code /}, {@code ..}), and the
     * files named from them are deleted and rewritten per run, so the raw key must never reach the
     * filesystem. The key itself is still used verbatim for component-key mapping.
     *
     * @param key the project key, not {@code null}
     * @return a file-name-safe segment, never {@code null} or a path-traversal token
     */
    static String segmentFor(String key)
    {
        // Allow only letters, digits, underscore and hyphen. Dots are deliberately excluded so the name
        // can never be "."/".." nor end in a dot (which Windows silently trims), and separators/colons
        // become underscores - the result is always a single, contained path segment. A short hash suffix
        // keeps the name unique and bounded even for long keys or collisions after sanitising, and a
        // leading underscore keeps it clear of Windows reserved device names (CON, NUL, COM1, ...).
        String cleaned = key.replaceAll("[^A-Za-z0-9_-]", "_"); //$NON-NLS-1$ //$NON-NLS-2$
        if (cleaned.length() > MAX_SEGMENT_LENGTH)
        {
            cleaned = cleaned.substring(0, MAX_SEGMENT_LENGTH);
        }
        return '_' + cleaned + '_' + Integer.toHexString(key.hashCode());
    }
}
