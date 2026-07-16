/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Reads the {@code ceTaskId} from a scanner's {@code report-task.txt} output. */
public final class ReportTaskParser
{
    private static final String REPORT_TASK_FILE = "report-task.txt"; //$NON-NLS-1$
    private static final String CE_TASK_PREFIX = "ceTaskId="; //$NON-NLS-1$

    private ReportTaskParser()
    {
    }

    /**
     * Extracts the Compute Engine task id from the scanner working directory.
     *
     * @param scannerWorkDir the scanner working directory, not {@code null}
     * @return the trimmed task id, or empty if the file, the key, or its value is missing
     */
    public static Optional<String> ceTaskId(Path scannerWorkDir)
    {
        Path reportTask = scannerWorkDir.resolve(REPORT_TASK_FILE);
        if (!Files.isRegularFile(reportTask))
        {
            return Optional.empty();
        }
        try
        {
            for (String line : Files.readAllLines(reportTask, StandardCharsets.UTF_8))
            {
                if (line.startsWith(CE_TASK_PREFIX))
                {
                    String value = line.substring(CE_TASK_PREFIX.length()).trim();
                    if (!value.isEmpty())
                    {
                        return Optional.of(value);
                    }
                }
            }
        }
        catch (IOException e)
        {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
