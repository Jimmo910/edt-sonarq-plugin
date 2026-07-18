/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.suppress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.List;

import org.junit.Test;

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
        return new SonarIssue(key, "rule", SonarSeverity.MAJOR, SonarIssueType.CODE_SMELL, componentKey,
            "message", line);
    }

    @Test
    public void removesSuppressedIssueAndShiftsSameFileLines()
    {
        SonarIssue suppressed = issue("k-suppressed", FILE_A, 10);
        SonarIssue above = issue("k-above", FILE_A, 5);
        SonarIssue atCodeLine = issue("k-at", FILE_A, 10);
        SonarIssue below = issue("k-below", FILE_A, 20);
        SonarIssue otherFile = issue("k-other-file", FILE_B, 10);
        List<SonarIssue> issues = List.of(suppressed, above, atCodeLine, below, otherFile);

        List<SonarIssue> result = SuppressionLineShift.applyAfterSuppress(issues, "k-suppressed", FILE_A, 10);

        assertEquals(4, result.size());
        assertFalse(result.stream().anyMatch(i -> i.key().equals("k-suppressed")));
        assertEquals(5, lineOf(result, "k-above"));
        assertEquals(11, lineOf(result, "k-at"));
        assertEquals(22, lineOf(result, "k-below"));
        assertEquals(10, lineOf(result, "k-other-file"));
    }

    @Test
    public void otherFieldsAreUnchangedOnAShiftedIssue()
    {
        SonarIssue suppressed = issue("k-suppressed", FILE_A, 10);
        SonarIssue shifted = issue("k-shifted", FILE_A, 15);
        List<SonarIssue> issues = List.of(suppressed, shifted);

        List<SonarIssue> result = SuppressionLineShift.applyAfterSuppress(issues, "k-suppressed", FILE_A, 10);

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

        List<SonarIssue> result = SuppressionLineShift.applyAfterSuppress(List.of(only), "k-missing", FILE_A, 10);

        assertEquals(List.of(only), result);
    }

    private static int lineOf(List<SonarIssue> issues, String key)
    {
        return issues.stream().filter(i -> i.key().equals(key)).findFirst().orElseThrow().line();
    }
}
