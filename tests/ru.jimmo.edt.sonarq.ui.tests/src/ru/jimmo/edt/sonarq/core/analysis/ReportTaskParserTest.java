/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Tests for {@link ReportTaskParser}. */
public class ReportTaskParserTest
{
    private Path workDir;

    @Before
    public void setUp() throws IOException
    {
        workDir = Files.createTempDirectory("sonarq-report-task-test");
    }

    @After
    public void tearDown() throws IOException
    {
        try (Stream<Path> walk = Files.walk(workDir))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private void writeReportTask(String content) throws IOException
    {
        Files.writeString(workDir.resolve("report-task.txt"), content, StandardCharsets.UTF_8);
    }

    @Test
    public void readsCeTaskId() throws IOException
    {
        writeReportTask("""
            projectKey=my.project
            serverUrl=https://sonar.example.com
            ceTaskId=AXabc123
            ceTaskUrl=https://sonar.example.com/api/ce/task?id=AXabc123
            """);

        Optional<String> ceTaskId = ReportTaskParser.ceTaskId(workDir);
        assertTrue(ceTaskId.isPresent());
        assertEquals("AXabc123", ceTaskId.get());
    }

    @Test
    public void missingFileYieldsEmpty()
    {
        assertFalse(ReportTaskParser.ceTaskId(workDir).isPresent());
    }

    @Test
    public void missingKeyYieldsEmpty() throws IOException
    {
        writeReportTask("projectKey=my.project\nserverUrl=https://sonar.example.com\n");
        assertFalse(ReportTaskParser.ceTaskId(workDir).isPresent());
    }

    @Test
    public void emptyValueYieldsEmpty() throws IOException
    {
        writeReportTask("ceTaskId=\n");
        assertFalse(ReportTaskParser.ceTaskId(workDir).isPresent());
    }
}
