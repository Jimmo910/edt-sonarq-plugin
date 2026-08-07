/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.suppress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.Test;

import ru.jimmo.edt.sonarq.core.model.IssueQuery;
import ru.jimmo.edt.sonarq.core.model.IssueSnapshot;
import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarIssueType;
import ru.jimmo.edt.sonarq.core.model.SonarSeverity;

/** Tests for {@link SuppressionLineShift}. */
public class SuppressionLineShiftTest
{
    private static final String FILE_A = "proj:src/CommonModules/A/Module.bsl";
    private static final String FILE_B = "proj:src/CommonModules/B/Module.bsl";

    private static SonarIssue issue(String key, String componentKey, int line)
    {
        return issue(key, "rule", componentKey, line);
    }

    private static SonarIssue issue(String key, String ruleKey, String componentKey, int line)
    {
        return new SonarIssue(key, ruleKey, SonarSeverity.MAJOR, SonarIssueType.CODE_SMELL, componentKey,
            "message", line);
    }

    @Test
    public void removesSuppressedIssueAndShiftsSameFileLines()
    {
        SonarIssue suppressed = issue("k-suppressed", FILE_A, 10);
        SonarIssue above = issue("k-above", FILE_A, 5);
        SonarIssue atCodeLine = issue("k-at", "otherRule", FILE_A, 10);
        SonarIssue below = issue("k-below", FILE_A, 20);
        SonarIssue otherFile = issue("k-other-file", FILE_B, 10);
        List<SonarIssue> issues = List.of(suppressed, above, atCodeLine, below, otherFile);

        List<SonarIssue> result = SuppressionLineShift.applyAfterSuppress(issues, suppressed);

        assertEquals(4, result.size());
        assertFalse(result.stream().anyMatch(i -> i.key().equals("k-suppressed")));
        assertEquals(5, lineOf(result, "k-above"));
        assertEquals(11, lineOf(result, "k-at"));
        assertEquals(22, lineOf(result, "k-below"));
        assertEquals(10, lineOf(result, "k-other-file"));
    }

    /**
     * The off/on pair disables the rule for the whole wrapped line, so a second finding of the same rule on
     * that line is gone from the next analysis too and must not be left behind as a phantom issue. Before
     * the local-analysis keys carried the column, those two findings shared one key and both fell out of the
     * list as "the suppressed issue"; now that they are distinguishable, the rule/line match is what has to
     * drop them.
     */
    @Test
    public void dropsEveryFindingOfTheSuppressedRuleOnTheSuppressedLine()
    {
        SonarIssue suppressed = issue("MissingSpace:src/M.bsl:10:4", "MissingSpace", FILE_A, 10);
        SonarIssue sibling = issue("MissingSpace:src/M.bsl:10:20", "MissingSpace", FILE_A, 10);
        SonarIssue sameRuleOtherLine = issue("MissingSpace:src/M.bsl:12:4", "MissingSpace", FILE_A, 12);
        SonarIssue sameRuleOtherFile = issue("MissingSpace:src/N.bsl:10:4", "MissingSpace", FILE_B, 10);

        List<SonarIssue> result = SuppressionLineShift.applyAfterSuppress(
            List.of(suppressed, sibling, sameRuleOtherLine, sameRuleOtherFile), suppressed);

        assertEquals(2, result.size());
        assertFalse(result.stream().anyMatch(i -> i.key().equals("MissingSpace:src/M.bsl:10:20")));
        assertEquals(14, lineOf(result, "MissingSpace:src/M.bsl:12:4"));
        assertEquals(10, lineOf(result, "MissingSpace:src/N.bsl:10:4"));
    }

    /** Server-mode rule keys carry a {@code bsl:} prefix; the same line is still the same suppression. */
    @Test
    public void matchesTheSuppressedRuleAcrossTheServerModePrefix()
    {
        SonarIssue suppressed = issue("k1", "bsl:MissingSpace", FILE_A, 10);
        SonarIssue sibling = issue("k2", "MissingSpace", FILE_A, 10);

        List<SonarIssue> result = SuppressionLineShift.applyAfterSuppress(List.of(suppressed, sibling), suppressed);

        assertEquals(List.of(), result);
    }

    @Test
    public void otherFieldsAreUnchangedOnAShiftedIssue()
    {
        SonarIssue suppressed = issue("k-suppressed", FILE_A, 10);
        SonarIssue shifted = issue("k-shifted", FILE_A, 15);
        List<SonarIssue> issues = List.of(suppressed, shifted);

        List<SonarIssue> result = SuppressionLineShift.applyAfterSuppress(issues, suppressed);

        assertEquals(1, result.size());
        SonarIssue out = result.get(0);
        assertEquals("k-shifted", out.key());
        assertEquals("rule", out.ruleKey());
        assertEquals(SonarSeverity.MAJOR, out.severity());
        assertEquals(SonarIssueType.CODE_SMELL, out.type());
        assertEquals(FILE_A, out.componentKey());
        assertEquals("message", out.message());
        assertEquals(17, out.line());
    }

