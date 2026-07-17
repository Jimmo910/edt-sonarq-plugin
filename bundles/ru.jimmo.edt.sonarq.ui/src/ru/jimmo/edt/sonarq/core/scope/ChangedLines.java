/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.scope;

import java.io.File;

/** Tells which lines of which files changed relative to a base, for base-branch issue scoping. */
public interface ChangedLines
{
    /**
     * @return {@code true} when a base was resolved and a diff computed; {@code false} means callers must
     *     not filter (degrade to showing all issues)
     */
    boolean available();

    /**
     * @param repoRelativePath a work-tree-relative path with {@code /} separators, not {@code null}
     * @param line the 1-based line; {@code <= 0} asks whether the file has any changed line
     * @return {@code true} when that line (or, for {@code line <= 0}, the file) changed vs the base
     */
    boolean isChanged(String repoRelativePath, int line);

    /**
     * @return the git work-tree root directory, or {@code null} when {@link #available()} is {@code false}
     */
    File workTreeRoot();
}
