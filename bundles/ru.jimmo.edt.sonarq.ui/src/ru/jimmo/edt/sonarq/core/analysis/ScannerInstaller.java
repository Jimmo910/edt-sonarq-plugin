/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

/** Downloads and unpacks the SonarScanner CLI into a state directory, idempotently. */
public final class ScannerInstaller
{
    /** The pinned SonarScanner CLI version. */
    public static final String SCANNER_VERSION = "7.1.0.4889"; //$NON-NLS-1$

    private static final String BASE_URL =
        "https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/"; //$NON-NLS-1$
    private static final String CLI_PREFIX = "sonar-scanner-cli-"; //$NON-NLS-1$
    private static final String DIR_PREFIX = "sonar-scanner-"; //$NON-NLS-1$
    private static final String ZIP_SUFFIX = ".zip"; //$NON-NLS-1$
    private static final String OS_WINDOWS = "windows-x64"; //$NON-NLS-1$
    private static final String OS_MAC = "macosx-aarch64"; //$NON-NLS-1$
    private static final String OS_LINUX = "linux-x64"; //$NON-NLS-1$
    private static final String SCANNER_DIR = "scanner"; //$NON-NLS-1$
    private static final String BIN_DIR = "bin"; //$NON-NLS-1$
    private static final String EXE_WINDOWS = "sonar-scanner.bat"; //$NON-NLS-1$
    private static final String EXE_OTHER = "sonar-scanner"; //$NON-NLS-1$
    private static final String MARKER_FILE = ".complete"; //$NON-NLS-1$

    private ScannerInstaller()
    {
    }

    /**
     * Returns the download URL for the current operating system.
     *
     * @return the scanner archive URL, never {@code null}
     */
    public static String downloadUrl()
    {
        return BASE_URL + CLI_PREFIX + SCANNER_VERSION + '-' + osClassifier() + ZIP_SUFFIX;
    }

    /**
     * Ensures the scanner is installed under {@code stateDir/scanner} and returns its executable.
     *
     * <p>If the expected executable already exists and a {@code .complete} marker file confirms a prior
     * extraction ran to completion, the method returns immediately without invoking {@code download}.
     * Otherwise any leftover directory (for example a half-extracted install left by a cancelled or
     * crashed run) is deleted, the archive is streamed and unpacked with a zip-slip guard, and the marker
     * is written only after the whole archive has been extracted successfully.
     *
     * @param stateDir the plugin state directory to unpack under, not {@code null}
     * @param download the source of the archive bytes, not {@code null}
     * @param monitor the progress monitor checked for cancellation per entry, or {@code null}
     * @return the path to the scanner executable, never {@code null}
     * @throws IOException if the archive cannot be read or an entry escapes the target directory
     * @throws OperationCanceledException if the monitor is cancelled during unpacking
     */
    public static Path ensureScanner(Path stateDir, DownloadFunction download, IProgressMonitor monitor)
        throws IOException
    {
        Path scannerRoot = stateDir.resolve(SCANNER_DIR);
        Path executable = expectedExecutable(scannerRoot);
        Path marker = scannerRoot.resolve(MARKER_FILE);
        if (Files.exists(executable) && Files.exists(marker))
        {
            return executable;
        }
        deleteRecursively(scannerRoot);
        Files.createDirectories(scannerRoot);
        Path normalizedRoot = scannerRoot.normalize();
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
            markBinExecutable(scannerRoot);
        }
        Files.createFile(marker);
        return executable;
    }

    /**
     * Recursively deletes a directory tree, tolerating a directory that does not exist.
     *
     * <p>Used to discard a poisoned half-extracted install (no completion marker) before retrying.
     *
     * @param dir the directory to delete, not {@code null}
     * @throws IOException if a file or directory cannot be deleted
     */
    private static void deleteRecursively(Path dir) throws IOException
    {
        if (!Files.isDirectory(dir))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    /**
     * Marks every regular file in the extracted {@code bin} directory as executable, for POSIX systems
     * where the archive does not carry Unix permission bits.
     *
     * @param scannerRoot the scanner root directory holding the unpacked distribution, not {@code null}
     * @throws IOException if the {@code bin} directory cannot be walked
     */
    private static void markBinExecutable(Path scannerRoot) throws IOException
    {
        Path binDir = scannerRoot.resolve(DIR_PREFIX + SCANNER_VERSION + '-' + osClassifier()).resolve(BIN_DIR);
        if (!Files.isDirectory(binDir))
        {
            return;
        }
        try (Stream<Path> files = Files.walk(binDir))
        {
            files.filter(Files::isRegularFile).forEach(file -> file.toFile().setExecutable(true, false));
        }
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

    private static Path expectedExecutable(Path scannerRoot)
    {
        String folder = DIR_PREFIX + SCANNER_VERSION + '-' + osClassifier();
        String executableName = OS_WINDOWS.equals(osClassifier()) ? EXE_WINDOWS : EXE_OTHER;
        return scannerRoot.resolve(folder).resolve(BIN_DIR).resolve(executableName);
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
        return OS_LINUX;
    }
}
