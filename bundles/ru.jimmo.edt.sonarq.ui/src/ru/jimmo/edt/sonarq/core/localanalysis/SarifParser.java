/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;

import ru.jimmo.edt.sonarq.core.checks.DiagnosticCategories;
import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarIssueType;
import ru.jimmo.edt.sonarq.core.model.SonarRule;
import ru.jimmo.edt.sonarq.core.model.SonarSeverity;

/**
 * Parses SARIF reports produced by a local BSL Language Server analysis run.
 *
 * <p>The report is read with a pull parser ({@link JsonReader}) straight off a {@link Reader}, one token at
 * a time: neither the document text nor a parsed JSON tree is ever materialized. A full-checks run over an
 * ERP-class 1C configuration produces a SARIF file in the hundreds of megabytes, and this parser runs inside
 * the EDT JVM shared with the modeling core - reading such a report into a {@link String} (two bytes per
 * character) and then into an object tree (several times that again) would exhaust the IDE's heap with an
 * {@link OutOfMemoryError}, which, being an {@link Error}, no {@code catch (RuntimeException)} in the job
 * layer would contain. Streaming keeps the resident cost proportional to the issues actually kept, not to
 * the report size.
 *
 * <p>Members are handled in whatever order they arrive: SARIF puts {@code runs[].tool} before
 * {@code runs[].results} in practice, but nothing here relies on that. Nothing needs buffering either - a
 * result's issue type comes from the bundled diagnostic catalog rather than from the run's rule list (see
 * {@link #issueTypeOf}), so results can be emitted as they are read even if the rules arrive afterwards.
 *
 * <p>A malformed or truncated report is reported as a {@link JsonSyntaxException} - the same unchecked
 * failure the previous tree-based implementation raised - so callers see a clean, reportable error rather
 * than a stray {@link IllegalStateException} or a checked I/O failure that has nothing to do with I/O.
 */
public final class SarifParser
{
    /** Passed as the issue limit to parse every result in the report, however many there are. */
    public static final int NO_ISSUE_LIMIT = Integer.MAX_VALUE;

    private static final String FILE_SCHEME_PREFIX = "file://"; //$NON-NLS-1$

    private static final String FILE_SCHEME_ABSOLUTE = "file:///"; //$NON-NLS-1$

    private static final String UNC_PREFIX = "//"; //$NON-NLS-1$

    private static final String DOT_SLASH_PREFIX = "./"; //$NON-NLS-1$

    private static final String SLASH = "/"; //$NON-NLS-1$

    private static final String EMPTY = ""; //$NON-NLS-1$

