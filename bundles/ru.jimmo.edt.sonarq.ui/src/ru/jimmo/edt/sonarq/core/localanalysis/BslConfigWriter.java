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

import com.google.gson.JsonObject;

/**
 * Writes a BSL Language Server configuration file that disables a set of diagnostics, so a local analysis
 * run only reports the diagnostics a user has left enabled.
 */
public final class BslConfigWriter
{
    /** The BSL Language Server configuration file name, written under the plugin state directory. */
    private static final String CONFIG_FILE_NAME = "bsl-ls-config.json"; //$NON-NLS-1$

    private static final String DIAGNOSTICS_MEMBER = "diagnostics"; //$NON-NLS-1$

    private static final String PARAMETERS_MEMBER = "parameters"; //$NON-NLS-1$

    private BslConfigWriter()
    {
    }

    /**
     * Writes a {@code bsl-ls-config.json} file under {@code stateDir} that disables every key in
     * {@code disabledKeys}, in the shape the BSL Language Server expects:
     * {@code {"diagnostics":{"parameters":{"Key":false,...}}}}, with parameter keys written in sorted
     * order. Any file previously written at that path is overwritten.
     *
     * @param stateDir the plugin state directory to write the config file under, not {@code null}
     * @param disabledKeys the diagnostic keys to disable, may be {@code null} or empty
     * @return the written file's path, or {@code null} if {@code disabledKeys} is {@code null} or empty
     *     and no config file is needed
     * @throws IOException if the file cannot be written
     */
    public static Path write(Path stateDir, Collection<String> disabledKeys) throws IOException
    {
        if (disabledKeys == null || disabledKeys.isEmpty())
        {
            return null;
        }
        JsonObject parameters = new JsonObject();
        for (String key : new TreeSet<>(disabledKeys))
        {
            parameters.addProperty(key, Boolean.FALSE);
        }
        JsonObject diagnostics = new JsonObject();
        diagnostics.add(PARAMETERS_MEMBER, parameters);
        JsonObject root = new JsonObject();
        root.add(DIAGNOSTICS_MEMBER, diagnostics);

        Path file = stateDir.resolve(CONFIG_FILE_NAME);
        Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
        return file;
    }
}