    @Test
    public void issueWithNoMatchingKeyIsNotRemoved()
    {
        SonarIssue only = issue("k-only", FILE_A, 3);
        SonarIssue missing = issue("k-missing", "otherRule", FILE_A, 10);

        List<SonarIssue> result = SuppressionLineShift.applyAfterSuppress(List.of(only), missing);

        assertEquals(List.of(only), result);
    }

    /**
     * The arithmetic both models share: the Problems-view quick fix renumbers workspace markers with it even
     * when the issue view is closed, and the view renumbers its snapshot with it.
     */
    @Test
    public void shiftedLineLeavesLinesAboveTheSuppressionAlone()
    {
        assertEquals(1, SuppressionLineShift.shiftedLine(1, 10));
        assertEquals(9, SuppressionLineShift.shiftedLine(9, 10));
    }

    /** The wrapped line itself only moves down by the {@code -off} comment above it. */
    @Test
    public void shiftedLinePushesTheSuppressedLineDownByOne()
    {
        assertEquals(11, SuppressionLineShift.shiftedLine(10, 10));
        assertEquals(2, SuppressionLineShift.shiftedLine(1, 1));
    }

    /** Everything below moves down by both comments. */
    @Test
    public void shiftedLinePushesLinesBelowTheSuppressionDownByTwo()
    {
        assertEquals(13, SuppressionLineShift.shiftedLine(11, 10));
        assertEquals(1002, SuppressionLineShift.shiftedLine(1000, 10));
    }

    /**
     * A truncated snapshot ("Showing first N of M") must still read as truncated after a suppression. The
     * view used to rebuild it with "total = the issues I still hold", which turned every capped result into a
     * complete one at the first suppression and hid the warning until the next refresh.
     */
    @Test
    public void suppressingInATruncatedSnapshotKeepsItTruncated()
    {
        SonarIssue suppressed = issue("k-suppressed", FILE_A, 10);
        IssueSnapshot snapshot = new IssueSnapshot(new IssueQuery("proj", null),
            List.of(suppressed, issue("k-below", FILE_A, 20)), 5000, Instant.EPOCH);

        IssueSnapshot result = SuppressionLineShift.applyAfterSuppress(snapshot, suppressed);

        assertTrue("the result is still only a fraction of what the server has", result.truncated());
        assertEquals("the total loses exactly the silenced findings", 4999, result.serverTotal());
        assertEquals(1, result.issues().size());
        assertEquals(22, lineOf(result.issues(), "k-below"));
        assertEquals(snapshot.query(), result.query());
        assertEquals(snapshot.loadedAt(), result.loadedAt());
    }

    /** A complete snapshot must not start claiming a truncation the suppression invented. */
    @Test
    public void suppressingInACompleteSnapshotDoesNotInventATruncation()
    {
        SonarIssue suppressed = issue("k-suppressed", FILE_A, 10);
        IssueSnapshot snapshot = new IssueSnapshot(new IssueQuery("proj", null),
            List.of(suppressed, issue("k-below", FILE_A, 20)), 2, Instant.EPOCH);

        IssueSnapshot result = SuppressionLineShift.applyAfterSuppress(snapshot, suppressed);

        assertFalse(result.truncated());
        assertEquals(1, result.serverTotal());
    }

    /**
     * Two findings of the same rule on the wrapped line are silenced by one comment pair, and the total has
     * to account for both - never for fewer issues than the snapshot still holds.
     */
    @Test
    public void theTotalNeverFallsBelowTheIssuesLeft()
    {
        SonarIssue suppressed = issue("k-suppressed", "rule", FILE_A, 10);
        IssueSnapshot snapshot = new IssueSnapshot(new IssueQuery("proj", null),
            List.of(suppressed, issue("k-twin", "rule", FILE_A, 10), issue("k-below", FILE_A, 20)), 3,
            Instant.EPOCH);

        IssueSnapshot result = SuppressionLineShift.applyAfterSuppress(snapshot, suppressed);

        assertEquals(1, result.issues().size());
        assertEquals(1, result.serverTotal());
        assertFalse(result.truncated());
    }

    private static int lineOf(List<SonarIssue> issues, String key)
    {
        return issues.stream().filter(i -> i.key().equals(key)).findFirst().orElseThrow().line();
    }
}
