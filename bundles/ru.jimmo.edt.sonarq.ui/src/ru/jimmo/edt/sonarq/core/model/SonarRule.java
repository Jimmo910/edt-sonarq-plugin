/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.model;

/**
 * Description of a SonarQube rule.
 *
 * @param key the rule key, e.g. {@code bsl:MethodSize}, not {@code null}
 * @param name the human-readable rule name, not {@code null}
 * @param htmlDescription the rule description as HTML, not {@code null}
 */
public record SonarRule(String key, String name, String htmlDescription)
{
}
