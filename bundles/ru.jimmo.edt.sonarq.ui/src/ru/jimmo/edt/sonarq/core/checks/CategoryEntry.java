/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.checks;

/**
 * One bundled BSL diagnostic category entry.
 *
 * @param key the diagnostic (rule) key, e.g. {@code UnusedLocalVariable}, not {@code null}
 * @param name the human-readable diagnostic name, not {@code null}
 * @param category the assigned category, not {@code null}
 * @param edtCheck the id of the EDT built-in check this diagnostic duplicates, or {@code null} when
 *     {@code category} is not {@link DiagnosticCategory#EDT_DUPLICATE}
 */
public record CategoryEntry(String key, String name, DiagnosticCategory category, String edtCheck)
{
}
