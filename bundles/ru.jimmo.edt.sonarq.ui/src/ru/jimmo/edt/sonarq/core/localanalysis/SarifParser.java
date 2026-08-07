/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.jimmo.edt.sonarq.core.checks.DiagnosticCategories;
import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarIssueType;
import ru.jimmo.edt.sonarq.core.model.SonarRule;
import ru.jimmo.edt.sonarq.core.model.SonarSeverity;

/** Parses SARIF reports produced by a local BSL Language Server analysis run. */
public final class SarifParser
{
    private static final String FILE_SCHEME_PREFIX = "file://"; //$NON-NLS-1$

    private static final String FILE_SCHEME_ABSOLUTE = "file:///"; //$NON-NLS-1$

    private static final String UNC_PREFIX = "//"; //$NON-NLS-1$

    private static final String DOT_SLASH_PREFIX = "./"; //$NON-NLS-1$

    private static final String SLASH = "/"; //$NON-NLS-1$

    private static final String EMPTY = ""; //$NON-NLS-1$

    /** The BSL Language Server diagnostic type that maps onto {@link SonarIssueType#BUG}. */
    private static final String BSL_TYPE_ERROR = "Error"; //$NON-NLS-1$

    /** The BSL Language Server diagnostic type that maps onto {@link SonarIssueType#VULNERABILITY}. */
    private static final String BSL_TYPE_VULNERABILITY = "Vulnerability"; //$NON-NLS-1$

