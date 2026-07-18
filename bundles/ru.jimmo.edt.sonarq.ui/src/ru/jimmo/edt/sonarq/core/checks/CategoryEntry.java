/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.checks;

import java.util.List;

/**
 * One bundled BSL diagnostic category entry.
 *
 * @param key the diagnostic (rule) key, e.g. {@code UnusedLocalVariable}, not {@code null}
 * @param name the human-readable diagnostic name, not {@code null}
 * @param category the assigned category, not {@code null}
 * @param type the BSL Language Server's own diagnostic type, e.g. {@code Code smell}, not {@code null};
 *     empty when the bundled catalog does not carry one
 * @param tags the BSL Language Server's own diagnostic tags, e.g. {@code brainoverload}, not {@code null};
 *     empty when the bundled catalog does not carry any
 * @param edtCheck the id of the EDT built-in check this diagnostic duplicates, or {@code null} when
 *     {@code category} is not {@link DiagnosticCategory#EDT_DUPLICATE}
 */
public record CategoryEntry(String key, String name, DiagnosticCategory category, String type, List<String> tags,
    String edtCheck)
{
    /**
     * Canonical constructor: defensively copies {@code tags} so a caller cannot mutate this entry's state
     * through an aliased list.
     *
     * @param key the diagnostic (rule) key, not {@code null}
     * @param name the human-readable diagnostic name, not {@code null}
     * @param category the assigned category, not {@code null}
     * @param type the BSL Language Server's own diagnostic type, not {@code null}
     * @param tags the BSL Language Server's own diagnostic tags, not {@code null}
     * @param edtCheck the id of the EDT built-in check this diagnostic duplicates, or {@code null}
     */
    public CategoryEntry
    {
        tags = List.copyOf(tags);
    }
}
