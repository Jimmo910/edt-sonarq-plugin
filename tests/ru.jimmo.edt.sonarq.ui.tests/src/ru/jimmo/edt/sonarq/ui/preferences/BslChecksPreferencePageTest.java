/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Test;

import ru.jimmo.edt.sonarq.core.checks.CategoryEntry;
import ru.jimmo.edt.sonarq.core.checks.DiagnosticCategories;
import ru.jimmo.edt.sonarq.core.checks.DiagnosticCategory;
import ru.jimmo.edt.sonarq.core.localanalysis.DiagnosticsCatalog;

/** Tests for the pure (SWT-free) parts of {@link BslChecksPreferencePage}. */
public class BslChecksPreferencePageTest
{
    @Test
    public void recommendedToDisableReturnsExactlyTheRecommendedSubset()
    {
        DiagnosticCategories categories = DiagnosticCategories.load();
        Set<String> displayed = Set.of("UnusedLocalVariable", "MethodSize", "Typo", "NoSuchDiagnosticXYZ");

        Set<String> result = BslChecksPreferencePage.recommendedToDisable(displayed, categories);

        assertEquals(categories.recommendedDisabledKeys(displayed), result);
        assertTrue(result.contains("UnusedLocalVariable"));
        assertTrue(result.contains("MethodSize"));
        assertFalse(result.contains("Typo"));
        assertFalse(result.contains("NoSuchDiagnosticXYZ"));
    }

    @Test
    public void recommendedToDisableIsEmptyWhenNoDisplayedKeyIsKnown()
    {
        DiagnosticCategories categories = DiagnosticCategories.load();

        Set<String> result = BslChecksPreferencePage.recommendedToDisable(Set.of("NoSuchA", "NoSuchB"), categories);

        assertTrue(result.isEmpty());
    }

    @Test
    public void mergeDisplayedKeysAddsFetchedOnlyKeyAsGeneralAndDedupesOverlap()
    {
        DiagnosticCategories categories = DiagnosticCategories.load();
        Set<String> bundledKeys =
            categories.all().stream().map(CategoryEntry::key).collect(Collectors.toSet());
        List<DiagnosticsCatalog.Entry> fetched = List.of(
            new DiagnosticsCatalog.Entry("UnusedLocalVariable", "Unused local variable (fetched name)"),
            new DiagnosticsCatalog.Entry("TotallyNewDiagnosticXYZ", "Totally New Diagnostic"));

        List<BslChecksPreferencePage.DiagKey> merged =
            BslChecksPreferencePage.mergeDisplayedKeys(categories, fetched);

        Set<String> mergedKeys =
            merged.stream().map(BslChecksPreferencePage.DiagKey::key).collect(Collectors.toSet());
        assertTrue(mergedKeys.containsAll(bundledKeys));
        assertTrue(mergedKeys.contains("TotallyNewDiagnosticXYZ"));
        // Every bundled key plus exactly the one fetched-only key: "UnusedLocalVariable" (present in both)
        // must not be duplicated.
        assertEquals(bundledKeys.size() + 1, merged.size());

        BslChecksPreferencePage.DiagKey fetchedOnly = merged.stream()
            .filter(diagKey -> diagKey.key().equals("TotallyNewDiagnosticXYZ")).findFirst().orElseThrow();
        assertEquals(DiagnosticCategory.GENERAL, fetchedOnly.category());
    }
}