    /** The BSL Language Server diagnostic type reported as a potential security weakness. */
    private static final String BSL_TYPE_SECURITY_HOTSPOT = "Security Hotspot"; //$NON-NLS-1$

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
     * <p>Each result's {@link SonarIssueType} is derived from the bundled diagnostic catalog (see
     * {@link #issueTypeOf}); the catalog is loaded once per call, never per result.
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
            DiagnosticCategories categories = DiagnosticCategories.load();
            for (JsonElement runElement : runs)
            {
                JsonObject run = runElement.getAsJsonObject();
                rules.putAll(parseRules(run));
                issues.addAll(parseResults(run, projectKey, uriBasePrefix, categories));
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
        String html = MarkdownHtml.toHtml(DiagnosticDescription.cleanMarkdown(description));
        String helpUri = asString(ruleObject, "helpUri"); //$NON-NLS-1$
        if (helpUri.isEmpty() || !hasSafeScheme(helpUri))
        {
            return html;
        }
        return html + "<p><a href=\"" + escapeAttribute(helpUri) //$NON-NLS-1$
            + "\">Documentation</a></p>"; //$NON-NLS-1$
    }

    private static boolean hasSafeScheme(String url)
    {
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") //$NON-NLS-1$ //$NON-NLS-2$
            || lower.startsWith("mailto:"); //$NON-NLS-1$
    }

    private static String escapeAttribute(String value)
    {
        return value.replace("&", "&amp;") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("\"", "&quot;") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("<", "&lt;") //$NON-NLS-1$ //$NON-NLS-2$
            .replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static List<SonarIssue> parseResults(JsonObject run, String projectKey, String uriBasePrefix,
        DiagnosticCategories categories)
    {
        List<SonarIssue> issues = new ArrayList<>();
        JsonArray results = run.getAsJsonArray("results"); //$NON-NLS-1$
        if (results != null)
        {
            Set<String> usedKeys = new HashSet<>();
            for (JsonElement element : results)
            {
                issues.add(parseResult(element.getAsJsonObject(), projectKey, uriBasePrefix, categories, usedKeys));
            }
        }
        return issues;
    }

    private static SonarIssue parseResult(JsonObject result, String projectKey, String uriBasePrefix,
        DiagnosticCategories categories, Set<String> usedKeys)
    {
        String ruleId = asString(result, "ruleId"); //$NON-NLS-1$
        String message = asMessage(result);
        SonarSeverity severity = severityFromLevel(asString(result, "level")); //$NON-NLS-1$
        SonarIssueType type = issueTypeOf(categories.typeOf(ruleId));
        JsonObject physicalLocation = firstPhysicalLocation(result);
        String uri = normalizeUri(locationUri(physicalLocation), uriBasePrefix);
        int line = locationLine(physicalLocation);
        String componentKey = projectKey + ":" + uri; //$NON-NLS-1$
        String key = uniqueKey(ruleId + ":" + uri + ":" + line //$NON-NLS-1$ //$NON-NLS-2$
            + ":" + locationColumn(physicalLocation), usedKeys); //$NON-NLS-1$
        return new SonarIssue(key, ruleId, severity, type, componentKey, message, line);
    }

    /**
     * Makes an issue key unique within one report.
     *
     * <p>A local-analysis key is synthesized from the finding's own coordinates - there is no server-side
     * issue id to use - and the rule, file and line alone do not identify a finding: one line routinely
     * carries several results of the same rule (e.g. {@code MissingSpace} around each of two operators).
     * Duplicate keys break everything that treats a key as an identity: the Problems-view quick fix hands
     * its marker's key to the issue view, which then renumbers whichever of the colliding issues it finds
     * first, and the suppression bookkeeping cannot tell them apart. The column disambiguates all realistic
     * collisions; the counter suffix is the belt-and-braces guarantee for a report that still repeats a
     * position, so uniqueness is a property of this method rather than an assumption about the analyzer.
     *
     * @param candidate the key built from the finding's coordinates, not {@code null}
     * @param usedKeys the keys already handed out for this report, mutated by this call, not {@code null}
     * @return {@code candidate} when it is still free, otherwise {@code candidate} with a {@code #<n>}
     *     occurrence suffix
     */
    private static String uniqueKey(String candidate, Set<String> usedKeys)
    {
        if (usedKeys.add(candidate))
        {
            return candidate;
        }
        int occurrence = 2;
        String key = candidate + "#" + occurrence; //$NON-NLS-1$
        while (!usedKeys.add(key))
        {
            occurrence++;
            key = candidate + "#" + occurrence; //$NON-NLS-1$
        }
        return key;
    }

    /**
     * Maps a BSL Language Server diagnostic type onto the SonarQube issue type the view filters by.
     *
     * <p>A local run's SARIF results carry no issue type of their own, so before this mapping every local
     * issue was reported as a {@link SonarIssueType#CODE_SMELL} and the view's Type filter had nothing to
     * filter. The type comes from the bundled diagnostic catalog instead (see
     * {@link DiagnosticCategories#typeOf}), which records the language server's own classification.
     *
     * <p>{@code Security Hotspot} maps onto {@link SonarIssueType#VULNERABILITY}: SonarQube models hotspots
     * as a type of their own, which this plug-in's enum - built from the server Web API's
     * {@code BUG|VULNERABILITY|CODE_SMELL} - does not have, and the security bucket is the closest and the
     * only one that keeps such a diagnostic visible under a security-oriented filter. An unknown or missing
     * type (a rule the bundled catalog does not list, e.g. one added by a newer language server) falls back
     * to {@link SonarIssueType#CODE_SMELL}, the pre-existing behavior for every diagnostic.
     *
     * @param bslType the bundled catalog's diagnostic type, may be empty or {@code null}
     * @return the mapped issue type, never {@code null}
     */
    static SonarIssueType issueTypeOf(String bslType)
    {
        if (BSL_TYPE_ERROR.equalsIgnoreCase(bslType))
        {
            return SonarIssueType.BUG;
        }
        if (BSL_TYPE_VULNERABILITY.equalsIgnoreCase(bslType) || BSL_TYPE_SECURITY_HOTSPOT.equalsIgnoreCase(bslType))
        {
            return SonarIssueType.VULNERABILITY;
        }
        return SonarIssueType.CODE_SMELL;
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

    /**
     * The 1-based start column of a result's region - only ever used to tell two findings of the same rule
     * on the same line apart (see {@link #uniqueKey}), never shown or navigated to.
     *
     * @param physicalLocation the result's physical location, may be {@code null}
     * @return the region's {@code startColumn}, or {@code 0} when there is no region or no column
     */
    private static int locationColumn(JsonObject physicalLocation)
    {
        if (physicalLocation == null)
        {
            return 0;
        }
        JsonObject region = physicalLocation.getAsJsonObject("region"); //$NON-NLS-1$
        return region != null ? asInt(region, "startColumn", 0) : 0; //$NON-NLS-1$
    }

    /**
     * Maps a SARIF result level onto a SonarQube severity.
     *
     * <p>An absent level is <em>not</em> the lowest severity: the SARIF 2.1.0 specification defines
     * {@code warning} as the default value of {@code result.level}, so a result that omits it is a warning
     * and maps to {@link SonarSeverity#MAJOR}. Only a level that is present but unrecognized (including
     * SARIF's own {@code none}) degrades to {@link SonarSeverity#INFO}.
     *
     * @param level the raw {@code result.level} value, {@code ""} when the member is absent
     * @return the mapped severity, never {@code null}
     */
    private static SonarSeverity severityFromLevel(String level)
    {
        if ("error".equals(level)) //$NON-NLS-1$
        {
            return SonarSeverity.CRITICAL;
        }
        if ("warning".equals(level) || level.isEmpty()) //$NON-NLS-1$
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
        String normalized = percentDecode(uri).replace('\\', '/');
        if (normalized.startsWith(FILE_SCHEME_ABSOLUTE))
        {
            normalized = stripFileScheme(normalized);
        }
        else if (normalized.startsWith(FILE_SCHEME_PREFIX))
        {
            // file://host/share/... - a UNC location; keep the leading // so the authority is preserved.
            normalized = UNC_PREFIX + normalized.substring(FILE_SCHEME_PREFIX.length());
        }
        normalized = stripBasePrefix(normalized, uriBasePrefix);
        while (normalized.startsWith(DOT_SLASH_PREFIX))
        {
            normalized = normalized.substring(DOT_SLASH_PREFIX.length());
        }
        return normalized;
    }

    /**
     * Decodes {@code %XX} percent-escapes as UTF-8, leaving every other character (including {@code +})
     * untouched, so SARIF paths that contain spaces or non-ASCII characters map to real file names.
     *
     * @param value the raw URI, not {@code null}
     * @return the decoded value, never {@code null}
     */
    private static String percentDecode(String value)
    {
        if (value.indexOf('%') < 0)
        {
            return value;
        }
        StringBuilder result = new StringBuilder(value.length());
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
            int high = c == '%' && i + 2 < value.length() ? Character.digit(value.charAt(i + 1), 16) : -1;
            int low = high >= 0 ? Character.digit(value.charAt(i + 2), 16) : -1;
            if (high >= 0 && low >= 0)
            {
                pending.write((high << 4) + low);
                i += 2;
            }
            else
            {
                flushDecoded(pending, result);
                // Append the literal character as-is, so a surrogate pair (a non-BMP character) is kept
                // whole rather than each half being byte-encoded separately and corrupted.
                result.append(c);
            }
        }
        flushDecoded(pending, result);
        return result.toString();
    }

    private static void flushDecoded(ByteArrayOutputStream pending, StringBuilder result)
    {
        if (pending.size() > 0)
        {
            result.append(pending.toString(StandardCharsets.UTF_8));
            pending.reset();
        }
    }

    /**
     * Removes the {@code file://} scheme from a forward-slashed URI while preserving the root slash of a
     * POSIX absolute path.
     *
     * <p>A Windows URI {@code file:///E:/...} leaves a spurious leading slash before the drive letter
     * ({@code /E:/...}); that slash is dropped. A POSIX URI {@code file:///home/...} leaves a genuine root
     * slash ({@code /home/...}); it is kept so relativization against an absolute base prefix still works.
     *
     * @param uri the forward-slashed URI starting with {@code file://}, not {@code null}
     * @return the scheme-stripped path, never {@code null}
     */
    private static String stripFileScheme(String uri)
    {
        String path = uri.substring(FILE_SCHEME_PREFIX.length());
        return isWindowsDrivePath(path) ? path.substring(1) : path;
    }

    /**
     * Tells whether a scheme-stripped path has the Windows form {@code /<letter>:/...} left behind by a
     * {@code file:///E:/...} URI.
     *
     * @param path the scheme-stripped path, not {@code null}
     * @return {@code true} when the path starts with a slash, an ASCII drive letter, a colon and a slash
     */
    private static boolean isWindowsDrivePath(String path)
    {
        if (path.length() < 4 || path.charAt(0) != '/' || path.charAt(2) != ':' || path.charAt(3) != '/')
        {
            return false;
        }
        char drive = path.charAt(1);
        return (drive >= 'A' && drive <= 'Z') || (drive >= 'a' && drive <= 'z');
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
        // Only strip when the base ends on a path-segment boundary, so base /work/project does not swallow
        // the front of an unrelated /work/project-old/... path.
        if (!remainder.isEmpty() && !remainder.startsWith(SLASH))
        {
            return uri;
        }
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
