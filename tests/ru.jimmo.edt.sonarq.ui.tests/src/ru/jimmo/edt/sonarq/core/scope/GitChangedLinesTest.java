/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.scope;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;

/** Tests for {@link GitChangedLines}. */
public class GitChangedLinesTest
{
    @Test
    public void reportsWorkingCopyChangesAgainstBaseCommit() throws Exception
    {
        Path repo = Files.createTempDirectory("gitchg");
        try (Git git = Git.init().setDirectory(repo.toFile()).call())
        {
            Files.writeString(repo.resolve("m.bsl"), "l1\nl2\nl3\n");
            git.add().addFilepattern(".").call();
            RevCommit base = git.commit().setAuthor("t", "t@t").setMessage("base").call();
            Files.writeString(repo.resolve("m.bsl"), "l1\nCHANGED\nl3\nADDED\n");

            ChangedLines cl = GitChangedLines.compute(repo.toFile(), base.getName());

            assertTrue(cl.available());
            assertTrue(cl.isChanged("m.bsl", 2));
            assertTrue(cl.isChanged("m.bsl", 4));
            assertFalse(cl.isChanged("m.bsl", 1));
            assertTrue(cl.isChanged("m.bsl", 0));
        }
    }

    @Test
    public void newFileAllLinesChanged() throws Exception
    {
        Path repo = Files.createTempDirectory("gitchgnew");
        try (Git git = Git.init().setDirectory(repo.toFile()).call())
        {
            Files.writeString(repo.resolve("m.bsl"), "l1\n");
            git.add().addFilepattern(".").call();
            RevCommit base = git.commit().setAuthor("t", "t@t").setMessage("base").call();
            Files.writeString(repo.resolve("added.bsl"), "a1\na2\n");

            ChangedLines cl = GitChangedLines.compute(repo.toFile(), base.getName());

            assertTrue(cl.available());
            assertTrue(cl.isChanged("added.bsl", 1));
            assertTrue(cl.isChanged("added.bsl", 2));
            assertTrue(cl.isChanged("added.bsl", 0));
        }
    }

    @Test
    public void unresolvableBaseIsUnavailable() throws Exception
    {
        Path repo = Files.createTempDirectory("gitchg2");
        try (Git git = Git.init().setDirectory(repo.toFile()).call())
        {
            assertFalse(GitChangedLines.compute(repo.toFile(), "no-such-ref").available());
        }
    }

    @Test
    public void nonRepoDirIsUnavailable() throws Exception
    {
        assertFalse(GitChangedLines.compute(Files.createTempDirectory("plain").toFile(), "HEAD").available());
    }
}
