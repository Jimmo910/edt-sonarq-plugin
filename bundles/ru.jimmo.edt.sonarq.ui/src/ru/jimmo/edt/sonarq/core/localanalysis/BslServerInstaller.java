/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

import ru.jimmo.edt.sonarq.core.analysis.DownloadFunction;

/**
 * Downloads and unpacks the self-contained BSL Language Server distribution into a state directory,
 * idempotently.
 *
 * <p>The plugin ships the {@code jpackage} application-image assets ({@code bsl-language-server_win.zip} /
 * {@code _nix.zip} / {@code _mac.zip}). Each zip bundles a private Java runtime, so the analysis runs
 * with no external JDK on the machine or on {@code PATH}. The nesting and launcher name differ per OS
 * (verified against the real 1.0.4 assets):
 *
 * <ul>
 * <li>Windows: {@code bsl-language-server/bsl-language-server.exe}</li>
 * <li>Linux: {@code bsl-language-server/bin/bsl-language-server}</li>
 * <li>macOS: {@code bsl-language-server.app/Contents/MacOS/bsl-language-server}</li>
 * </ul>
 *
 * <p>A future user-override setting (task L4) may point at an existing {@code bsl-language-server}
 * executable; that path is used as-is and does not go through this installer.
 */
public final class BslServerInstaller
{
    /** The pinned BSL Language Server release version, used to build the download tag path. */
    public static final String VERSION = "1.0.4"; //$NON-NLS-1$

    private static final String BASE_URL =
        "https://github.com/1c-syntax/bsl-language-server/releases/download/"; //$NON-NLS-1$
    private static final String TAG_PREFIX = "v"; //$NON-NLS-1$
    private static final String ASSET_PREFIX = "bsl-language-server_"; //$NON-NLS-1$
    private static final String ZIP_SUFFIX = ".zip"; //$NON-NLS-1$
    private static final String OS_WINDOWS = "win"; //$NON-NLS-1$
    private static final String OS_MAC = "mac"; //$NON-NLS-1$
    private static final String OS_NIX = "nix"; //$NON-NLS-1$
    private static final String SERVER_DIR = "bsl-ls"; //$NON-NLS-1$
    private static final String APP_DIR = "bsl-language-server"; //$NON-NLS-1$
    private static final String APP_DIR_MAC = "bsl-language-server.app"; //$NON-NLS-1$
    private static final String CONTENTS_DIR = "Contents"; //$NON-NLS-1$
    private static final String MACOS_DIR = "MacOS"; //$NON-NLS-1$
    private static final String BIN_DIR = "bin"; //$NON-NLS-1$
    private static final String EXE_WINDOWS = "bsl-language-server.exe"; //$NON-NLS-1$
    private static final String EXE_OTHER = "bsl-language-server"; //$NON-NLS-1$

    private BslServerInstaller()
    {
    }

    /**
     * Returns the download URL of the operating-system-matching native distribution zip.
     *
     * @return the GitHub release asset URL, never {@code null}
     */
    public static String downloadUrl()
    {
        return BASE_URL + TAG_PREFIX + VERSION + '/' + ASSET_PREFIX + osClassifier() + ZIP_SUFFIX;
    }

