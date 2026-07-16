/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.model;

/**
 * A SonarQube project branch.
 *
 * @param name the branch name, not {@code null}
 * @param main whether this is the project's main branch
 */
public record BranchInfo(String name, boolean main)
{
}
