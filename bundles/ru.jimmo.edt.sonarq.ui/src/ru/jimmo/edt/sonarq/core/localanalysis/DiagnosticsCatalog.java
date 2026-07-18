/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.jimmo.edt.sonarq.core.model.SonarRule;
import ru.jimmo.edt.sonarq.ui.views.RuleHtml;

/**
 * Catalog of BSL diagnostics discovered from a local analysis run, persisted as JSON so a settings UI can
 * list every known diagnostic key/name/description without re-running an analysis.
 */
public final class DiagnosticsCatalog
{
    /** The file name the catalog is conventionally persisted under, inside the plugin state directory. */
    public static final String CATALOG_FILE_NAME = "bsl-diagnostics-catalog.json"; //$NON-NLS-1$

    private static final String KEY_MEMBER = "key"; //$NON-NLS-1$

    private static final String NAME_MEMBER = "name"; //$NON-NLS-1$

    private static final String DESCRIPTION_MEMBER = "description"; //$NON-NLS-1$

    private static final String EMPTY = ""; //$NON-NLS-1$

    private DiagnosticsCatalog()
    {
    }

    /**
     * One diagnostic entry in the catalog.
     *
     * @param key the diagnostic (rule) key, not {@code null}
     * @param name the human-readable diagnostic name, not {@code null}
     * @param description the plain-text rule description, not {@code null}; empty when unknown (e.g. a
     *     catalog written by an older plugin version, before descriptions were persisted)
     */
    public record Entry(String key, String name, String description)
    {
    }

    /**
     * Builds the catalog entries from a parsed SARIF report's rule descriptions, sorted by key.
     *
     * @param report the parsed report, not {@code null}
     * @return the catalog entries sorted by key, never {@code null}
     */
    public static List<Entry> fromReport(SarifReport report)
    {
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, SonarRule> rule : report.rules().entrySet())
        {
            String description = RuleHtml.toPlainText(rule.getValue().htmlDescription());
            entries.add(new Entry(rule.getKey(), rule.getValue().name(), description));
        }
        entries.sort(Comparator.comparing(Entry::key));
        return entries;
    }

    /**
     * Writes the catalog entries as a JSON array ({@code [{"key":..,"name":..,"description":..},...]}) to
     * the given file.
     *
     * @param file the file to write, not {@code null}
     * @param entries the entries to persist, not {@code null}
     * @throws IOException if the file cannot be written
     */
    public static void save(Path file, List<Entry> entries) throws IOException
    {
        JsonArray array = new JsonArray();
        for (Entry entry : entries)
        {
            JsonObject object = new JsonObject();
            object.addProperty(KEY_MEMBER, entry.key());
            object.addProperty(NAME_MEMBER, entry.name());
            object.addProperty(DESCRIPTION_MEMBER, entry.description());
            array.add(object);
        }
        Files.writeString(file, array.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Loads catalog entries previously written by {@link #save(Path, List)}.
     *
     * <p>Never throws: a missing file, or a file that does not hold a valid JSON array of entries, yields
     * an empty list, so a corrupted or absent catalog never blocks a caller (e.g. a settings page) from
     * opening. A catalog written before descriptions were persisted has no {@code description} member;
     * such entries load with an empty description, no migration needed.
     *
     * @param file the file to read, not {@code null}
     * @return the loaded entries, never {@code null}; empty when the file is absent or unreadable
     */
    public static List<Entry> load(Path file)
    {
        try
        {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();
            List<Entry> entries = new ArrayList<>();
            for (JsonElement element : array)
            {
                JsonObject object = element.getAsJsonObject();
                entries.add(new Entry(asString(object, KEY_MEMBER), asString(object, NAME_MEMBER),
                    asString(object, DESCRIPTION_MEMBER)));
            }
            return entries;
        }
        catch (IOException | RuntimeException e)
        {
            return List.of();
        }
    }

    private static String asString(JsonObject object, String member)
    {
        JsonElement value = object.get(member);
        return value != null && !value.isJsonNull() ? value.getAsString() : EMPTY;
    }
}
