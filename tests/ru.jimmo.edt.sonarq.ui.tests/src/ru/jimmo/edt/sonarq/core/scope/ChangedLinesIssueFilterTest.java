/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.scope;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import ru.jimmo.edt.sonarq.core.model.SonarIssue;
import ru.jimmo.edt.sonarq.core.model.SonarIssueType;
import ru.jimmo.edt.sonarq.core.model.SonarSeverity;

/** Tests for {@link ChangedLinesIssueFilter}. */
public class ChangedLinesIssueFilterTest
{
    private static SonarIssue issue(String componentKey, int line)
    {
        return new SonarIssue("key", "rule", SonarSeverity.MAJOR, SonarIssueType.CODE_SMELL, componentKey,
            "message", line);
    }

    private static ChangedLines fake(File workTree, Set<String> changedFileLine, Set<String> changedFiles)
    {
        return new ChangedLines()
        {
            @Override
            public boolean available()
            {
                return true;
            }

            @Override
            public File workTreeRoot()
            {
                return workTree;
            }

            @Override
            public boolean isChanged(String path, int line)
            {
                return line <= 0 ? changedFiles.contains(path) : changedFileLine.contains(path + "#" + line);
            }
        };
    }

    @Test
    public void keepsOnlyChangedLinesWhenProjectIsRepoRoot()
    {
        Path root = Path.of("C:/repo/proj");
        SonarIssue kept = issue("proj:src/CommonModules/A/Module.bsl", 10);
        SonarIssue dropped = issue("proj:src/CommonModules/A/Module.bsl", 11);
        ChangedLines cl = fake(root.toFile(), Set.of("src/CommonModules/A/Module.bsl#10"), Set.of());

        List<SonarIssue> out = ChangedLinesIssueFilter.keepChanged(List.of(kept, dropped), "proj", root, cl);

        assertEquals(List.of(kept), out);
    }

    @Test
    public void prefixesPathWhenProjectIsRepoSubdir()
    {
        Path repo = Path.of("C:/repo");
        Path proj = repo.resolve("apps/erp");
        SonarIssue kept = issue("erp:src/CommonModules/A/Module.bsl", 5);
        ChangedLines cl = fake(repo.toFile(), Set.of("apps/erp/src/CommonModules/A/Module.bsl#5"), Set.of());

        List<SonarIssue> out = ChangedLinesIssueFilter.keepChanged(List.of(kept), "erp", proj, cl);

        assertEquals(1, out.size());
    }

    @Test
    public void unavailableChangedLinesPassesEverythingThrough()
    {
        ChangedLines unavailable = new ChangedLines()
        {
            @Override
            public boolean available()
            {
                return false;
            }

            @Override
            public File workTreeRoot()
            {
                return null;
            }

            @Override
            public boolean isChanged(String p, int l)
            {
                return false;
            }
        };
        List<SonarIssue> in = List.of(issue("p:src/M.bsl", 1));
        assertEquals(in, ChangedLinesIssueFilter.keepChanged(in, "p", Path.of("C:/p"), unavailable));
    }

    @Test
    public void fileLevelIssueKeptWhenFileChanged()
    {
        Path root = Path.of("C:/repo/proj");
        SonarIssue fileLevel = issue("proj:src/CommonModules/A/Module.bsl", 0);
        ChangedLines cl = fake(root.toFile(), Set.of(), Set.of("src/CommonModules/A/Module.bsl"));
        assertEquals(List.of(fileLevel),
            ChangedLinesIssueFilter.keepChanged(List.of(fileLevel), "proj", root, cl));
    }
}
