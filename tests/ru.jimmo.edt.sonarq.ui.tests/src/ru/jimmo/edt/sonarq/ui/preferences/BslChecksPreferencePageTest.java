/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.ui.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.junit.Test;

import com.google.gson.JsonSyntaxException;

import ru.jimmo.edt.sonarq.core.checks.CategoryEntry;
import ru.jimmo.edt.sonarq.core.checks.DiagnosticCategories;
import ru.jimmo.edt.sonarq.core.checks.DiagnosticCategory;
import ru.jimmo.edt.sonarq.core.localanalysis.DiagnosticsCatalog;
import ru.jimmo.edt.sonarq.ui.Messages;

/** Tests for the pure (SWT-free) parts of {@link BslChecksPreferencePage}. */
public class BslChecksPreferencePageTest
{
    /** A stand-in state directory; the fetch stubs below never touch the file system. */
    private static final Path STATE_DIR = Path.of("state");

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

    @Test
    public void descriptionBodyIncludesNameTypeTagsAndDescriptionWhenPresent()
    {
        BslChecksPreferencePage.DiagKey diagKey = new BslChecksPreferencePage.DiagKey("UnusedLocalVariable",
            "Unused local variable", DiagnosticCategory.GENERAL, null, "Code smell", List.of("clumsy", "standard"));

        String body = BslChecksPreferencePage.descriptionBody(diagKey, "This variable is never used.");

        assertTrue(body.contains("Unused local variable"));
        assertTrue(body.contains("Code smell"));
        assertTrue(body.contains("clumsy, standard"));
        assertTrue(body.contains("This variable is never used."));
        assertFalse(body.contains(Messages.BslChecksPage_Description_Empty));
    }

    @Test
    public void descriptionBodyFallsBackToEmptyHintWhenDescriptionIsBlankOrNull()
    {
        BslChecksPreferencePage.DiagKey diagKey = new BslChecksPreferencePage.DiagKey("MethodSize", "Method size",
            DiagnosticCategory.GENERAL, null, "Code smell", List.of());

        String blankBody = BslChecksPreferencePage.descriptionBody(diagKey, "   ");
        String nullBody = BslChecksPreferencePage.descriptionBody(diagKey, null);

        assertTrue(blankBody.contains(Messages.BslChecksPage_Description_Empty));
        assertTrue(nullBody.contains(Messages.BslChecksPage_Description_Empty));
    }

    @Test
    public void descriptionBodyUsesNoTagsLabelWhenDiagnosticHasNoTags()
    {
        BslChecksPreferencePage.DiagKey diagKey = new BslChecksPreferencePage.DiagKey("MethodSize", "Method size",
            DiagnosticCategory.GENERAL, null, "Code smell", List.of());

        String body = BslChecksPreferencePage.descriptionBody(diagKey, "Some description.");

        assertTrue(body.contains(Messages.BslChecksPage_NoTags));
    }

    /**
     * A corrupt SARIF report raises an unchecked {@link JsonSyntaxException}, which used to escape the fetch
     * job's {@code IOException}/{@code InterruptedException} catches and surface as a Job-framework error
     * status instead of on the page's status label (review minor M10).
     */
    @Test
    public void fetchOutcomeReportsACorruptReportOnThePageRatherThanEscaping()
    {
        BslChecksPreferencePage.FetchOutcome outcome =
            BslChecksPreferencePage.fetchOutcome(STATE_DIR, new NullProgressMonitor(), (dir, monitor) ->
            {
                throw new JsonSyntaxException("Malformed SARIF report");
            });

        assertFalse(outcome.cancelled());
        assertEquals("Malformed SARIF report", outcome.errorMessage());
        assertTrue(outcome.entries().isEmpty());
    }

    /** The installer's {@code Files.walk} can fail with an unchecked wrapper; that must be reported too. */
    @Test
    public void fetchOutcomeReportsAnUncheckedIoFailure()
    {
        BslChecksPreferencePage.FetchOutcome outcome =
            BslChecksPreferencePage.fetchOutcome(STATE_DIR, new NullProgressMonitor(), (dir, monitor) ->
            {
                throw new UncheckedIOException(new IOException("engine tree vanished"));
            });

        assertFalse(outcome.cancelled());
        assertTrue(outcome.errorMessage().contains("engine tree vanished"));
    }

    /** A failure that carries no message must still say something on the status label. */
    @Test
    public void fetchOutcomeFallsBackToTheExceptionItselfWhenItCarriesNoMessage()
    {
        BslChecksPreferencePage.FetchOutcome outcome =
            BslChecksPreferencePage.fetchOutcome(STATE_DIR, new NullProgressMonitor(), (dir, monitor) ->
            {
                throw new IllegalStateException();
            });

        assertFalse(outcome.cancelled());
        assertTrue(outcome.errorMessage().contains("IllegalStateException"));
    }

    @Test
    public void fetchOutcomeReportsACheckedIoFailure()
    {
        BslChecksPreferencePage.FetchOutcome outcome =
            BslChecksPreferencePage.fetchOutcome(STATE_DIR, new NullProgressMonitor(), (dir, monitor) ->
            {
                throw new IOException("download failed");
            });

        assertFalse(outcome.cancelled());
        assertEquals("download failed", outcome.errorMessage());
    }

    /** Cancellation is not a failure: it must stay a cancelled job status, with nothing on the label. */
    @Test
    public void fetchOutcomeTreatsCancellationAsCancelledNotAsAFailure()
    {
        BslChecksPreferencePage.FetchOutcome outcome =
            BslChecksPreferencePage.fetchOutcome(STATE_DIR, new NullProgressMonitor(), (dir, monitor) ->
            {
                throw new OperationCanceledException();
            });

        assertTrue(outcome.cancelled());
        assertNull(outcome.errorMessage());
    }

    @Test
    public void fetchOutcomeCarriesTheFetchedEntriesOnSuccess()
    {
        List<DiagnosticsCatalog.Entry> fetched =
            List.of(new DiagnosticsCatalog.Entry("MethodSize", "Method size", ""));

        BslChecksPreferencePage.FetchOutcome outcome =
            BslChecksPreferencePage.fetchOutcome(STATE_DIR, new NullProgressMonitor(), (dir, monitor) -> fetched);

        assertFalse(outcome.cancelled());
        assertNull(outcome.errorMessage());
        assertEquals(fetched, outcome.entries());
    }
}
