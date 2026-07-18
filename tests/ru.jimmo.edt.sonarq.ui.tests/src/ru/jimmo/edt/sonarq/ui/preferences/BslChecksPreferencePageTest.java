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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Test;

import ru.jimmo.edt.sonarq.core.checks.CategoryEntry;
import ru.jimmo.edt.sonarq.core.checks.DiagnosticCategories;
import ru.jimmo.edt.sonarq.core.checks.DiagnosticCategory;
import ru.jimmo.edt.sonarq.core.localanalysis.DiagnosticsCatalog;
import ru.jimmo.edt.sonarq.ui.Messages;

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
            new DiagnosticsCatalog.Entry("UnusedLocalVariable", "Unused local variable (fetched name)", ""),
            new DiagnosticsCatalog.Entry("TotallyNewDiagnosticXYZ", "Totally New Diagnostic", ""));

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
        // A key unknown to the bundled catalog has no known type or tags either.
        assertEquals("", fetchedOnly.type());
        assertTrue(fetchedOnly.tags().isEmpty());
    }

    @Test
    public void mergeDisplayedKeysCarriesTypeAndTagsFromBundledCatalog()
    {
        DiagnosticCategories categories = DiagnosticCategories.load();

        List<BslChecksPreferencePage.DiagKey> merged = BslChecksPreferencePage.mergeDisplayedKeys(categories,
            List.of());

        BslChecksPreferencePage.DiagKey unusedLocalVariable = merged.stream()
            .filter(diagKey -> diagKey.key().equals("UnusedLocalVariable")).findFirst().orElseThrow();
        assertEquals(categories.typeOf("UnusedLocalVariable"), unusedLocalVariable.type());
        assertEquals(categories.tagsOf("UnusedLocalVariable"), unusedLocalVariable.tags());
        assertFalse(unusedLocalVariable.type().isEmpty());
    }

    @Test
    public void groupKeysByTypeGroupsBySingleTypeWithCorrectCounts()
    {
        List<BslChecksPreferencePage.DiagKey> diagKeys = List.of(
            new BslChecksPreferencePage.DiagKey("A", "Name A", DiagnosticCategory.GENERAL, null, "Code smell",
                List.of()),
            new BslChecksPreferencePage.DiagKey("B", "Name B", DiagnosticCategory.GENERAL, null, "Error", List.of()),
            new BslChecksPreferencePage.DiagKey("C", "Name C", DiagnosticCategory.GENERAL, null, "Code smell",
                List.of()));

        Map<String, List<String>> byType = BslChecksPreferencePage.groupKeysByType(diagKeys);

        assertEquals(Set.of("Code smell", "Error"), byType.keySet());
        assertEquals(List.of("A", "C"), byType.get("Code smell"));
        assertEquals(List.of("B"), byType.get("Error"));
    }

    @Test
    public void groupKeysByTagPutsMultiTagKeyUnderEachTagAndNoTagKeyUnderNoTagsBucket()
    {
        List<BslChecksPreferencePage.DiagKey> diagKeys = List.of(
            new BslChecksPreferencePage.DiagKey("A", "Name A", DiagnosticCategory.GENERAL, null, "Code smell",
                List.of("clumsy", "standard")),
            new BslChecksPreferencePage.DiagKey("B", "Name B", DiagnosticCategory.GENERAL, null, "Error",
                List.of()));

        Map<String, List<String>> byTag = BslChecksPreferencePage.groupKeysByTag(diagKeys);

        assertEquals(Set.of("clumsy", "standard", Messages.BslChecksPage_NoTags), byTag.keySet());
        assertTrue(byTag.get("clumsy").contains("A"));
        assertTrue(byTag.get("standard").contains("A"));
        assertTrue(byTag.get(Messages.BslChecksPage_NoTags).contains("B"));
        assertFalse(byTag.get("clumsy").contains("B"));
    }
}
