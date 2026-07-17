/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.scope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Reads a project's subsystem tree (names and nesting) from its on-disk {@code src/Subsystems} folder. */
public final class SubsystemTreeReader
{
    private static final String SRC = "src"; //$NON-NLS-1$
    private static final String SUBSYSTEMS = "Subsystems"; //$NON-NLS-1$
    private static final String MDO_SUFFIX = ".mdo"; //$NON-NLS-1$

    private SubsystemTreeReader()
    {
    }

    /**
     * Reads the top-level subsystems (with their nested subsystems) of a project.
     *
     * @param projectRoot the EDT project location, not {@code null}
     * @return the subsystem forest sorted by name, empty when the project has no subsystems
     */
    public static List<SubsystemNode> read(Path projectRoot)
    {
        return readLevel(projectRoot.resolve(SRC).resolve(SUBSYSTEMS));
    }

    /**
     * Reads the subsystems directly under {@code subsystemsDir}, recursing into each one's nested
     * {@code Subsystems} folder.
     *
     * @param subsystemsDir a {@code Subsystems} directory, not {@code null}, may not exist
     * @return the subsystems sorted by name, empty when {@code subsystemsDir} is absent
     */
    private static List<SubsystemNode> readLevel(Path subsystemsDir)
    {
        if (!Files.isDirectory(subsystemsDir))
        {
            return List.of();
        }
        List<SubsystemNode> nodes = new ArrayList<>();
        try (Stream<Path> children = Files.list(subsystemsDir))
        {
            for (Path dir : children.filter(Files::isDirectory).toList())
            {
                String name = dir.getFileName().toString();
                if (Files.isRegularFile(dir.resolve(name + MDO_SUFFIX)))
                {
                    nodes.add(new SubsystemNode(name, readLevel(dir.resolve(SUBSYSTEMS))));
                }
            }
        }
        catch (IOException e)
        {
            return List.of();
        }
        nodes.sort(Comparator.comparing(SubsystemNode::name));
        return nodes;
    }
}
