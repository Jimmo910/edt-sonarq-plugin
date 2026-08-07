/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.suppress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.junit.Test;

import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarIssueType;
import ru.jimmo.edt.sonarq.core.model.SonarSeverity;

/**
 * Model-level regression tests for the quick-suppress desynchronization: the issue view keeps its own
 * in-memory line numbers between refreshes and renumbers them with {@link SuppressionLineShift} after each
 * suppression, so it must renumber if and only if {@link BslSuppression#insert} really wrote the off/on
 * comment pair.
 *
 * <p>These drive the two pure helpers exactly the way {@code SonarIssuesView#suppressIssue} now chains them
 * (insert, then shift only on {@code true}), without touching the view or SWT.
 */
public class SuppressionDesyncRegressionTest
{
    private static final String FILE_A = "proj:src/CommonModules/A/Module.bsl";

    /** A module whose first statement is already wrapped in an {@code R1} suppression from an earlier run. */
    private static final String ALREADY_SUPPRESSED = "Процедура П()\n"
        + "    // BSLLS:R1-off\n"
        + "    А = 1;\n"
        + "    // BSLLS:R1-on\n"
        + "    Б = 2;\n"
        + "КонецПроцедуры\n";

    /**
     * The defect: suppressing an issue whose line is already wrapped is a no-op on the file, but the view
     * used to shift its remaining issues by +2 anyway. The next issue in that file then carried a line
     * number two lines past its code, and suppressing it wrapped the wrong lines (here: line 5 -&gt; 7,
     * which is not even inside the procedure any more).
     */
    @Test
    public void aNoOpInsertionMustNotShiftTheRemainingIssues() throws Exception
    {
        IDocument document = new Document(ALREADY_SUPPRESSED);
        List<SonarIssue> issues = List.of(issue("k-already", FILE_A, 3), issue("k-next", FILE_A, 5));

        Applied applied = suppress(document, issues, "k-already", "R1");

        assertFalse(applied.inserted());
        assertEquals("no-op suppressions must leave the file alone", ALREADY_SUPPRESSED, document.get());
        assertEquals(issues, applied.issues());
        assertEquals(5, lineOf(applied.issues(), "k-next"));
    }

    /**
     * The end-to-end consequence of the fix: after the no-op, the next suppression in the same file still
     * targets the line its issue actually sits on, and wraps that code and nothing else.
     */
    @Test
    public void theNextSuppressionAfterANoOpStillWrapsTheRightLine() throws Exception
    {
        IDocument document = new Document(ALREADY_SUPPRESSED);
        List<SonarIssue> issues = List.of(issue("k-already", FILE_A, 3), issue("k-next", FILE_A, 5));

        List<SonarIssue> model = suppress(document, issues, "k-already", "R1").issues();
        assertTrue(suppress(document, model, "k-next", "R2").inserted());

        assertEquals("Процедура П()\n"
            + "    // BSLLS:R1-off\n"
            + "    А = 1;\n"
            + "    // BSLLS:R1-on\n"
            + "    // BSLLS:R2-off\n"
            + "    Б = 2;\n"
            + "    // BSLLS:R2-on\n"
            + "КонецПроцедуры\n", document.get());
    }

    /** A real insertion still shifts, so a following suppression in the same file stays on its own code. */
    @Test
    public void aRealInsertionStillShiftsTheRemainingIssues() throws Exception
    {
        IDocument document = new Document("Процедура П()\n    А = 1;\n    Б = 2;\nКонецПроцедуры\n");
        List<SonarIssue> issues = List.of(issue("k-first", FILE_A, 2), issue("k-next", FILE_A, 3));

        Applied applied = suppress(document, issues, "k-first", "R1");

        assertTrue(applied.inserted());
        assertEquals(1, applied.issues().size());
        assertEquals(5, lineOf(applied.issues(), "k-next"));

        assertTrue(suppress(document, applied.issues(), "k-next", "R2").inserted());

        assertEquals("Процедура П()\n"
            + "    // BSLLS:R1-off\n"
            + "    А = 1;\n"
            + "    // BSLLS:R1-on\n"
            + "    // BSLLS:R2-off\n"
            + "    Б = 2;\n"
            + "    // BSLLS:R2-on\n"
            + "КонецПроцедуры\n", document.get());
    }

    /**
     * Mirrors the view's suppress action: insert, and renumber the in-memory issues only when the insertion
     * really happened.
     *
     * @param document the document being edited
     * @param issues the issues the view currently holds
     * @param issueKey the key of the issue being suppressed
     * @param ruleKey the rule to suppress
     * @return whether anything was written, plus the issue list the view would hold afterwards
     * @throws Exception when the line number is out of the document's range
     */
    private static Applied suppress(IDocument document, List<SonarIssue> issues, String issueKey, String ruleKey)
        throws Exception
    {
        SonarIssue target = issues.stream().filter(i -> i.key().equals(issueKey)).findFirst().orElseThrow();
        boolean inserted =
            BslSuppression.insert(document, target.line(), ruleKey, target.lineAnchor()).inserted();
        if (!inserted)
        {
            return new Applied(false, issues);
        }
        return new Applied(true, SuppressionLineShift.applyAfterSuppress(issues, target));
    }

    /**
     * The outcome of one simulated suppression.
     *
     * @param inserted whether the off/on comment pair was written
     * @param issues the issue list held afterwards
     */
    private record Applied(boolean inserted, List<SonarIssue> issues)
    {
    }

    private static SonarIssue issue(String key, String componentKey, int line)
    {
        return new SonarIssue(key, "rule", SonarSeverity.MAJOR, SonarIssueType.CODE_SMELL, componentKey,
            "message", line);
    }

    private static int lineOf(List<SonarIssue> issues, String key)
    {
        return issues.stream().filter(i -> i.key().equals(key)).findFirst().orElseThrow().line();
    }
}
