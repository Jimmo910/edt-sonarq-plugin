/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.scope;

import java.util.List;

/**
 * A node in a project's subsystem tree: a subsystem name and its nested subsystems.
 *
 * @param name the subsystem name, not {@code null}
 * @param children the nested subsystems, not {@code null}
 */
public record SubsystemNode(String name, List<SubsystemNode> children)
{
    /** Defensively copies {@code children}. */
    public SubsystemNode
    {
        children = List.copyOf(children);
    }
}
