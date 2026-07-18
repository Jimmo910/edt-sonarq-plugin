/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.checks;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Loader for the bundled catalog that maps every known BSL Language Server diagnostic key to a
 * {@link DiagnosticCategory}, with an EDT built-in check id as evidence for duplicates.
 *
 * <p>The catalog is a first-cut, community-refinable classification shipped as a JSON resource inside this
 * bundle; it is not derived from any authoritative EDT/BSL Language Server metadata and is expected to be
 * corrected over time as real-world usage surfaces mistakes.
 */
public final class DiagnosticCategories
{
    private static final String RESOURCE_PATH = "/bsl-diagnostic-categories.json"; //$NON-NLS-1$

    private static final String DIAGNOSTICS_MEMBER = "diagnostics"; //$NON-NLS-1$

    private static final String KEY_MEMBER = "key"; //$NON-NLS-1$

    private static final String NAME_MEMBER = "name"; //$NON-NLS-1$

    private static final String CATEGORY_MEMBER = "category"; //$NON-NLS-1$

    private static final String EDT_CHECK_MEMBER = "edtCheck"; //$NON-NLS-1$

    private final List<CategoryEntry> entries;

    private final Map<String, CategoryEntry> byKey;

    private DiagnosticCategories(List<CategoryEntry> entries)
    {
        this.entries = List.copyOf(entries);
        Map<String, CategoryEntry> map = new HashMap<>();
        for (CategoryEntry entry : entries)
        {
            map.put(entry.key(), entry);
        }
        this.byKey = Collections.unmodifiableMap(map);
    }

    /**
     * Loads the bundled BSL diagnostic category catalog from this bundle's classpath.
     *
     * <p>Never throws: a missing or malformed resource yields an empty instance (every key then resolves
     * to {@link DiagnosticCategory#GENERAL}), so a caller (e.g. a settings page) is never blocked from
     * opening. Re-parses the resource on every call; a caller that reads it repeatedly should cache the
     * result.
     *
     * @return the loaded catalog, never {@code null}; empty when the resource is missing or invalid
     */
    public static DiagnosticCategories load()
    {
        try (InputStream stream = DiagnosticCategories.class.getResourceAsStream(RESOURCE_PATH))
        {
            if (stream == null)
            {
                return new DiagnosticCategories(List.of());
            }
            return parse(stream);
        }
        catch (IOException e)
        {
            return new DiagnosticCategories(List.of());
        }
    }

    /**
     * Parses a diagnostic category catalog document, per-entry resilient: a single malformed diagnostic
     * (missing {@code "key"}/{@code "name"}, or a non-object array element) is skipped rather than fatal,
     * so the rest of a community-contributed catalog still loads. A malformed top-level document (not
     * JSON, or the {@code "diagnostics"} member missing or not an array) still yields an empty instance,
     * same as before. Extracted from {@link #load()} so this per-entry behavior is unit-testable without
     * the bundled resource; does not close {@code in}.
     *
     * @param in the JSON document to parse, not {@code null}
     * @return the parsed catalog, never {@code null}; empty when the document is malformed
     */
    static DiagnosticCategories parse(InputStream in)
    {
        JsonArray diagnostics;
        try
        {
            JsonObject root =
                JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            diagnostics = root.getAsJsonArray(DIAGNOSTICS_MEMBER);
        }
        catch (RuntimeException e)
        {
            return new DiagnosticCategories(List.of());
        }
        if (diagnostics == null)
        {
            return new DiagnosticCategories(List.of());
        }
        List<CategoryEntry> entries = new ArrayList<>();
        for (JsonElement element : diagnostics)
        {
            try
            {
                entries.add(toEntry(element.getAsJsonObject()));
            }
            catch (RuntimeException e)
            {
                // A single malformed entry must not collapse the whole catalog; skip it and keep parsing
                // the rest, so one bad community-contributed entry cannot silently empty the catalog.
            }
        }
        return new DiagnosticCategories(entries);
    }

    private static CategoryEntry toEntry(JsonObject object)
    {
        String key = object.get(KEY_MEMBER).getAsString();
        String name = object.get(NAME_MEMBER).getAsString();
        DiagnosticCategory category =
            DiagnosticCategory.fromResourceId(object.get(CATEGORY_MEMBER).getAsString());
        JsonElement edtCheckElement = object.get(EDT_CHECK_MEMBER);
        String edtCheck =
            edtCheckElement != null && !edtCheckElement.isJsonNull() ? edtCheckElement.getAsString() : null;
        return new CategoryEntry(key, name, category, edtCheck);
    }

    /**
     * The category assigned to a diagnostic key.
     *
     * @param key the diagnostic key, may be {@code null}
     * @return the assigned category, or {@link DiagnosticCategory#GENERAL} when {@code key} is unknown
     */
    public DiagnosticCategory categoryOf(String key)
    {
        CategoryEntry entry = byKey.get(key);
        return entry != null ? entry.category() : DiagnosticCategory.GENERAL;
    }

    /**
     * The bundled human-readable name of a diagnostic key.
     *
     * @param key the diagnostic key, may be {@code null}
     * @return the bundled name, or {@code null} when {@code key} is unknown
     */
    public String nameOf(String key)
    {
        CategoryEntry entry = byKey.get(key);
        return entry != null ? entry.name() : null;
    }

    /**
     * The id of the EDT built-in check a diagnostic key duplicates.
     *
     * @param key the diagnostic key, may be {@code null}
     * @return the EDT check id, or {@code null} when {@code key} is unknown or not categorized as
     *     {@link DiagnosticCategory#EDT_DUPLICATE}
     */
    public String edtCheckOf(String key)
    {
        CategoryEntry entry = byKey.get(key);
        return entry != null ? entry.edtCheck() : null;
    }

    /**
     * All bundled catalog entries.
     *
     * @return the bundled entries, never {@code null}, unmodifiable
     */
    public List<CategoryEntry> all()
    {
        return entries;
    }

    /**
     * The keys in {@code known} whose bundled category is one the plugin recommends disabling.
     *
     * @param known the diagnostic keys to filter, not {@code null}
     * @return the subset of {@code known} whose {@link DiagnosticCategory#recommendedDisabled()} is
     *     {@code true}; never {@code null}
     */
    public Set<String> recommendedDisabledKeys(Collection<String> known)
    {
        Set<String> result = new LinkedHashSet<>();
        for (String key : known)
        {
            if (categoryOf(key).recommendedDisabled())
            {
                result.add(key);
            }
        }
        return result;
    }
}
