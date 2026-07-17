/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

import ru.jimmo.edt.sonarq.core.checks.DiagnosticCategories;

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
}
