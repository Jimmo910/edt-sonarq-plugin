/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.jimmo.edt.sonarq.core.model.BranchInfo;
import ru.jimmo.edt.sonarq.core.model.CeTask;
import ru.jimmo.edt.sonarq.core.model.ComponentInfo;
import ru.jimmo.edt.sonarq.core.model.IssuesPage;
import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarIssueType;
import ru.jimmo.edt.sonarq.core.model.SonarRule;
import ru.jimmo.edt.sonarq.core.model.SonarSeverity;

/** Parses SonarQube Web API JSON payloads into model objects. */
public final class SonarJsonParser
{
    private SonarJsonParser()
    {
    }

    /**
     * Parses one page of {@code /api/issues/search} response.
     *
     * @param json the response body, not {@code null}
     * @return the parsed page, never {@code null}
     */
    public static IssuesPage parseIssuesPage(String json)
    {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject paging = root.getAsJsonObject("paging"); //$NON-NLS-1$
        int total = paging != null ? paging.get("total").getAsInt() : 0; //$NON-NLS-1$
        int pageIndex = paging != null ? paging.get("pageIndex").getAsInt() : 1; //$NON-NLS-1$
        int pageSize = paging != null ? paging.get("pageSize").getAsInt() : 0; //$NON-NLS-1$
        List<SonarIssue> issues = new ArrayList<>();
        JsonArray array = root.getAsJsonArray("issues"); //$NON-NLS-1$
        if (array != null)
        {
            for (JsonElement element : array)
            {
                issues.add(parseIssue(element.getAsJsonObject()));
            }
        }
        return new IssuesPage(issues, total, pageIndex, pageSize);
    }

    /**
     * Parses {@code /api/rules/show} response.
     *
     * @param json the response body, not {@code null}
     * @return the parsed rule, never {@code null}
     */
    public static SonarRule parseRule(String json)
    {
        JsonObject rule = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("rule"); //$NON-NLS-1$
        String key = asString(rule, "key"); //$NON-NLS-1$
        String name = asString(rule, "name"); //$NON-NLS-1$
        return new SonarRule(key, name, asString(rule, "htmlDesc")); //$NON-NLS-1$
    }

    /**
     * Parses {@code /api/project_branches/list} response.
     *
     * @param json the response body, not {@code null}
     * @return the branches, never {@code null}
     */
    public static List<BranchInfo> parseBranches(String json)
    {
        List<BranchInfo> result = new ArrayList<>();
        JsonArray array = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("branches"); //$NON-NLS-1$
        if (array != null)
        {
            for (JsonElement element : array)
            {
                JsonObject branch = element.getAsJsonObject();
                boolean main = branch.has("isMain") && branch.get("isMain").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$
                result.add(new BranchInfo(asString(branch, "name"), main)); //$NON-NLS-1$
            }
        }
        return result;
    }

    /**
     * Parses {@code /api/components/search} response.
     *
     * @param json the response body, not {@code null}
     * @return the components, never {@code null}
     */
    public static List<ComponentInfo> parseComponents(String json)
    {
        List<ComponentInfo> result = new ArrayList<>();
        JsonArray array = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("components"); //$NON-NLS-1$
        if (array != null)
        {
            for (JsonElement element : array)
            {
                JsonObject component = element.getAsJsonObject();
                String key = asString(component, "key"); //$NON-NLS-1$
                result.add(new ComponentInfo(key, asString(component, "name"))); //$NON-NLS-1$
            }
        }
        return result;
    }

    /**
     * Parses {@code /api/languages/list} response.
     *
     * @param json the response body, not {@code null}
     * @return the language keys, never {@code null}; empty if the server reports none
     */
    public static Set<String> parseLanguages(String json)
    {
        Set<String> result = new LinkedHashSet<>();
        JsonArray array = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("languages"); //$NON-NLS-1$
        if (array != null)
        {
            for (JsonElement element : array)
            {
                result.add(asString(element.getAsJsonObject(), "key")); //$NON-NLS-1$
            }
        }
        return result;
    }

    /**
     * Parses {@code /api/ce/task} response.
     *
     * @param json the response body, not {@code null}
     * @return the task status, never {@code null}
     */
    public static CeTask parseCeTask(String json)
    {
        JsonObject task = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("task"); //$NON-NLS-1$
        return new CeTask(asString(task, "status"), asString(task, "errorMessage")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static SonarIssue parseIssue(JsonObject issue)
    {
        return new SonarIssue(
            asString(issue, "key"), //$NON-NLS-1$
            asString(issue, "rule"), //$NON-NLS-1$
            SonarSeverity.fromJson(asString(issue, "severity")), //$NON-NLS-1$
            SonarIssueType.fromJson(asString(issue, "type")), //$NON-NLS-1$
            asString(issue, "component"), //$NON-NLS-1$
            asString(issue, "message"), //$NON-NLS-1$
            issue.has("line") ? issue.get("line").getAsInt() : 0); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String asString(JsonObject object, String member)
    {
        JsonElement value = object.get(member);
        return value != null && !value.isJsonNull() ? value.getAsString() : ""; //$NON-NLS-1$
    }
}
