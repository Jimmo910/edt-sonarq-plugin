/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.scope;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Test;

/** Tests for {@link SubsystemTreeReader}. */
public class SubsystemTreeReaderTest
{
    private Path tempDir;

    @After
    public void tearDown() throws IOException
    {
        if (tempDir == null)
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(tempDir))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    public void readsNestedSubsystemTree() throws Exception
    {
        tempDir = Files.createTempDirectory("subsys");
        mkSubsystem(tempDir, "src/Subsystems/ЮТДвижок");
        mkSubsystem(tempDir, "src/Subsystems/ЮТДвижок/Subsystems/ЮТИсполнитель");
        mkSubsystem(tempDir, "src/Subsystems/Общий");

        List<SubsystemNode> tree = SubsystemTreeReader.read(tempDir);

        assertEquals(List.of("Общий", "ЮТДвижок"), tree.stream().map(SubsystemNode::name).toList());
        SubsystemNode engine = tree.get(1);
        assertEquals(List.of("ЮТИсполнитель"), engine.children().stream().map(SubsystemNode::name).toList());
    }

    @Test
    public void missingSubsystemsDirYieldsEmptyList() throws Exception
    {
        tempDir = Files.createTempDirectory("empty");

        assertTrue(SubsystemTreeReader.read(tempDir).isEmpty());
    }

    private static void mkSubsystem(Path root, String rel) throws IOException
    {
        Path dir = Files.createDirectories(root.resolve(rel));
        String name = dir.getFileName().toString();
        Files.writeString(dir.resolve(name + ".mdo"), "<mdclass:Subsystem/>", StandardCharsets.UTF_8);
    }
}
