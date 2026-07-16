/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.mapping;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/** Detects the current git branch of an EDT project working tree. */
public final class GitBranchDetector
{
    private GitBranchDetector()
    {
    }

    /**
     * Detects the checked-out branch for the repository containing the given directory.
     *
     * @param directoryInsideRepo a directory inside a git working tree, not {@code null}
     * @return the short branch name, or empty for a non-repository or a detached HEAD
     */
    public static Optional<String> detectBranch(File directoryInsideRepo)
    {
        FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(directoryInsideRepo);
        if (builder.getGitDir() == null)
        {
            return Optional.empty();
        }
        try (Repository repository = builder.build())
        {
            String fullBranch = repository.getFullBranch();
            if (fullBranch != null && fullBranch.startsWith(Constants.R_HEADS))
            {
                return Optional.of(Repository.shortenRefName(fullBranch));
            }
            return Optional.empty();
        }
        catch (IOException e)
        {
            return Optional.empty();
        }
    }
}
