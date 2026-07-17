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
import java.util.Collection;
import java.util.TreeSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Writes a BSL Language Server configuration file that disables a set of diagnostics and/or restricts
 * analysis to a set of subsystems, so a local analysis run only reports the diagnostics a user has left
 * enabled and only for the subsystems the user has selected.
 */
public final class BslConfigWriter
{
    /** The BSL Language Server configuration file name, written under the plugin state directory. */
    private static final String CONFIG_FILE_NAME = "bsl-ls-config.json"; //$NON-NLS-1$

    private static final String DIAGNOSTICS_MEMBER = "diagnostics"; //$NON-NLS-1$

    private static final String PARAMETERS_MEMBER = "parameters"; //$NON-NLS-1$

    private static final String SUBSYSTEMS_FILTER_MEMBER = "subsystemsFilter"; //$NON-NLS-1$

    private static final String INCLUDE_MEMBER = "include"; //$NON-NLS-1$

    private BslConfigWriter()
    {
    }

    /**
     * Writes a {@code bsl-ls-config.json} file under {@code stateDir} that disables every key in
     * {@code disabledKeys} and/or restricts analysis to the subsystems in {@code includeSubsystems}, in
     * the shape the BSL Language Server expects:
     * {@code {"diagnostics":{"parameters":{"Key":false,...},"subsystemsFilter":{"include":["Name",...]}}}},
     * with each member present only when its input is non-empty and its entries written in sorted order.
     * Any file previously written at that path is overwritten.
     *
     * <p>This is called from {@code RefreshInputsFactory} on every project refresh once any diagnostic is
     * disabled or a subsystem filter is configured - on the UI thread for a manually triggered refresh -
     * so, before writing, the existing file (if any) is read and compared byte-for-byte against the
     * computed content; the write is skipped when they already match, turning the common case (neither
     * input has changed since the last refresh) into a small read and compare instead of a synchronous
     * file write on every refresh.
     *
     * @param stateDir the plugin state directory to write the config file under, not {@code null}
     * @param disabledKeys the diagnostic keys to disable, may be {@code null} or empty
     * @param includeSubsystems the subsystem names to restrict analysis to, may be {@code null} or empty
     * @return the written file's path, or {@code null} if both {@code disabledKeys} and
     *     {@code includeSubsystems} are {@code null} or empty and no config file is needed
     * @throws IOException if the existing file cannot be read or the new content cannot be written
     */
    public static Path write(Path stateDir, Collection<String> disabledKeys,
        Collection<String> includeSubsystems) throws IOException
    {
        boolean hasDisabled = disabledKeys != null && !disabledKeys.isEmpty();
        boolean hasSubsystems = includeSubsystems != null && !includeSubsystems.isEmpty();
        if (!hasDisabled && !hasSubsystems)
        {
            return null;
        }
        JsonObject diagnostics = new JsonObject();
        if (hasDisabled)
        {
            JsonObject parameters = new JsonObject();
            for (String key : new TreeSet<>(disabledKeys))
            {
                parameters.addProperty(key, Boolean.FALSE);
            }
            diagnostics.add(PARAMETERS_MEMBER, parameters);
        }
        if (hasSubsystems)
        {
            JsonArray include = new JsonArray();
            for (String name : new TreeSet<>(includeSubsystems))
            {
                include.add(name);
            }
            JsonObject subsystemsFilter = new JsonObject();
            subsystemsFilter.add(INCLUDE_MEMBER, include);
            diagnostics.add(SUBSYSTEMS_FILTER_MEMBER, subsystemsFilter);
        }
        JsonObject root = new JsonObject();
        root.add(DIAGNOSTICS_MEMBER, diagnostics);
        String json = root.toString();

        Path file = stateDir.resolve(CONFIG_FILE_NAME);
        if (isUnchanged(file, json))
        {
            return file;
        }
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    /**
     * Tells whether {@code file} already holds exactly {@code content}, so a caller can skip a redundant
     * write.
     *
     * @param file the file to compare against, not {@code null}
     * @param content the content that would be written, not {@code null}
     * @return {@code true} if {@code file} exists and its content equals {@code content}
     * @throws IOException if an existing file cannot be read
     */
    private static boolean isUnchanged(Path file, String content) throws IOException
    {
        if (!Files.isRegularFile(file))
        {
            return false;
        }
        return content.equals(Files.readString(file, StandardCharsets.UTF_8));
    }
}
