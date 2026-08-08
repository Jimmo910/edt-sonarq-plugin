/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.anchors;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Removes a temporary directory tree after a test, children before their parents.
 *
 * <p>Best-effort on purpose. On Windows a file deleted while something else still holds it open stays in the
 * directory listing until that handle closes, and a scanner may drop a scratch file of its own into a
 * directory being written to; either turns cleanup into a {@code DirectoryNotEmptyException} or a
 * {@code NoSuchFileException} that says nothing about the code under test. Whatever survives is under the
 * system temporary directory, where the operating system will collect it. Tests that care whether a
 * particular file exists assert that themselves.
 */
public final class TempDirectories
{
    private static final int ATTEMPTS = 5;

    private static final long RETRY_MILLIS = 50L;

    private TempDirectories()
    {
    }

    /**
     * Deletes a directory and everything under it, as far as the operating system allows.
     *
     * @param root the directory to remove, may be {@code null} or absent
     */
    public static void delete(Path root)
    {
        if (root == null)
        {
            return;
        }
        for (int attempt = 1; attempt <= ATTEMPTS && Files.exists(root); attempt++)
        {
            deleteOnce(root);
            if (Files.exists(root))
            {
                sleep();
            }
        }
    }

    /**
     * One post-order deletion pass; anything it cannot remove is left for the next attempt.
     *
     * @param root the directory to remove, not {@code null}
     */
    private static void deleteOnce(Path root)
    {
        try
        {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>()
            {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                {
                    deleteQuietly(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure)
                {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                {
                    deleteQuietly(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        catch (IOException e)
        {
            // Nothing left to do about it here; the next attempt, or the operating system, will.
        }
    }

    private static void deleteQuietly(Path path)
    {
        try
        {
            Files.deleteIfExists(path);
        }
        catch (IOException e)
        {
            // Held open, or gone since it was listed. Either way, not this test's business.
        }
    }

    private static void sleep()
    {
        try
        {
            Thread.sleep(RETRY_MILLIS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}