    /**
     * Ensures the BSL Language Server distribution is installed under {@code stateDir/bsl-ls} and returns
     * its launcher executable.
     *
     * <p>If the expected launcher already exists the method returns immediately without invoking
     * {@code download}. Otherwise the zip is streamed and unpacked with a zip-slip guard, the monitor
     * being polled for cancellation before and per entry. On non-Windows systems every regular file in a
     * {@code bin} directory (the launcher and the bundled runtime tools) is marked executable, since the
     * zip does not carry Unix permission bits.
     *
     * @param stateDir the plugin state directory to unpack under, not {@code null}
     * @param download the source of the archive bytes, not {@code null}
     * @param monitor the progress monitor checked for cancellation, or {@code null}
     * @return the path to the launcher executable, never {@code null}
     * @throws IOException if the archive cannot be read or an entry escapes the target directory
     * @throws OperationCanceledException if the monitor is cancelled before or during unpacking
     */
    public static Path ensureServer(Path stateDir, DownloadFunction download, IProgressMonitor monitor)
        throws IOException
    {
        Path serverRoot = stateDir.resolve(SERVER_DIR);
        Path executable = expectedExecutable(serverRoot);
        if (Files.exists(executable))
        {
            return executable;
        }
        if (monitor != null && monitor.isCanceled())
        {
            throw new OperationCanceledException();
        }
        Files.createDirectories(serverRoot);
        Path normalizedRoot = serverRoot.normalize();
        try (ZipInputStream zip = new ZipInputStream(download.open(downloadUrl())))
        {
            ZipEntry entry = zip.getNextEntry();
            while (entry != null)
            {
                if (monitor != null && monitor.isCanceled())
                {
                    throw new OperationCanceledException();
                }
                Path target = resolveEntry(normalizedRoot, entry.getName());
                if (entry.isDirectory())
                {
                    Files.createDirectories(target);
                }
                else
                {
                    Path parent = target.getParent();
                    if (parent != null)
                    {
                        Files.createDirectories(parent);
                    }
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
                entry = zip.getNextEntry();
            }
        }
        if (!OS_WINDOWS.equals(osClassifier()))
        {
            markBinExecutable(serverRoot);
        }
        return executable;
    }

    /**
     * Marks every regular file inside a launcher directory ({@code bin} on Linux and the bundled
     * runtime, {@code MacOS} on macOS) of the unpacked distribution as executable, covering the launcher
     * and the bundled runtime tools on POSIX systems where the zip carries no Unix permission bits.
     *
     * @param serverRoot the {@code bsl-ls} directory holding the unpacked distribution, not {@code null}
     * @throws IOException if the distribution tree cannot be walked
     */
    private static void markBinExecutable(Path serverRoot) throws IOException
    {
        if (!Files.isDirectory(serverRoot))
        {
            return;
        }
        try (Stream<Path> files = Files.walk(serverRoot))
        {
            files.filter(Files::isRegularFile)
                .filter(BslServerInstaller::isInExecutableDirectory)
                .forEach(file -> file.toFile().setExecutable(true, false));
        }
    }

    /**
     * Tests whether a file's immediate parent directory holds executables ({@code bin} or {@code MacOS}).
     *
     * @param file the file to test, not {@code null}
     * @return {@code true} if the file sits directly inside a {@code bin} or {@code MacOS} directory
     */
    private static boolean isInExecutableDirectory(Path file)
    {
        Path parent = file.getParent();
        if (parent == null)
        {
            return false;
        }
        String parentName = String.valueOf(parent.getFileName());
        return BIN_DIR.equals(parentName) || MACOS_DIR.equals(parentName);
    }

    /**
     * Resolves a zip entry name against the target root, rejecting paths that escape it.
     *
     * @param normalizedRoot the normalized target root, not {@code null}
     * @param entryName the raw entry name, not {@code null}
     * @return the safe resolved path, never {@code null}
     * @throws IOException if the entry would land outside the target root
     */
    private static Path resolveEntry(Path normalizedRoot, String entryName) throws IOException
    {
        Path resolved = normalizedRoot.resolve(entryName).normalize();
        if (!resolved.startsWith(normalizedRoot))
        {
            throw new IOException("Zip entry escapes target directory: " + entryName); //$NON-NLS-1$
        }
        return resolved;
    }

    /**
     * Returns the expected launcher path for the current operating system.
     *
     * @param serverRoot the {@code bsl-ls} directory, not {@code null}
     * @return the launcher path, never {@code null}
     */
    private static Path expectedExecutable(Path serverRoot)
    {
        String os = osClassifier();
        if (OS_WINDOWS.equals(os))
        {
            return serverRoot.resolve(APP_DIR).resolve(EXE_WINDOWS);
        }
        if (OS_MAC.equals(os))
        {
            return serverRoot.resolve(APP_DIR_MAC).resolve(CONTENTS_DIR).resolve(MACOS_DIR).resolve(EXE_OTHER);
        }
        return serverRoot.resolve(APP_DIR).resolve(BIN_DIR).resolve(EXE_OTHER);
    }

    private static String osClassifier()
    {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$
        if (os.contains("win")) //$NON-NLS-1$
        {
            return OS_WINDOWS;
        }
        if (os.contains("mac")) //$NON-NLS-1$
        {
            return OS_MAC;
        }
        return OS_NIX;
    }
}