    private static final String MALFORMED_REPORT = "Malformed SARIF report"; //$NON-NLS-1$

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
     * <p>This overload takes the whole document as a {@link String} and is therefore only appropriate for
     * reports of a known, bounded size (test fixtures, catalog-only reports). Prefer
     * {@link #parse(Path, String, String, int)} for a report an analysis run produced.
     *
     * @param json the SARIF document, not {@code null}
     * @param projectKey the SonarQube project key used to build component keys, not {@code null}
     * @param uriBasePrefix the absolute path prefix to strip from artifact URIs, may be empty or {@code null}
     * @return the parsed report, never {@code null}; empty when {@code runs} is absent or empty
     */
    public static SarifReport parse(String json, String projectKey, String uriBasePrefix)
    {
        try (Reader reader = new StringReader(json))
        {
            return parse(reader, projectKey, uriBasePrefix, NO_ISSUE_LIMIT);
        }
        catch (IOException e)
        {
            // A StringReader never fails on I/O; only a malformed document can get here, and that is
            // already reported as a JsonSyntaxException from #parse(Reader, ...).
            throw new JsonSyntaxException(MALFORMED_REPORT, e);
        }
    }

    /**
     * Streams a SARIF report file into issues and rule descriptions, keeping every result.
     *
     * @param reportFile the SARIF report file, not {@code null}
     * @param projectKey the SonarQube project key used to build component keys, not {@code null}
     * @return the parsed report, never {@code null}; empty when {@code runs} is absent or empty
     * @throws IOException if the file cannot be read
     * @throws JsonSyntaxException if the report is malformed or truncated
     */
    public static SarifReport parse(Path reportFile, String projectKey) throws IOException
    {
        return parse(reportFile, projectKey, EMPTY, NO_ISSUE_LIMIT);
    }

    /**
     * Streams a SARIF report file into issues and rule descriptions, keeping at most {@code maxIssues} of
     * them.
     *
     * <p>The file is read through a buffered {@link Reader} and never materialized as a string; see the
     * class javadoc for why that matters inside the EDT JVM.
     *
     * @param reportFile the SARIF report file, not {@code null}
     * @param projectKey the SonarQube project key used to build component keys, not {@code null}
     * @param uriBasePrefix the absolute path prefix to strip from artifact URIs, may be empty or {@code null}
     * @param maxIssues the maximum number of issues to keep, or {@link #NO_ISSUE_LIMIT} for all of them
     * @return the parsed report, never {@code null}; {@link SarifReport#totalResults()} counts every result
     *     in the file, including those dropped by the limit
     * @throws IOException if the file cannot be read
     * @throws JsonSyntaxException if the report is malformed or truncated
     */
    public static SarifReport parse(Path reportFile, String projectKey, String uriBasePrefix, int maxIssues)
        throws IOException
    {
        try (Reader reader = Files.newBufferedReader(reportFile, StandardCharsets.UTF_8))
        {
            return parse(reader, projectKey, uriBasePrefix, maxIssues);
        }
    }

    /**
     * Streams a SARIF report off a {@link Reader} into issues and rule descriptions, keeping at most
     * {@code maxIssues} of them.
     *
     * <p>Results beyond {@code maxIssues} are skipped token by token but still counted, so the caller can
     * report a truthful "showing first N of M" total without holding on to the surplus.
     *
     * @param reader the SARIF document reader, not {@code null}; not closed by this method
     * @param projectKey the SonarQube project key used to build component keys, not {@code null}
     * @param uriBasePrefix the absolute path prefix to strip from artifact URIs, may be empty or {@code null}
     * @param maxIssues the maximum number of issues to keep, or {@link #NO_ISSUE_LIMIT} for all of them
     * @return the parsed report, never {@code null}; empty when {@code runs} is absent or empty
     * @throws IOException if the reader fails
     * @throws JsonSyntaxException if the report is malformed or truncated
     */
    public static SarifReport parse(Reader reader, String projectKey, String uriBasePrefix, int maxIssues)
        throws IOException
    {
        JsonReader json = new JsonReader(reader);
        ReportBuilder builder = new ReportBuilder(projectKey, uriBasePrefix, maxIssues);
        try
        {
            readRoot(json, builder);
        }
        catch (MalformedJsonException | EOFException | IllegalStateException | NumberFormatException e)
        {
            // Gson reports a broken document as one of these; funnel them all into the single unchecked
            // failure callers already handle, so a corrupt report never escapes as a stray IllegalState.
            throw new JsonSyntaxException(MALFORMED_REPORT, e);
        }
        return builder.build();
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

    private static void readRoot(JsonReader json, ReportBuilder builder) throws IOException
    {
        json.beginObject();
        while (json.hasNext())
        {
            if ("runs".equals(json.nextName())) //$NON-NLS-1$
            {
                readRuns(json, builder);
            }
            else
            {
                json.skipValue();
            }
        }
        json.endObject();
    }

    private static void readRuns(JsonReader json, ReportBuilder builder) throws IOException
    {
        if (!beginArrayOrSkip(json))
        {
            return;
        }
        while (json.hasNext())
        {
            readRun(json, builder);
        }
        json.endArray();
    }

    private static void readRun(JsonReader json, ReportBuilder builder) throws IOException
    {
        json.beginObject();
        while (json.hasNext())
        {
            String member = json.nextName();
            if ("tool".equals(member)) //$NON-NLS-1$
            {
                readTool(json, builder);
            }
            else if ("results".equals(member)) //$NON-NLS-1$
            {
                readResults(json, builder);
            }
            else
            {
                json.skipValue();
            }
        }
        json.endObject();
    }

    private static void readTool(JsonReader json, ReportBuilder builder) throws IOException
    {
        if (!beginObjectOrSkip(json))
        {
            return;
        }
        while (json.hasNext())
        {
            if ("driver".equals(json.nextName())) //$NON-NLS-1$
            {
                readDriver(json, builder);
            }
            else
            {
                json.skipValue();
            }
        }
        json.endObject();
    }

    private static void readDriver(JsonReader json, ReportBuilder builder) throws IOException
    {
        if (!beginObjectOrSkip(json))
        {
            return;
        }
        while (json.hasNext())
        {
            if ("rules".equals(json.nextName())) //$NON-NLS-1$
            {
                readRules(json, builder);
            }
            else
            {
                json.skipValue();
            }
        }
        json.endObject();
    }

    private static void readRules(JsonReader json, ReportBuilder builder) throws IOException
    {
        if (!beginArrayOrSkip(json))
        {
            return;
        }
        while (json.hasNext())
        {
            builder.addRule(readRule(json));
        }
        json.endArray();
    }

    private static SonarRule readRule(JsonReader json) throws IOException
    {
        String id = EMPTY;
        String name = EMPTY;
        String helpUri = EMPTY;
        RuleDescription description = new RuleDescription();
        json.beginObject();
        while (json.hasNext())
        {
            String member = json.nextName();
            if ("id".equals(member)) //$NON-NLS-1$
            {
                id = nextStringOrEmpty(json);
            }
            else if ("name".equals(member)) //$NON-NLS-1$
            {
                name = nextStringOrEmpty(json);
            }
            else if ("helpUri".equals(member)) //$NON-NLS-1$
            {
                helpUri = nextStringOrEmpty(json);
            }
            else if ("fullDescription".equals(member)) //$NON-NLS-1$
            {
                readFullDescription(json, description);
            }
            else
            {
                json.skipValue();
            }
        }
        json.endObject();
        String ruleName = name.isEmpty() ? id : name;
        return new SonarRule(id, ruleName, ruleDescription(description, helpUri));
    }

    /**
     * Reads a rule's {@code fullDescription} object.
     *
     * @param json the pull parser positioned on the member's value, not {@code null}
     * @param description receives the {@code text} and {@code markdown} members, not {@code null}
     * @throws IOException if the reader fails
     */
    private static void readFullDescription(JsonReader json, RuleDescription description) throws IOException
    {
        if (!beginObjectOrSkip(json))
        {
            return;
        }
        while (json.hasNext())
        {
            String member = json.nextName();
            if ("text".equals(member)) //$NON-NLS-1$
            {
                description.text = nextStringOrEmpty(json);
            }
            else if ("markdown".equals(member)) //$NON-NLS-1$
            {
                description.markdown = nextStringOrEmpty(json);
            }
            else
            {
                json.skipValue();
            }
        }
        json.endObject();
    }

    private static String ruleDescription(RuleDescription description, String helpUri)
    {
        String raw = !description.text.isEmpty() ? description.text : description.markdown;
        String html = MarkdownHtml.toHtml(DiagnosticDescription.cleanMarkdown(raw));
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

    /**
     * Streams the {@code results} array, materializing at most the builder's issue limit and skipping - but
     * still counting - the rest.
     *
     * @param json the pull parser positioned on the member's value, not {@code null}
     * @param builder collects the issues, not {@code null}
     * @throws IOException if the reader fails
     */
    private static void readResults(JsonReader json, ReportBuilder builder) throws IOException
    {
        if (!beginArrayOrSkip(json))
        {
            return;
        }
        while (json.hasNext())
        {
            if (builder.wantsMoreIssues())
            {
                builder.addIssue(readResult(json, builder));
            }
            else
            {
                json.skipValue();
            }
            builder.countResult();
        }
        json.endArray();
    }

    private static SonarIssue readResult(JsonReader json, ReportBuilder builder) throws IOException
    {
        String ruleId = EMPTY;
        String level = EMPTY;
        String message = EMPTY;
        ResultLocation location = new ResultLocation();
        json.beginObject();
        while (json.hasNext())
        {
            String member = json.nextName();
            if ("ruleId".equals(member)) //$NON-NLS-1$
            {
                ruleId = nextStringOrEmpty(json);
            }
            else if ("level".equals(member)) //$NON-NLS-1$
            {
                level = nextStringOrEmpty(json);
            }
            else if ("message".equals(member)) //$NON-NLS-1$
            {
                message = readMessageText(json);
            }
            else if ("locations".equals(member)) //$NON-NLS-1$
            {
                readLocations(json, location);
            }
            else
            {
                json.skipValue();
            }
        }
        json.endObject();
        return builder.toIssue(ruleId, level, message, location);
    }

    private static String readMessageText(JsonReader json) throws IOException
    {
        String text = EMPTY;
        if (!beginObjectOrSkip(json))
        {
            return text;
        }
        while (json.hasNext())
        {
            if ("text".equals(json.nextName())) //$NON-NLS-1$
            {
                text = nextStringOrEmpty(json);
            }
            else
            {
                json.skipValue();
            }
        }
        json.endObject();
        return text;
    }

    /**
     * Reads a result's {@code locations} array, keeping the first entry's physical location and skipping
     * any further ones - exactly what the tree-based parser did with {@code locations.get(0)}.
     *
     * @param json the pull parser positioned on the member's value, not {@code null}
     * @param location receives the first location's coordinates, not {@code null}
     * @throws IOException if the reader fails
     */
    private static void readLocations(JsonReader json, ResultLocation location) throws IOException
    {
        if (!beginArrayOrSkip(json))
        {
            return;
        }
        boolean first = true;
        while (json.hasNext())
        {
            if (first)
            {
                readLocation(json, location);
                first = false;
            }
            else
            {
                json.skipValue();
            }
        }
        json.endArray();
    }

    private static void readLocation(JsonReader json, ResultLocation location) throws IOException
    {
        if (!beginObjectOrSkip(json))
        {
            return;
        }
        while (json.hasNext())
        {
            if ("physicalLocation".equals(json.nextName())) //$NON-NLS-1$
            {
                readPhysicalLocation(json, location);
            }
            else
            {
                json.skipValue();
            }
        }
        json.endObject();
    }

    private static void readPhysicalLocation(JsonReader json, ResultLocation location) throws IOException
    {
        if (!beginObjectOrSkip(json))
        {
            return;
        }
        while (json.hasNext())
        {
            String member = json.nextName();
            if ("artifactLocation".equals(member)) //$NON-NLS-1$
            {
                location.uri = readArtifactUri(json);
            }
            else if ("region".equals(member)) //$NON-NLS-1$
            {
                readRegion(json, location);
            }
            else
            {
                json.skipValue();
            }
        }
        json.endObject();
    }

    private static String readArtifactUri(JsonReader json) throws IOException
    {
        String uri = EMPTY;
        if (!beginObjectOrSkip(json))
        {
            return uri;
        }
        while (json.hasNext())
        {
            if ("uri".equals(json.nextName())) //$NON-NLS-1$
            {
                uri = nextStringOrEmpty(json);
            }
            else
            {
                json.skipValue();
            }
        }
        json.endObject();
        return uri;
    }

    private static void readRegion(JsonReader json, ResultLocation location) throws IOException
    {
        if (!beginObjectOrSkip(json))
        {
            return;
        }
        while (json.hasNext())
        {
            String member = json.nextName();
            if ("startLine".equals(member)) //$NON-NLS-1$
            {
                location.line = nextIntOrZero(json);
            }
            else if ("startColumn".equals(member)) //$NON-NLS-1$
            {
                location.column = nextIntOrZero(json);
            }
            else
            {
                json.skipValue();
            }
        }
        json.endObject();
    }

    /**
     * Enters an object value, or consumes it when it is JSON {@code null} - the tree-based parser treated a
     * null member exactly like a missing one.
     *
     * @param json the pull parser positioned on a value, not {@code null}
     * @return {@code true} when the object was entered and must be closed with {@code endObject}
     * @throws IOException if the reader fails
     */
    private static boolean beginObjectOrSkip(JsonReader json) throws IOException
    {
        if (json.peek() != JsonToken.BEGIN_OBJECT)
        {
            json.skipValue();
            return false;
        }
        json.beginObject();
        return true;
    }

    /**
     * Enters an array value, or consumes it when it is JSON {@code null}.
     *
     * @param json the pull parser positioned on a value, not {@code null}
     * @return {@code true} when the array was entered and must be closed with {@code endArray}
     * @throws IOException if the reader fails
     */
    private static boolean beginArrayOrSkip(JsonReader json) throws IOException
    {
        if (json.peek() != JsonToken.BEGIN_ARRAY)
        {
            json.skipValue();
            return false;
        }
        json.beginArray();
        return true;
    }

    private static String nextStringOrEmpty(JsonReader json) throws IOException
    {
        if (json.peek() == JsonToken.NULL)
        {
            json.nextNull();
            return EMPTY;
        }
        return json.nextString();
    }

    private static int nextIntOrZero(JsonReader json) throws IOException
    {
        if (json.peek() == JsonToken.NULL)
        {
            json.nextNull();
            return 0;
        }
        return json.nextInt();
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

    /** A rule's {@code fullDescription} members, filled in as the pull parser walks them. */
    private static final class RuleDescription
    {
        private String text = EMPTY;

        private String markdown = EMPTY;
    }

    /** The coordinates of a result's first physical location, filled in as the pull parser walks it. */
    private static final class ResultLocation
    {
        private String uri = EMPTY;

        private int line;

        private int column;
    }

    /**
     * Accumulates one report as it streams past: the rule catalog, the issues kept within the limit, and
     * the count of every result seen.
     */
    private static final class ReportBuilder
    {
        private final String projectKey;

        private final String uriBasePrefix;

        private final int maxIssues;

        private final DiagnosticCategories categories = DiagnosticCategories.load();

        private final List<SonarIssue> issues = new ArrayList<>();

        private final Map<String, SonarRule> rules = new LinkedHashMap<>();

        private final Set<String> usedKeys = new HashSet<>();

        private int totalResults;

        ReportBuilder(String projectKey, String uriBasePrefix, int maxIssues)
        {
            this.projectKey = projectKey;
            this.uriBasePrefix = uriBasePrefix;
            this.maxIssues = maxIssues;
        }

        boolean wantsMoreIssues()
        {
            return issues.size() < maxIssues;
        }

        void addRule(SonarRule rule)
        {
            rules.put(rule.key(), rule);
        }

        void addIssue(SonarIssue issue)
        {
            issues.add(issue);
        }

        void countResult()
        {
            totalResults++;
        }

        SonarIssue toIssue(String ruleId, String level, String message, ResultLocation location)
        {
            SonarSeverity severity = severityFromLevel(level);
            SonarIssueType type = issueTypeOf(categories.typeOf(ruleId));
            String uri = normalizeUri(location.uri, uriBasePrefix);
            String componentKey = projectKey + ":" + uri; //$NON-NLS-1$
            String key = uniqueKey(ruleId + ":" + uri + ":" + location.line //$NON-NLS-1$ //$NON-NLS-2$
                + ":" + location.column, usedKeys); //$NON-NLS-1$
            return new SonarIssue(key, ruleId, severity, type, componentKey, message, location.line);
        }

        SarifReport build()
        {
            return new SarifReport(issues, rules, totalResults);
        }
    }
}
