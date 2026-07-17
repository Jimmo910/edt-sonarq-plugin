/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.After;
import org.junit.Test;

/** Tests for {@link BslConfigWriter}. */
public class BslConfigWriterTest
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
    public void nullDisabledKeysYieldsNullAndNoFile() throws IOException
    {
        tempDir = Files.createTempDirectory("bsl-config-writer-test");

        Path result = BslConfigWriter.write(tempDir, null);

        assertNull(result);
        assertTrue(Files.list(tempDir).findAny().isEmpty());
    }

    @Test
    public void emptyDisabledKeysYieldsNull() throws IOException
    {
        tempDir = Files.createTempDirectory("bsl-config-writer-test");

        Path result = BslConfigWriter.write(tempDir, List.of());

        assertNull(result);
    }

    @Test
    public void writesExpectedJsonShapeWithSortedKeys() throws IOException
    {
        tempDir = Files.createTempDirectory("bsl-config-writer-test");

        Path result = BslConfigWriter.write(tempDir, List.of("Typo", "MethodSize"));

        assertEquals(tempDir.resolve("bsl-ls-config.json"), result);
        String json = Files.readString(result, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject parameters = root.getAsJsonObject("diagnostics").getAsJsonObject("parameters");
        assertEquals(false, parameters.get("MethodSize").getAsBoolean());
        assertEquals(false, parameters.get("Typo").getAsBoolean());
        assertEquals(2, parameters.size());

        // Keys must be written in sorted order, not insertion order.
        List<String> memberNames = parameters.keySet().stream().toList();
        assertEquals(List.of("MethodSize", "Typo"), memberNames);
    }

    @Test
    public void repeatedWriteOverwritesPreviousContent() throws IOException
    {
        tempDir = Files.createTempDirectory("bsl-config-writer-test");

        BslConfigWriter.write(tempDir, List.of("First"));
        Path result = BslConfigWriter.write(tempDir, List.of("Second"));

        String json = Files.readString(result, StandardCharsets.UTF_8);
        JsonObject parameters = JsonParser.parseString(json).getAsJsonObject()
            .getAsJsonObject("diagnostics").getAsJsonObject("parameters");
        assertEquals(1, parameters.size());
        assertTrue(parameters.has("Second"));
    }

    @Test
    public void secondWriteWithSameKeysDoesNotModifyFile() throws IOException
    {
        tempDir = Files.createTempDirectory("bsl-config-writer-test");
        Path result = BslConfigWriter.write(tempDir, List.of("Typo", "MethodSize"));
        // Push the timestamp back in time so a skipped write is distinguishable from one that merely
        // completed within the same filesystem timestamp-resolution tick.
        FileTime old = FileTime.fromMillis(Files.getLastModifiedTime(result).toMillis() - 60_000L);
        Files.setLastModifiedTime(result, old);

        Path second = BslConfigWriter.write(tempDir, List.of("MethodSize", "Typo"));

        assertEquals(result, second);
        assertEquals(old, Files.getLastModifiedTime(second));
    }
}
