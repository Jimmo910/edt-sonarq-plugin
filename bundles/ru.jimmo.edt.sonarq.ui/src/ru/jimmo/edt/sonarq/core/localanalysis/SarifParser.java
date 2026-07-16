/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarIssueType;
import ru.jimmo.edt.sonarq.core.model.SonarRule;
import ru.jimmo.edt.sonarq.core.model.SonarSeverity;

/** Parses SARIF reports produced by a local BSL Language Server analysis run. */
public final class SarifParser
{
    private static final String FILE_SCHEME_PREFIX = "file:///"; //$NON-NLS-1$

    private static final String DOT_SLASH_PREFIX = "./"; //$NON-NLS-1$

    private static final String SLASH = "/"; //$NON-NLS-1$

    private static final String EMPTY = ""; //$NON-NLS-1$

    private SarifParser()
    {
    }

    /**
     * Parses a SARIF report into issues and rule descriptions, keeping artifact URIs verbatim (apart from
     * stripping a {@code file:///} scheme and a leading {@code ./}).
     *
     * @param json the SARIF document, not {@code null}
     * @param projectKey the SonarQube project key used to build component keys, not {@code null}
     * @return the parsed report, never {@code null}; empty when {@code runs} is absent or empty
     */
    public static SarifReport parse(String json, String projectKey)
    {
        return parse(json, projectKey, EMPTY);
    }

    /**
     * Parses a SARIF report into issues and rule descriptions, relativizing artifact URIs against a base.
     *
     * <p>A real BSL Language Server run emits absolute artifact locations such as
     * {@code file:///E:/proj/TestConfiguration/src/CommonModules/X/Module.bsl}. To turn these into the
     * project-relative paths SonarQube component keys expect ({@code src/CommonModules/X/Module.bsl}),
     * callers pass the project root directory (the parent of the analyzed {@code src} folder) as
     * {@code uriBasePrefix}. The prefix is normalized (back-slashes to slashes, trailing slash removed)
     * and stripped case-insensitively from the front of the scheme-stripped URI, tolerating a Windows
     * drive-letter case difference. An empty or {@code null} prefix leaves URIs unchanged.
     *
     * @param json the SARIF document, not {@code null}
     * @param projectKey the SonarQube project key used to build component keys, not {@code null}
     * @param uriBasePrefix the absolute path prefix to strip from artifact URIs, may be empty or {@code null}
     * @return the parsed report, never {@code null}; empty when {@code runs} is absent or empty
     */
    public static SarifReport parse(String json, String projectKey, String uriBasePrefix)
    {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<SonarIssue> issues = new ArrayList<>();
        Map<String, SonarRule> rules = new LinkedHashMap<>();
        JsonArray runs = root.getAsJsonArray("runs"); //$NON-NLS-1$
        if (runs != null)
        {
            for (JsonElement runElement : runs)
            {
                JsonObject run = runElement.getAsJsonObject();
                rules.putAll(parseRules(run));
                issues.addAll(parseResults(run, projectKey, uriBasePrefix));
            }
        }
        return new SarifReport(issues, rules);
    }

    private static Map<String, SonarRule> parseRules(JsonObject run)
    {
        Map<String, SonarRule> rules = new LinkedHashMap<>();
        JsonObject tool = run.getAsJsonObject("tool"); //$NON-NLS-1$
        JsonObject driver = tool != null ? tool.getAsJsonObject("driver") : null; //$NON-NLS-1$
        JsonArray ruleArray = driver != null ? driver.getAsJsonArray("rules") : null; //$NON-NLS-1$
        if (ruleArray != null)
        {
            for (JsonElement element : ruleArray)
            {
                JsonObject ruleObject = element.getAsJsonObject();
                String id = asString(ruleObject, "id"); //$NON-NLS-1$
                rules.put(id, parseRule(ruleObject, id));
            }
        }
        return rules;
    }

    private static SonarRule parseRule(JsonObject ruleObject, String id)
    {
        String name = asString(ruleObject, "name"); //$NON-NLS-1$
        String ruleName = name.isEmpty() ? id : name;
        return new SonarRule(id, ruleName, ruleDescription(ruleObject));
    }

