/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

import ru.jimmo.edt.sonarq.core.analysis.DownloadFunction;

/** Downloads the BSL Language Server executable jar into a state directory, idempotently. */
public final class BslServerInstaller
{
    /** The pinned BSL Language Server release version (asset {@code bsl-language-server-<VERSION>-exec.jar}). */
    public static final String VERSION = "1.0.4"; //$NON-NLS-1$

    private static final String BASE_URL =
        "https://github.com/1c-syntax/bsl-language-server/releases/download/"; //$NON-NLS-1$
    private static final String JAR_PREFIX = "bsl-language-server-"; //$NON-NLS-1$
    private static final String JAR_SUFFIX = "-exec.jar"; //$NON-NLS-1$
    private static final String TAG_PREFIX = "v"; //$NON-NLS-1$
    private static final String SERVER_DIR = "bsl-ls"; //$NON-NLS-1$
    private static final String PART_SUFFIX = ".part"; //$NON-NLS-1$
    private static final int CHUNK_SIZE = 64 * 1024;

    private BslServerInstaller()
    {
    }

    /**
     * Returns the download URL of the pinned BSL Language Server executable jar.
     *
     * @return the GitHub release asset URL, never {@code null}
     */
    public static String downloadUrl()
    {
        return BASE_URL + TAG_PREFIX + VERSION + '/' + jarName();
    }

    /**
     * Ensures the BSL Language Server jar is installed under {@code stateDir/bsl-ls} and returns its path.
     *
     * <p>If the expected jar already exists the method returns immediately without invoking
     * {@code download}. Otherwise the single jar is streamed to a temporary file in 64&nbsp;KiB chunks,
     * the monitor being polled for cancellation before and during the copy, and then atomically moved
     * into place so a partial download never masquerades as a complete install.
     *
     * @param stateDir the plugin state directory to install under, not {@code null}
     * @param download the source of the jar bytes, not {@code null}
     * @param monitor the progress monitor checked for cancellation, or {@code null}
     * @return the path to the installed jar, never {@code null}
     * @throws IOException if the jar cannot be read or written
     * @throws OperationCanceledException if the monitor is cancelled before or during the download
     */
    public static Path ensureServer(Path stateDir, DownloadFunction download, IProgressMonitor monitor)
        throws IOException
    {
        Path serverDir = stateDir.resolve(SERVER_DIR);
        Path target = serverDir.resolve(jarName());
        if (Files.exists(target))
        {
            return target;
        }
        if (monitor != null && monitor.isCanceled())
        {
            throw new OperationCanceledException();
        }
        Files.createDirectories(serverDir);
        Path partFile = serverDir.resolve(jarName() + PART_SUFFIX);
        try
        {
            downloadTo(download, partFile, monitor);
        }
        catch (IOException | RuntimeException e)
        {
            deleteQuietly(partFile);
            throw e;
        }
        Files.move(partFile, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /**
     * Streams the jar bytes into {@code partFile} in fixed-size chunks, aborting on cancellation.
     *
     * @param download the source of the jar bytes, not {@code null}
     * @param partFile the temporary destination file, not {@code null}
     * @param monitor the progress monitor checked per chunk, or {@code null}
     * @throws IOException if the jar cannot be read or written
     * @throws OperationCanceledException if the monitor is cancelled during the copy
     */
    private static void downloadTo(DownloadFunction download, Path partFile, IProgressMonitor monitor)
        throws IOException
    {
        byte[] buffer = new byte[CHUNK_SIZE];
        try (InputStream in = download.open(downloadUrl());
            OutputStream out = Files.newOutputStream(partFile))
        {
            int read = in.read(buffer);
            while (read != -1)
            {
                if (monitor != null && monitor.isCanceled())
                {
                    throw new OperationCanceledException();
                }
                out.write(buffer, 0, read);
                read = in.read(buffer);
            }
        }
    }

    /**
     * Deletes a file if it exists, swallowing any I/O error so cleanup never masks the original failure.
     *
     * @param path the file to remove, not {@code null}
     */
    private static void deleteQuietly(Path path)
    {
        try
        {
            Files.deleteIfExists(path);
        }
        catch (IOException ignored)
        {
            // best-effort cleanup of a partial download
        }
    }

    /**
     * Returns the executable jar file name for the pinned version.
     *
     * @return the jar file name, never {@code null}
     */
    public static String jarName()
    {
        return JAR_PREFIX + VERSION + JAR_SUFFIX;
    }
}
