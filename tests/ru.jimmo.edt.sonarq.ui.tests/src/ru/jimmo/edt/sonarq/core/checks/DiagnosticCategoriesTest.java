/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.checks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Test;

/** Tests for {@link DiagnosticCategories}. */
public class DiagnosticCategoriesTest
{
    @Test
    public void loadsAll186Entries()
    {
        assertEquals(186, DiagnosticCategories.load().all().size());
    }

    @Test
    public void categorizesKnownKeys()
    {
        DiagnosticCategories c = DiagnosticCategories.load();
        assertEquals(DiagnosticCategory.EDT_DUPLICATE, c.categoryOf("UnusedLocalVariable"));
        assertEquals(DiagnosticCategory.NEEDS_TUNING, c.categoryOf("CyclomaticComplexity"));
        assertEquals(DiagnosticCategory.INAPPROPRIATE, c.categoryOf("TernaryOperatorUsage"));
        assertEquals("module-unused-local-variable-check", c.edtCheckOf("UnusedLocalVariable"));
    }

    @Test
    public void unknownKeyIsGeneral()
    {
        DiagnosticCategories c = DiagnosticCategories.load();
        assertEquals(DiagnosticCategory.GENERAL, c.categoryOf("NoSuchDiagnosticXYZ"));
        assertNull(c.edtCheckOf("NoSuchDiagnosticXYZ"));
    }

    @Test
    public void recommendedDisabledFiltersKnown()
    {
        DiagnosticCategories c = DiagnosticCategories.load();
        Set<String> rec =
            c.recommendedDisabledKeys(Set.of("UnusedLocalVariable", "MethodSize", "Typo", "NoSuchXYZ"));
        assertTrue(rec.contains("UnusedLocalVariable"));
        assertTrue(rec.contains("MethodSize"));
        assertFalse(rec.contains("Typo"));
        assertFalse(rec.contains("NoSuchXYZ"));
    }

    @Test
    public void resourceInvariants()
    {
        List<CategoryEntry> all = DiagnosticCategories.load().all();
        Map<DiagnosticCategory, Long> byCat =
            all.stream().collect(Collectors.groupingBy(CategoryEntry::category, Collectors.counting()));

        assertEquals(48L, byCat.get(DiagnosticCategory.EDT_DUPLICATE).longValue());
        assertEquals(13L, byCat.get(DiagnosticCategory.NEEDS_TUNING).longValue());
        assertEquals(7L, byCat.get(DiagnosticCategory.INAPPROPRIATE).longValue());
        assertEquals(118L, byCat.get(DiagnosticCategory.GENERAL).longValue());

        assertEquals(all.size(), all.stream().map(CategoryEntry::key).distinct().count());
        assertTrue(all.stream()
            .allMatch(e -> (e.category() == DiagnosticCategory.EDT_DUPLICATE) == (e.edtCheck() != null)));
    }
}
