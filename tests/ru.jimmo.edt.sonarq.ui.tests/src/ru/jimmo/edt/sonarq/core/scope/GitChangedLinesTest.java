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

    @Test
    public void subdirPathUsesForwardSlashKey() throws Exception
    {
        Path repo = Files.createTempDirectory("gitchgsubdir");
        try (Git git = Git.init().setDirectory(repo.toFile()).call())
        {
            Path sub = repo.resolve("sub").resolve("dir");
            Files.createDirectories(sub);
            Files.writeString(sub.resolve("m.bsl"), "l1\nl2\nl3\n");
            git.add().addFilepattern(".").call();
            RevCommit base = git.commit().setAuthor("t", "t@t").setMessage("base").call();
            Files.writeString(sub.resolve("m.bsl"), "l1\nCHANGED\nl3\n");

            ChangedLines cl = GitChangedLines.compute(repo.toFile(), base.getName());

            assertTrue(cl.available());
            assertTrue(cl.isChanged("sub/dir/m.bsl", 2));
        }
    }

    @Test
    public void renameIsReportedWithoutException() throws Exception
    {
        Path repo = Files.createTempDirectory("gitchgrename");
        try (Git git = Git.init().setDirectory(repo.toFile()).call())
        {
            Files.writeString(repo.resolve("a.bsl"), "l1\nl2\nl3\n");
            git.add().addFilepattern(".").call();
            RevCommit base = git.commit().setAuthor("t", "t@t").setMessage("base").call();
            Files.move(repo.resolve("a.bsl"), repo.resolve("b.bsl"));

            ChangedLines cl = GitChangedLines.compute(repo.toFile(), base.getName());

            assertTrue(cl.available());
            // Pure rename, content unchanged: detectRenames may report zero edits for b.bsl - that is an
            // acceptable outcome (either ranges or none). The important thing is that looking it up never
            // throws.
            cl.isChanged("b.bsl", 1);
        }
    }

    @Test
    public void deletedFileIsSkippedWithoutException() throws Exception
    {
        Path repo = Files.createTempDirectory("gitchgdelete");
        try (Git git = Git.init().setDirectory(repo.toFile()).call())
        {
            Files.writeString(repo.resolve("a.bsl"), "l1\nl2\n");
            Files.writeString(repo.resolve("keep.bsl"), "k1\nk2\nk3\n");
            git.add().addFilepattern(".").call();
            RevCommit base = git.commit().setAuthor("t", "t@t").setMessage("base").call();
            Files.delete(repo.resolve("a.bsl"));
            Files.writeString(repo.resolve("keep.bsl"), "k1\nCHANGED\nk3\n");

            ChangedLines cl = GitChangedLines.compute(repo.toFile(), base.getName());

            assertTrue(cl.available());
            assertTrue(cl.isChanged("keep.bsl", 2));
        }
    }
}
