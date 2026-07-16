/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.mapping;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Tests for {@link GitBranchDetector}. */
public class GitBranchDetectorTest
{
    private Path tempDir;

    @Before
    public void setUp() throws IOException
    {
        tempDir = Files.createTempDirectory("sonarq-git-test"); //$NON-NLS-1$
    }

    @After
    public void tearDown() throws IOException
    {
        try (Stream<Path> walk = Files.walk(tempDir))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    public void detectsInitialBranchName() throws Exception
    {
        try (Git git = Git.init().setDirectory(tempDir.toFile())
            .setInitialBranch("feature/task-1").call()) //$NON-NLS-1$
        {
            assertEquals(Optional.of("feature/task-1"), //$NON-NLS-1$
                GitBranchDetector.detectBranch(tempDir.toFile()));
        }
    }

    @Test
    public void detectsBranchFromNestedDirectory() throws Exception
    {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) //$NON-NLS-1$
        {
            Path nested = Files.createDirectories(tempDir.resolve("src/CommonModules")); //$NON-NLS-1$
            assertEquals(Optional.of("main"), GitBranchDetector.detectBranch(nested.toFile())); //$NON-NLS-1$
        }
    }

    @Test
    public void nonRepositoryYieldsEmpty()
    {
        assertEquals(Optional.empty(), GitBranchDetector.detectBranch(tempDir.toFile()));
    }

    @Test
    public void detachedHeadYieldsEmpty() throws Exception
    {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) //$NON-NLS-1$
        {
            RevCommit commit = git.commit().setAllowEmpty(true).setMessage("init") //$NON-NLS-1$
                .setAuthor("test", "test@example.com") //$NON-NLS-1$ //$NON-NLS-2$
                .setCommitter("test", "test@example.com").call(); //$NON-NLS-1$ //$NON-NLS-2$
            git.checkout().setName(commit.getName()).call();
            assertEquals(Optional.empty(), GitBranchDetector.detectBranch(tempDir.toFile()));
        }
    }
}
