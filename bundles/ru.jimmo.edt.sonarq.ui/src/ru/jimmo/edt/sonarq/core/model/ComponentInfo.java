/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.model;

/**
 * A SonarQube component (project) as returned by the components search.
 *
 * @param key the component key, not {@code null}
 * @param name the human-readable component name, not {@code null}
 */
public record ComponentInfo(String key, String name)
{
}