    private static String ruleDescription(JsonObject ruleObject)
    {
        JsonObject fullDescription = ruleObject.getAsJsonObject("fullDescription"); //$NON-NLS-1$
        String description = ""; //$NON-NLS-1$
        if (fullDescription != null)
        {
            String text = asString(fullDescription, "text"); //$NON-NLS-1$
            description = !text.isEmpty() ? text : asString(fullDescription, "markdown"); //$NON-NLS-1$
        }
        String helpUri = asString(ruleObject, "helpUri"); //$NON-NLS-1$
        if (helpUri.isEmpty())
        {
            return description;
        }
        return description + "<p><a href=\"" + helpUri + "\">Documentation</a></p>"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static List<SonarIssue> parseResults(JsonObject run, String projectKey, String uriBasePrefix)
    {
        List<SonarIssue> issues = new ArrayList<>();
        JsonArray results = run.getAsJsonArray("results"); //$NON-NLS-1$
        if (results != null)
        {
            for (JsonElement element : results)
            {
                issues.add(parseResult(element.getAsJsonObject(), projectKey, uriBasePrefix));
            }
        }
        return issues;
    }

    private static SonarIssue parseResult(JsonObject result, String projectKey, String uriBasePrefix)
    {
        String ruleId = asString(result, "ruleId"); //$NON-NLS-1$
        String message = asMessage(result);
        SonarSeverity severity = severityFromLevel(asString(result, "level")); //$NON-NLS-1$
        JsonObject physicalLocation = firstPhysicalLocation(result);
        String uri = normalizeUri(locationUri(physicalLocation), uriBasePrefix);
        int line = locationLine(physicalLocation);
        String componentKey = projectKey + ":" + uri; //$NON-NLS-1$
        String key = ruleId + ":" + uri + ":" + line; //$NON-NLS-1$ //$NON-NLS-2$
        return new SonarIssue(key, ruleId, severity, SonarIssueType.CODE_SMELL, componentKey, message, line);
    }

    private static String asMessage(JsonObject result)
    {
        JsonObject message = result.getAsJsonObject("message"); //$NON-NLS-1$
        return message != null ? asString(message, "text") : ""; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static JsonObject firstPhysicalLocation(JsonObject result)
    {
        JsonArray locations = result.getAsJsonArray("locations"); //$NON-NLS-1$
        if (locations == null || locations.isEmpty())
        {
            return null;
        }
        JsonObject location = locations.get(0).getAsJsonObject();
        return location.getAsJsonObject("physicalLocation"); //$NON-NLS-1$
    }

    private static String locationUri(JsonObject physicalLocation)
    {
        if (physicalLocation == null)
        {
            return ""; //$NON-NLS-1$
        }
        JsonObject artifactLocation = physicalLocation.getAsJsonObject("artifactLocation"); //$NON-NLS-1$
        return artifactLocation != null ? asString(artifactLocation, "uri") : ""; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static int locationLine(JsonObject physicalLocation)
    {
        if (physicalLocation == null)
        {
            return 0;
        }
        JsonObject region = physicalLocation.getAsJsonObject("region"); //$NON-NLS-1$
        return region != null ? asInt(region, "startLine", 0) : 0; //$NON-NLS-1$
    }

    private static SonarSeverity severityFromLevel(String level)
    {
        if ("error".equals(level)) //$NON-NLS-1$
        {
            return SonarSeverity.CRITICAL;
        }
        if ("warning".equals(level)) //$NON-NLS-1$
        {
            return SonarSeverity.MAJOR;
        }
        if ("note".equals(level)) //$NON-NLS-1$
        {
            return SonarSeverity.MINOR;
        }
        return SonarSeverity.INFO;
    }

    private static String normalizeUri(String uri, String uriBasePrefix)
    {
        String normalized = uri.replace('\\', '/');
        if (normalized.startsWith(FILE_SCHEME_PREFIX))
        {
            normalized = normalized.substring(FILE_SCHEME_PREFIX.length());
        }
        normalized = stripBasePrefix(normalized, uriBasePrefix);
        while (normalized.startsWith(DOT_SLASH_PREFIX))
        {
            normalized = normalized.substring(DOT_SLASH_PREFIX.length());
        }
        return normalized;
    }

    /**
     * Strips the analysis base directory from the front of a scheme-stripped URI, yielding a
     * project-relative path.
     *
     * @param uri the scheme-stripped, forward-slashed URI, not {@code null}
     * @param uriBasePrefix the base path prefix to remove, may be empty or {@code null}
     * @return the path with the base prefix and any leading slashes removed, never {@code null}
     */
    private static String stripBasePrefix(String uri, String uriBasePrefix)
    {
        if (uriBasePrefix == null || uriBasePrefix.isEmpty())
        {
            return uri;
        }
        String base = uriBasePrefix.replace('\\', '/');
        while (base.endsWith(SLASH))
        {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty() || uri.length() < base.length() || !uri.regionMatches(true, 0, base, 0, base.length()))
        {
            return uri;
        }
        String remainder = uri.substring(base.length());
        while (remainder.startsWith(SLASH))
        {
            remainder = remainder.substring(1);
        }
        return remainder;
    }

    private static String asString(JsonObject object, String member)
    {
        JsonElement value = object.get(member);
        return value != null && !value.isJsonNull() ? value.getAsString() : ""; //$NON-NLS-1$
    }

    private static int asInt(JsonObject object, String member, int defaultValue)
    {
        JsonElement value = object.get(member);
        return value != null && !value.isJsonNull() ? value.getAsInt() : defaultValue;
    }
}
