/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.Test;

import ru.jimmo.edt.sonarq.core.model.BranchInfo;
import ru.jimmo.edt.sonarq.core.model.CeTask;
import ru.jimmo.edt.sonarq.core.model.ComponentInfo;
import ru.jimmo.edt.sonarq.core.model.IssuesPage;
import ru.jimmo.edt.sonarq.core.model.SonarIssueType;
import ru.jimmo.edt.sonarq.core.model.SonarRule;
import ru.jimmo.edt.sonarq.core.model.SonarSeverity;

/** Tests for {@link SonarJsonParser}. */
public class SonarJsonParserTest
{
    private static final String ISSUES_JSON = """
        {
          "paging": { "pageIndex": 1, "pageSize": 500, "total": 2 },
          "issues": [
            {
              "key": "AY1", "rule": "bsl:MethodSize", "severity": "MAJOR", "type": "CODE_SMELL",
              "component": "TestConfiguration:src/CommonModules/Common/Module.bsl",
              "message": "Method is too long", "line": 42
            },
            {
              "key": "AY2", "rule": "bsl:Typo", "severity": "WEIRD", "type": "ODD",
              "component": "TestConfiguration:src/Catalogs/Items/ObjectModule.bsl",
              "message": "File level issue"
            }
          ]
        }""";

    @Test
    public void parsesIssuesPageWithPagingAndFields()
    {
        IssuesPage page = SonarJsonParser.parseIssuesPage(ISSUES_JSON);
        assertEquals(2, page.total());
        assertEquals(1, page.pageIndex());
        assertEquals(500, page.pageSize());
        assertEquals(2, page.issues().size());
        assertEquals("bsl:MethodSize", page.issues().get(0).ruleKey());
        assertEquals(SonarSeverity.MAJOR, page.issues().get(0).severity());
        assertEquals(42, page.issues().get(0).line());
    }

    @Test
    public void unknownSeverityAndTypeFallBackToDefaults()
    {
        IssuesPage page = SonarJsonParser.parseIssuesPage(ISSUES_JSON);
        assertEquals(SonarSeverity.INFO, page.issues().get(1).severity());
        assertEquals(SonarIssueType.UNKNOWN, page.issues().get(1).type());
        assertEquals(0, page.issues().get(1).line());
    }

    @Test
    public void parsesRule()
    {
        String json = """
            { "rule": { "key": "bsl:MethodSize", "name": "Method size", "htmlDesc": "<p>Too long</p>" } }""";
        SonarRule rule = SonarJsonParser.parseRule(json);
        assertEquals("bsl:MethodSize", rule.key());
        assertEquals("Method size", rule.name());
        assertEquals("<p>Too long</p>", rule.htmlDescription());
    }

    @Test
    public void parsesBranches()
    {
        String json = """
            { "branches": [ { "name": "main", "isMain": true }, { "name": "feature/x", "isMain": false } ] }""";
        List<BranchInfo> branches = SonarJsonParser.parseBranches(json);
        assertEquals(2, branches.size());
        assertTrue(branches.get(0).main());
        assertFalse(branches.get(1).main());
        assertEquals("feature/x", branches.get(1).name());
    }

    @Test
    public void parsesComponents()
    {
        String json = """
            { "components": [ { "key": "TestConfiguration", "name": "Test Configuration" } ] }""";
        List<ComponentInfo> components = SonarJsonParser.parseComponents(json);
        assertEquals(1, components.size());
        assertEquals("TestConfiguration", components.get(0).key());
    }

    @Test
    public void parsesLanguages()
    {
        String json = """
            { "languages": [ { "key": "bsl", "name": "BSL" }, { "key": "java", "name": "Java" } ] }""";
        Set<String> languages = SonarJsonParser.parseLanguages(json);
        assertEquals(2, languages.size());
        assertTrue(languages.contains("bsl"));
        assertTrue(languages.contains("java"));
    }

    @Test
    public void parsesLanguagesEmptyWhenArrayMissing()
    {
        Set<String> languages = SonarJsonParser.parseLanguages("{}");
        assertTrue(languages.isEmpty());
    }

    @Test
    public void parsesCeTaskSuccessWithoutErrorMessage()
    {
        String json = """
            { "task": { "status": "SUCCESS" } }""";
        CeTask task = SonarJsonParser.parseCeTask(json);
        assertEquals("SUCCESS", task.status());
        assertEquals("", task.errorMessage());
        assertTrue(task.terminal());
        assertTrue(task.success());
    }

    @Test
    public void parsesCeTaskFailedWithErrorMessage()
    {
        String json = """
            { "task": { "status": "FAILED", "errorMessage": "boom" } }""";
        CeTask task = SonarJsonParser.parseCeTask(json);
        assertEquals("FAILED", task.status());
        assertEquals("boom", task.errorMessage());
        assertTrue(task.terminal());
        assertFalse(task.success());
    }

    @Test
    public void ceTaskInProgressIsNotTerminal()
    {
        String json = """
            { "task": { "status": "IN_PROGRESS" } }""";
        CeTask task = SonarJsonParser.parseCeTask(json);
        assertFalse(task.terminal());
        assertFalse(task.success());
    }
}
