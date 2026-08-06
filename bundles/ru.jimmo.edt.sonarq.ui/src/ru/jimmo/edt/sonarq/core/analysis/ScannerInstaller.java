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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
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
    private static final String OS_MAC_ARM = "macosx-aarch64"; //$NON-NLS-1$
    private static final String OS_MAC_INTEL = "macosx-x64"; //$NON-NLS-1$
    private static final String OS_LINUX = "linux-x64"; //$NON-NLS-1$
    private static final String SCANNER_DIR = "scanner"; //$NON-NLS-1$
    private static final String BIN_DIR = "bin"; //$NON-NLS-1$
    private static final String EXE_WINDOWS = "sonar-scanner.bat"; //$NON-NLS-1$
    private static final String EXE_OTHER = "sonar-scanner"; //$NON-NLS-1$
    private static final String MARKER_FILE = ".complete"; //$NON-NLS-1$

    private static final long LOCK_POLL_MILLIS = 200L;

    /**
     * Guards the whole check-install critical section of {@link #ensureScanner}.
     *
     * <p>Two analysis jobs for different projects can run {@link #ensureScanner} concurrently against the
     * same state directory. Without this lock both could observe "not installed", both would delete the same
     * {@code stateDir/scanner} tree and both would extract into it, so one caller would be handed an
     * executable the other is still overwriting - the same race
     * {@code BslServerInstaller#INSTALL_LOCK} guards for the language server. Package-private so the headless
     * test fragment can hold it directly to exercise the cancellation-while-waiting path.
     */
    static final ReentrantLock INSTALL_LOCK = new ReentrantLock();

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
     * <p>The whole sequence runs under {@link #INSTALL_LOCK}, serializing concurrent callers against the same
     * state directory (see the field javadoc); a caller blocked waiting for the lock still observes monitor
     * cancellation promptly, via {@link #acquireInstallLock(IProgressMonitor)}, even while another caller is
     * mid-download.
     *
     * @param stateDir the plugin state directory to unpack under, not {@code null}
     * @param download the source of the archive bytes, not {@code null}
     * @param monitor the progress monitor checked for cancellation per entry, or {@code null}
     * @return the path to the scanner executable, never {@code null}
     * @throws IOException if the archive cannot be read, an entry escapes the target directory, a leftover
     *     install cannot be deleted, or the calling thread is interrupted while waiting for
     *     {@link #INSTALL_LOCK}
     * @throws OperationCanceledException if the monitor is cancelled while waiting for the lock or during
     *     unpacking
     */
    public static Path ensureScanner(Path stateDir, DownloadFunction download, IProgressMonitor monitor)
        throws IOException
    {
        acquireInstallLock(monitor);
        try
        {
            return install(stateDir, download, monitor);
        }
        finally
        {
            INSTALL_LOCK.unlock();
        }
    }

    /**
     * Performs the check-install sequence of {@link #ensureScanner}, with {@link #INSTALL_LOCK} already held.
     *
     * @param stateDir the plugin state directory to unpack under, not {@code null}
     * @param download the source of the archive bytes, not {@code null}
     * @param monitor the progress monitor checked for cancellation per entry, or {@code null}
     * @return the path to the scanner executable, never {@code null}
     * @throws IOException if the archive cannot be read, an entry escapes the target directory, or a
     *     leftover install cannot be deleted
     * @throws OperationCanceledException if the monitor is cancelled during unpacking
     */
    private static Path install(Path stateDir, DownloadFunction download, IProgressMonitor monitor)
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
     * Acquires {@link #INSTALL_LOCK}, polling in short slices so a monitor cancellation is observed promptly
     * even while another caller is mid-download (the critical section can run for as long as the scanner
     * download takes).
     *
     * @param monitor the progress monitor checked for cancellation before and between poll attempts, or
     *     {@code null}
     * @throws IOException if the calling thread is interrupted while waiting for the lock
     * @throws OperationCanceledException if the monitor is cancelled before or while waiting for the lock
     */
    private static void acquireInstallLock(IProgressMonitor monitor) throws IOException
    {
        if (monitor != null && monitor.isCanceled())
        {
            throw new OperationCanceledException();
        }
        try
        {
            while (!INSTALL_LOCK.tryLock(LOCK_POLL_MILLIS, TimeUnit.MILLISECONDS))
            {
                if (monitor != null && monitor.isCanceled())
                {
                    throw new OperationCanceledException();
                }
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * Recursively deletes a directory tree, tolerating a directory that does not exist.
     *
     * <p>Used to discard a poisoned half-extracted install (no completion marker) before retrying.
     *
     * <p>Every single deletion is done with {@link Files#deleteIfExists}, so an entry that cannot be removed
     * raises an {@link IOException} naming it instead of being silently skipped - the earlier
     * {@code File#delete()} discarded that boolean, so a leftover the caller believed gone was extracted
     * into. {@code deleteIfExists} rather than {@code delete} because an entry that disappeared on its own
     * between the walk and the deletion is not a failure to report.
     *
     * @param dir the directory to delete, not {@code null}
     * @throws IOException if the tree cannot be walked, or one of its entries cannot be deleted
     */
    private static void deleteRecursively(Path dir) throws IOException
    {
        if (!Files.isDirectory(dir))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir))
        {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
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
        if (!Files.isDirectory(scannerRoot))
        {
            return;
        }
        // Mark every file under any bin directory executable, not just the scanner launcher: the
        // sonar-scanner-cli distribution bundles its own JRE, whose jre/bin/java must be runnable on POSIX.
        try (Stream<Path> files = Files.walk(scannerRoot))
        {
            files.filter(Files::isRegularFile)
                .filter(file -> file.getParent() != null
                    && BIN_DIR.equals(file.getParent().getFileName().toString()))
                .forEach(file -> file.toFile().setExecutable(true, false));
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
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$
            boolean arm = arch.contains("aarch64") || arch.contains("arm"); //$NON-NLS-1$ //$NON-NLS-2$
            return arm ? OS_MAC_ARM : OS_MAC_INTEL;
        }
        return OS_LINUX;
    }
}
