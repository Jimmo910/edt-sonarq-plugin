/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

import ru.jimmo.edt.sonarq.core.model.SonarRule;

/** Tests for {@link DiagnosticsCatalog}. */
public class DiagnosticsCatalogTest
{
    private Path tempDir;

    @After
    public void tearDown() throws IOException
    {
        if (tempDir == null)
        {
            return;
        }
        try (var walk = Files.walk(tempDir))
        {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    public void fromReportBuildsSortedEntriesFromRules()
    {
        Map<String, SonarRule> rules = new LinkedHashMap<>();
        rules.put("Typo", new SonarRule("Typo", "Typo rule", ""));
        rules.put("MethodSize", new SonarRule("MethodSize", "Method size", ""));
        SarifReport report = new SarifReport(List.of(), rules);

        List<DiagnosticsCatalog.Entry> entries = DiagnosticsCatalog.fromReport(report);

        assertEquals(2, entries.size());
        assertEquals("MethodSize", entries.get(0).key());
        assertEquals("Method size", entries.get(0).name());
        assertEquals("Typo", entries.get(1).key());
        assertEquals("Typo rule", entries.get(1).name());
    }

    @Test
    public void fromReportOnEmptyRulesYieldsEmptyList()
    {
        SarifReport report = new SarifReport(List.of(), Map.of());

        assertTrue(DiagnosticsCatalog.fromReport(report).isEmpty());
    }

    @Test
    public void saveThenLoadRoundTripsEntries() throws IOException
    {
        tempDir = Files.createTempDirectory("diagnostics-catalog-test");
        Path file = tempDir.resolve(DiagnosticsCatalog.CATALOG_FILE_NAME);
        List<DiagnosticsCatalog.Entry> original = List.of(
            new DiagnosticsCatalog.Entry("MethodSize", "Method size"),
            new DiagnosticsCatalog.Entry("Typo", "Typo rule"));

        DiagnosticsCatalog.save(file, original);
        List<DiagnosticsCatalog.Entry> loaded = DiagnosticsCatalog.load(file);

        assertEquals(original, loaded);
    }

    @Test
    public void loadOfMissingFileYieldsEmptyList()
    {
        Path missing = Path.of("this-file-does-not-exist-" + System.nanoTime() + ".json");

        assertTrue(DiagnosticsCatalog.load(missing).isEmpty());
    }

    @Test
    public void loadOfCorruptFileYieldsEmptyList() throws IOException
    {
        tempDir = Files.createTempDirectory("diagnostics-catalog-test");
        Path file = tempDir.resolve(DiagnosticsCatalog.CATALOG_FILE_NAME);
        Files.writeString(file, "{ not valid json array", StandardCharsets.UTF_8);

        assertTrue(DiagnosticsCatalog.load(file).isEmpty());
    }

    @Test
    public void catalogFileNameConstantIsExpected()
    {
        assertEquals("bsl-diagnostics-catalog.json", DiagnosticsCatalog.CATALOG_FILE_NAME);
    }
}
