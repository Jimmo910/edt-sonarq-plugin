/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
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
    private static final String MARKER_FILE = ".complete"; //$NON-NLS-1$
    private static final String CFG_FILE_NAME = "bsl-language-server.cfg"; //$NON-NLS-1$
    private static final String XMX_OPTION_PREFIX = "java-options=-Xmx"; //$NON-NLS-1$
    private static final String HEAP_UNIT_SUFFIX = "g"; //$NON-NLS-1$
    private static final int MIN_HEAP_GB = 1;

    private static final long LOCK_POLL_MILLIS = 200L;

    /**
     * Guards the whole check-install critical section of {@link #ensureServer}.
     *
     * <p>Two entry points can call {@link #ensureServer} concurrently against the same state directory:
     * the "Fetch Checks List" job on the BSL checks preference page, and a project refresh job running
     * {@code LocalIssueProvider#fetchIssues}. Without this lock, both could observe "not installed" at the
     * same time, both delete and re-extract the same target directory, and corrupt the installed
     * distribution - found in the K3 review, 2026-07-17. Package-private so the headless test fragment can
     * hold it directly to exercise the cancellation-while-waiting path.
     */
    static final ReentrantLock INSTALL_LOCK = new ReentrantLock();

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
     * <p>If the expected launcher already exists and a {@code .complete} marker file confirms a prior
     * extraction ran to completion, the method returns immediately without invoking {@code download}.
     * Otherwise any leftover directory (for example a half-extracted install left by a cancelled or
     * crashed run) is deleted, the zip is streamed and unpacked with a zip-slip guard, the monitor being
     * polled for cancellation before and per entry, and the marker is written only after the whole
     * archive has been extracted successfully. On non-Windows systems every regular file in a {@code bin}
     * directory (the launcher and the bundled runtime tools) is marked executable, since the zip does not
     * carry Unix permission bits.
     *
     * <p>The whole check-install sequence runs under {@link #INSTALL_LOCK}, serializing concurrent callers
     * against the same state directory (see the field javadoc); a caller blocked waiting for the lock still
     * observes monitor cancellation promptly, via {@link #acquireInstallLock(IProgressMonitor)}, even while
     * another caller is mid-download.
     *
     * @param stateDir the plugin state directory to unpack under, not {@code null}
     * @param download the source of the archive bytes, not {@code null}
     * @param monitor the progress monitor checked for cancellation, or {@code null}
     * @return the path to the launcher executable, never {@code null}
     * @throws IOException if the archive cannot be read, an entry escapes the target directory, or the
     *     calling thread is interrupted while waiting for {@link #INSTALL_LOCK}
     * @throws OperationCanceledException if the monitor is cancelled while waiting for the lock, before, or
     *     during unpacking
     */
    public static Path ensureServer(Path stateDir, DownloadFunction download, IProgressMonitor monitor)
        throws IOException
    {
        acquireInstallLock(monitor);
        try
        {
            return doEnsureServer(stateDir, download, monitor);
        }
        finally
        {
            INSTALL_LOCK.unlock();
        }
    }

    /**
     * Rewrites the bundled BSL Language Server launcher configuration file so its pinned JVM heap limit
     * matches {@code maxHeapGb}, instead of the {@code -Xmx4g} baked into the jpackage app-image by the
     * upstream project at build time.
     *
     * <p>The app-image's {@code java-options=-Xmx4g} line caps analysis at 4 GB of heap; large 1C
     * configurations exhaust that limit inside {@code ServerContext.populateContext} and the language
     * server dies with an {@link OutOfMemoryError}, a non-zero exit code and no SARIF written - confirmed
     * live 2026-07-18, both by reproducing the failure at 4 GB and by clearing it after raising the limit.
     * The jpackage launcher only reads its heap flag from this file - there is no command-line override -
     * so making the limit configurable means rewriting the file before every analysis run.
     *
     * <p>The file's location differs per operating system and jpackage layout ({@code app/...} on
     * Windows, {@code lib/app/...} on Linux, {@code Contents/app/...} on macOS), so rather than hardcoding
     * a relative path this walks the whole {@code stateDir/bsl-ls} tree looking for a file named
     * {@code bsl-language-server.cfg}. Every existing {@code java-options=-Xmx...} line is removed and
     * exactly one fresh line is appended, so repeated calls converge on the same content (idempotent)
     * regardless of how many stale lines a previous run left behind.
     *
     * <p>Never throws for a missing distribution: when the server has not been installed yet (or the
     * {@code bsl-ls} directory itself does not exist), this is a silent no-op, because a failure here must
     * never fail the analysis that is about to run {@link #ensureServer}.
     *
     * @param stateDir the plugin state directory the BSL Language Server was (or will be) unpacked under,
     *     not {@code null}
     * @param maxHeapGb the desired maximum heap, in gigabytes; clamped up to a minimum of 1 if lower
     * @throws IOException if the configuration file is found but cannot be read or written
     */
    public static void configureHeap(Path stateDir, int maxHeapGb) throws IOException
    {
        int clamped = Math.max(MIN_HEAP_GB, maxHeapGb);
        Path cfg = findCfgFile(stateDir.resolve(SERVER_DIR));
        if (cfg == null)
        {
            return;
        }
        List<String> updated = new ArrayList<>();
        for (String line : Files.readAllLines(cfg, StandardCharsets.UTF_8))
        {
            if (!line.strip().startsWith(XMX_OPTION_PREFIX))
            {
                updated.add(line);
            }
        }
        updated.add(XMX_OPTION_PREFIX + clamped + HEAP_UNIT_SUFFIX);
        Files.write(cfg, updated, StandardCharsets.UTF_8);
    }

    /**
     * Looks for a file named {@code bsl-language-server.cfg} anywhere under {@code serverRoot}, at
     * whatever depth the current jpackage layout nests it at (see {@link #configureHeap}).
     *
     * @param serverRoot the {@code bsl-ls} directory holding the unpacked distribution, not {@code null}
     * @return the found configuration file, or {@code null} if {@code serverRoot} does not exist or holds
     *     no such file
     * @throws IOException if the distribution tree cannot be walked
     */
    private static Path findCfgFile(Path serverRoot) throws IOException
    {
        if (!Files.isDirectory(serverRoot))
        {
            return null;
        }
        try (Stream<Path> walk = Files.walk(serverRoot))
        {
            return walk.filter(Files::isRegularFile)
                .filter(path -> CFG_FILE_NAME.equals(String.valueOf(path.getFileName())))
                .findFirst()
                .orElse(null);
        }
    }

    /**
     * Acquires {@link #INSTALL_LOCK}, polling in short slices so a monitor cancellation is observed
     * promptly even while another caller is mid-download (the critical section can run for as long as a
     * ~170 MB download takes).
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
     * The check-install sequence itself, run under {@link #INSTALL_LOCK} by {@link #ensureServer}.
     *
     * @param stateDir the plugin state directory to unpack under, not {@code null}
     * @param download the source of the archive bytes, not {@code null}
     * @param monitor the progress monitor checked for cancellation, or {@code null}
     * @return the path to the launcher executable, never {@code null}
     * @throws IOException if the archive cannot be read or an entry escapes the target directory
     * @throws OperationCanceledException if the monitor is cancelled before or during unpacking
     */
    private static Path doEnsureServer(Path stateDir, DownloadFunction download, IProgressMonitor monitor)
        throws IOException
    {
        Path serverRoot = stateDir.resolve(SERVER_DIR);
        Path executable = expectedExecutable(serverRoot);
        Path marker = serverRoot.resolve(MARKER_FILE);
        if (Files.exists(executable) && isMarkedForCurrentVersion(marker))
        {
            return executable;
        }
        if (monitor != null && monitor.isCanceled())
        {
            throw new OperationCanceledException();
        }
        deleteRecursively(serverRoot);
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
        Files.writeString(marker, VERSION, StandardCharsets.UTF_8);
        return executable;
    }

    /**
     * Tells whether the completion marker records the currently pinned version, so a version bump forces
     * a fresh download instead of reusing a launcher extracted for an older release.
     *
     * @param marker the completion marker file, not {@code null}
     * @return {@code true} if the marker exists and holds the current {@link #VERSION}
     */
    private static boolean isMarkedForCurrentVersion(Path marker)
    {
        try
        {
            return Files.exists(marker) && VERSION.equals(Files.readString(marker, StandardCharsets.UTF_8).trim());
        }
        catch (IOException e)
        {
            return false;
        }
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
