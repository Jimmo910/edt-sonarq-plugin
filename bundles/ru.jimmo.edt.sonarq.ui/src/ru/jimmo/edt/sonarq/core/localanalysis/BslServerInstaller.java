/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

import com.github._1c_syntax.utils.downloader.BslLanguageServerDownloader;
import com.github._1c_syntax.utils.downloader.BslLanguageServerReleaseChannel;
import com.github._1c_syntax.utils.downloader.DownloadProgressListener;
import com.github._1c_syntax.utils.downloader.GitHubReleaseClient;

import ru.jimmo.edt.sonarq.core.analysis.DownloadFunction;
import ru.jimmo.edt.sonarq.ui.Messages;

/**
 * Makes a self-contained BSL Language Server distribution available under a state directory, idempotently.
 *
 * <p>For the managed update channels ({@code STABLE}/{@code PRERELEASE}) this is a thin adapter over the
 * upstream {@link BslLanguageServerDownloader} from {@code io.github.1c-syntax:utils} - the downloader the
 * BSL Language Server maintainers share across their VS Code, IntelliJ and CLI clients (issue #8). Release
 * resolution, the update-check throttle ({@code SERVER-INFO}), version comparison, extraction and cleanup of
 * superseded versions all live upstream now. What stays here is what upstream has no concept of: the pinned
 * {@code FIXED} channel, the offline floor, the {@link IProgressMonitor} cancellation bridge, the install
 * lock and the heap {@code .cfg} rewrite.
 *
 * <p>Both paths share the upstream on-disk layout, so a single binary-path rule serves them
 * ({@code installDir} being {@code stateDir/bsl-ls}):
 *
 * <ul>
 * <li>Windows: {@code <installDir>/<version>/bsl-language-server/bsl-language-server.exe}</li>
 * <li>Linux: {@code <installDir>/<version>/bsl-language-server/bin/bsl-language-server}</li>
 * <li>macOS: {@code <installDir>/<version>/bsl-language-server.app/Contents/MacOS/bsl-language-server}</li>
 * </ul>
 *
 * <p>Each zip bundles a private Java runtime, so the analysis runs with no external JDK on the machine or on
 * {@code PATH}. A user-override setting may point at an existing {@code bsl-language-server} executable; that
 * path is used as-is and does not go through this installer.
 */
public final class BslServerInstaller
{
    /** The pinned BSL Language Server release version, the offline floor and the {@code FIXED} channel target. */
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
    private static final String STAGING_PREFIX = ".staging-"; //$NON-NLS-1$
    private static final String HIDDEN_PREFIX = "."; //$NON-NLS-1$
    private static final String CFG_FILE_NAME = "bsl-language-server.cfg"; //$NON-NLS-1$
    private static final String XMX_OPTION_PREFIX = "java-options=-Xmx"; //$NON-NLS-1$
    private static final String HEAP_UNIT_SUFFIX = "g"; //$NON-NLS-1$
    private static final int MIN_HEAP_GB = 1;

    private static final long LOCK_POLL_MILLIS = 200L;

    /** Connect timeout of the HTTP client handed to the upstream downloader and its release client. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

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
     * Performs the managed (upstream) install attempt, so the fallback chain around it can be exercised
     * without touching the network.
     */
    @FunctionalInterface
    interface ManagedInstall
    {
        /**
         * Runs the upstream downloader.
         *
         * @return the launcher path the upstream downloader settled on, possibly non-existent
         * @throws IOException if the release cannot be fetched or downloaded and nothing is installed
         */
        Path install() throws IOException;
    }

    /**
     * Returns the download URL of the operating-system-matching native distribution zip for the pinned
     * {@link #VERSION}.
     *
     * @return the GitHub release asset URL, never {@code null}
     */
    public static String downloadUrl()
    {
        return BASE_URL + TAG_PREFIX + VERSION + '/' + assetName(osClassifier());
    }

    /**
     * Tells whether a BSL Language Server distribution is installed under {@code stateDir/bsl-ls}, at any
     * version.
     *
     * <p>Never acquires {@link #INSTALL_LOCK} and never touches the network, so it is safe to call often -
     * for example to decide whether to show a "downloading..." hint before scheduling a refresh, or to render
     * an installed/not-installed label on a preferences page. The installed version is not pinned - the
     * update channel can move it - so this deliberately reports whether <em>some</em> engine is present.
     *
     * @param stateDir the plugin state directory the server would be unpacked under, not {@code null}
     * @return {@code true} if a launcher exists under some version directory
     */
    public static boolean isInstalled(Path stateDir)
    {
        return installedVersion(stateDir).isPresent();
    }

    /**
     * Returns the version of the BSL Language Server distribution installed under {@code stateDir/bsl-ls}.
     *
     * <p>Deliberately a plain filesystem scan rather than
     * {@code new BslLanguageServerDownloader(...).installedVersion()}: the upstream call would force us to
     * allocate a {@link GitHubReleaseClient} and an {@link HttpClient} (which owns a selector thread) on a
     * query that views and preference pages make on every repaint, and it only sees installs upstream itself
     * recorded in {@code SERVER-INFO} - it would miss a {@code FIXED}-channel install written by
     * {@link #ensureServer}. Scanning the version directories sees both. Network-free and lock-free.
     *
     * @param stateDir the plugin state directory the server would be unpacked under, not {@code null}
     * @return the installed version (the newest one if several are present), or empty if none is installed
     */
    public static Optional<String> installedVersion(Path stateDir)
    {
        Path installDir = stateDir.resolve(SERVER_DIR);
        if (!Files.isDirectory(installDir))
        {
            return Optional.empty();
        }
        try (Stream<Path> children = Files.list(installDir))
        {
            return children.filter(Files::isDirectory)
                .map(child -> String.valueOf(child.getFileName()))
                .filter(name -> !name.startsWith(HIDDEN_PREFIX))
                .sorted(Comparator.reverseOrder())
                .filter(version -> Files.exists(binaryPath(installDir, version, osClassifier())))
                .findFirst();
        }
        catch (IOException e)
        {
            return Optional.empty();
        }
    }

    /**
     * Deletes the whole managed BSL Language Server distribution under {@code stateDir/bsl-ls}, including
     * every version directory and the upstream {@code SERVER-INFO} bookkeeping file, so the next
     * {@link #ensureServer} call downloads and unpacks it again from scratch.
     *
     * <p>A no-op when nothing is installed there, so callers - for example a preferences page "delete
     * downloaded engine" button - can invoke this unconditionally without first checking
     * {@link #isInstalled}.
     *
     * @param stateDir the plugin state directory the server was (or would be) unpacked under, not
     *     {@code null}
     * @throws IOException if a file or directory under {@code stateDir/bsl-ls} cannot be deleted
     */
    public static void deleteServer(Path stateDir) throws IOException
    {
        deleteRecursively(stateDir.resolve(SERVER_DIR));
    }

    /**
     * Ensures a BSL Language Server distribution matching {@code channel} is installed under
     * {@code stateDir/bsl-ls} and returns its launcher executable.
     *
     * <p>{@code FIXED} never touches the GitHub API: when the pinned {@link #VERSION} launcher is already on
     * disk it is returned as is, otherwise the pinned asset is streamed from its release-download URL through
     * {@code download} and unpacked. {@code STABLE} and {@code PRERELEASE} delegate to the upstream
     * {@link BslLanguageServerDownloader}, which resolves the release, throttles its own update checks
     * ({@code SERVER-INFO}, 8 minutes), downloads only on a version change and removes superseded versions.
     *
     * <p>An update check must never harden into a hard failure. If the upstream call fails - an
     * {@link IOException} (offline, HTTP error) or an unchecked exception from an unexpected response body -
     * an already-installed engine is returned; if nothing is installed the pinned {@link #VERSION} floor is
     * downloaded instead. Only if that floor download also fails does an {@link IOException} escape, so this
     * never returns a path that does not exist. Cancellation is not a failure and is never swallowed.
     *
     * <p>The whole sequence runs under {@link #INSTALL_LOCK}, serializing concurrent callers against the same
     * state directory (see the field javadoc); a caller blocked waiting for the lock still observes monitor
     * cancellation promptly, via {@link #acquireInstallLock(IProgressMonitor)}, even while another caller is
     * mid-download.
     *
     * @param stateDir the plugin state directory to unpack under, not {@code null}
     * @param download the source of the pinned asset bytes, not {@code null}; unused unless the pinned floor
     *     is installed
     * @param channel the engine update channel controlling which release is installed, not {@code null}
     * @param monitor the progress monitor checked for cancellation, or {@code null}
     * @return the path to an existing launcher executable, never {@code null}
     * @throws IOException if the archive cannot be read, an entry escapes the target directory, the pinned
     *     floor cannot be installed, or the calling thread is interrupted while waiting for
     *     {@link #INSTALL_LOCK}
     * @throws OperationCanceledException if the monitor is cancelled while waiting for the lock, before, or
     *     during downloading or unpacking
     */
    public static Path ensureServer(Path stateDir, DownloadFunction download, BslUpdateChannel channel,
        IProgressMonitor monitor) throws IOException
    {
        acquireInstallLock(monitor);
        try
        {
            Path installDir = stateDir.resolve(SERVER_DIR);
            if (monitor != null && monitor.isCanceled())
            {
                throw new OperationCanceledException();
            }
            if (channel == BslUpdateChannel.FIXED)
            {
                return ensureFixedInstall(installDir, download, monitor);
            }
            return ensureManagedInstall(installDir, download, channel, monitor);
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
     * <p>The file's location differs per operating system, jpackage layout ({@code app/...} on Windows,
     * {@code lib/app/...} on Linux, {@code Contents/app/...} on macOS) and installed version, so rather than
     * hardcoding a relative path this walks the whole {@code stateDir/bsl-ls} tree looking for a file named
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
     * Builds the launcher path of an installed version, in the layout the upstream downloader uses (and that
     * {@link #ensureFixedInstall} reproduces for the pinned floor).
     *
     * <p>Package-private, and taking the operating-system classifier as an argument rather than reading
     * {@code os.name}, so the headless test fragment can pin every platform's layout.
     *
     * @param installDir the {@code bsl-ls} directory holding the version directories, not {@code null}
     * @param version the installed version, the name of its directory under {@code installDir}, not
     *     {@code null}
     * @param osClassifier the operating-system classifier, one of {@code win}, {@code mac}, {@code nix}, not
     *     {@code null}
     * @return the launcher path, never {@code null}
     */
    static Path binaryPath(Path installDir, String version, String osClassifier)
    {
        Path versionDir = installDir.resolve(version);
        if (OS_WINDOWS.equals(osClassifier))
        {
            return versionDir.resolve(APP_DIR).resolve(EXE_WINDOWS);
        }
        if (OS_MAC.equals(osClassifier))
        {
            return versionDir.resolve(APP_DIR_MAC).resolve(CONTENTS_DIR).resolve(MACOS_DIR).resolve(EXE_OTHER);
        }
        return versionDir.resolve(APP_DIR).resolve(BIN_DIR).resolve(EXE_OTHER);
    }

    /**
     * Runs a managed install attempt and degrades to the offline fallbacks when it fails.
     *
     * <p>Package-private and taking the upstream call as a seam, so the headless test fragment can exercise
     * every fallback branch without a network round trip. The contract, in order: a launcher the attempt
     * produced <em>and that exists</em> wins; an {@link OperationCanceledException} propagates untouched
     * (cancelling an update check is a user decision, not a failure to paper over); any other failure -
     * checked or unchecked - falls back to an already-installed engine, and failing that to the pinned
     * {@link #VERSION} floor, whose own {@link IOException} is allowed out so that a non-existent path is
     * never returned.
     *
     * @param managed the upstream install attempt, not {@code null}
     * @param installedBinary the network-free lookup of an already-installed launcher, not {@code null}
     * @param installDir the {@code bsl-ls} directory, not {@code null}
     * @param download the source of the pinned asset bytes for the floor, not {@code null}
     * @param monitor the progress monitor checked for cancellation, or {@code null}
     * @return the path to an existing launcher executable, never {@code null}
     * @throws IOException if the pinned floor itself cannot be installed
     * @throws OperationCanceledException if the monitor is cancelled
     */
    static Path installWithFallback(ManagedInstall managed, Supplier<Optional<Path>> installedBinary,
        Path installDir, DownloadFunction download, IProgressMonitor monitor) throws IOException
    {
        try
        {
            Path binary = managed.install();
            if (binary != null && Files.exists(binary))
            {
                return binary;
            }
        }
        catch (OperationCanceledException e)
        {
            throw e;
        }
        catch (IOException | RuntimeException e)
        {
            // An update check must never break the analysis: fall through to the offline fallbacks below.
        }
        Optional<Path> installed = installedBinary.get();
        if (installed.isPresent())
        {
            return installed.get();
        }
        return ensureFixedInstall(installDir, download, monitor);
    }

    /**
     * Delegates to the upstream {@link BslLanguageServerDownloader} for a managed channel, wrapped in the
     * fallback chain of {@link #installWithFallback}.
     *
     * @param installDir the {@code bsl-ls} directory to install under, not {@code null}
     * @param download the source of the pinned asset bytes for the floor, not {@code null}
     * @param channel the engine update channel, not {@code null} and not {@code FIXED}
     * @param monitor the progress monitor checked for cancellation, or {@code null}
     * @return the path to an existing launcher executable, never {@code null}
     * @throws IOException if the upstream call fails and the pinned floor cannot be installed either
     * @throws OperationCanceledException if the monitor is cancelled
     */
    private static Path ensureManagedInstall(Path installDir, DownloadFunction download, BslUpdateChannel channel,
        IProgressMonitor monitor) throws IOException
    {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        try
        {
            BslLanguageServerDownloader downloader =
                new BslLanguageServerDownloader(installDir, new GitHubReleaseClient(null, httpClient), httpClient);
            BslLanguageServerReleaseChannel upstreamChannel = toUpstreamChannel(channel);
            return installWithFallback(() -> downloader.downloadIfNeeded(upstreamChannel, progressListener(monitor)),
                downloader::installedBinary, installDir, download, monitor);
        }
        finally
        {
            // shutdownNow, not close: every byte we care about is already on disk by now, and close() blocks
            // until in-flight operations finish - it must never be able to stall an analysis job.
            httpClient.shutdownNow();
        }
    }

    /**
     * Maps our preference-facing channel onto the upstream one, which knows no pinned channel.
     *
     * @param channel the engine update channel, not {@code null} and not {@code FIXED}
     * @return the upstream channel, never {@code null}
     */
    private static BslLanguageServerReleaseChannel toUpstreamChannel(BslUpdateChannel channel)
    {
        return channel == BslUpdateChannel.PRERELEASE ? BslLanguageServerReleaseChannel.PRERELEASE
            : BslLanguageServerReleaseChannel.STABLE;
    }

    /**
     * Bridges an {@link IProgressMonitor} into the upstream download loop.
     *
     * <p>The upstream listener contract explicitly allows an implementation to throw a runtime exception to
     * cancel the download - the exception propagates out of the download call and the partial file is
     * deleted - which is exactly what an {@link OperationCanceledException} does here. The listener is
     * invoked once per 256 KB chunk, so it stays allocation-light and reports the download sub-task only
     * once, on the first chunk, rather than on every callback.
     *
     * @param monitor the progress monitor checked for cancellation, or {@code null}
     * @return the listener to hand to the upstream downloader, never {@code null}
     */
    private static DownloadProgressListener progressListener(IProgressMonitor monitor)
    {
        if (monitor == null)
        {
            return DownloadProgressListener.NONE;
        }
        boolean[] announced = new boolean[1];
        return (bytesRead, totalBytes) ->
        {
            if (monitor.isCanceled())
            {
                throw new OperationCanceledException();
            }
            if (!announced[0])
            {
                announced[0] = true;
                monitor.subTask(Messages.BslInstaller_Downloading);
            }
        };
    }

    /**
     * Installs the pinned {@link #VERSION} without ever consulting the GitHub API.
     *
     * <p>Serves both the {@code FIXED} channel and the offline floor of {@link #installWithFallback}: when
     * the pinned launcher is already on disk it is returned untouched, otherwise the pinned asset is streamed
     * from its release-download URL through {@code download} and unpacked into
     * {@code installDir/<VERSION>/}.
     *
     * @param installDir the {@code bsl-ls} directory to install under, not {@code null}
     * @param download the source of the archive bytes, not {@code null}
     * @param monitor the progress monitor checked for cancellation, or {@code null}
     * @return the path to the existing pinned launcher, never {@code null}
     * @throws IOException if the archive cannot be read, an entry escapes the target directory, or the
     *     archive did not contain the expected launcher
     * @throws OperationCanceledException if the monitor is cancelled before or during unpacking
     */
    private static Path ensureFixedInstall(Path installDir, DownloadFunction download, IProgressMonitor monitor)
        throws IOException
    {
        Path binary = binaryPath(installDir, VERSION, osClassifier());
        if (Files.exists(binary))
        {
            return binary;
        }
        extractDistribution(installDir, download, monitor);
        if (!OS_WINDOWS.equals(osClassifier()))
        {
            // Only the launcher needs the executable bit - verified live on Linux against the 1.0.4 native
            // asset, with every other bit stripped, for both --version and a full --analyze run.
            binary.toFile().setExecutable(true, false);
        }
        if (!Files.exists(binary))
        {
            throw new IOException("BSL Language Server launcher missing after unpacking: " + binary); //$NON-NLS-1$
        }
        return binary;
    }

    /**
     * Streams the pinned asset and unpacks it into {@code installDir/<VERSION>/}, zip-slip guarded, polling
     * {@code monitor} for cancellation before and per entry.
     *
     * <p>The archive is unpacked into a staging directory and only then moved into place, so an install
     * interrupted by a cancellation, a broken connection or a crash cannot leave a half-extracted tree that
     * a later call would mistake for a complete one - which is what the {@code .complete} marker used to
     * guard before the versioned layout replaced it.
     *
     * @param installDir the {@code bsl-ls} directory to install under, not {@code null}
     * @param download the source of the archive bytes, not {@code null}
     * @param monitor the progress monitor checked for cancellation, or {@code null}
     * @throws IOException if the archive cannot be read or an entry escapes the target directory
     * @throws OperationCanceledException if the monitor is cancelled during unpacking
     */
    private static void extractDistribution(Path installDir, DownloadFunction download, IProgressMonitor monitor)
        throws IOException
    {
        Path staging = installDir.resolve(STAGING_PREFIX + VERSION);
        deleteRecursively(staging);
        Files.createDirectories(staging);
        Path normalizedRoot = staging.normalize();
        if (monitor != null)
        {
            monitor.subTask(Messages.BslInstaller_Downloading);
        }
        try (ZipInputStream zip = new ZipInputStream(download.open(downloadUrl())))
        {
            if (monitor != null)
            {
                monitor.subTask(Messages.BslInstaller_Unpacking);
            }
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
        Path versionDir = installDir.resolve(VERSION);
        deleteRecursively(versionDir);
        Files.move(staging, versionDir);
    }

    /**
     * Looks for a file named {@code bsl-language-server.cfg} anywhere under {@code installDir}, at
     * whatever depth the current jpackage layout nests it at (see {@link #configureHeap}).
     *
     * @param installDir the {@code bsl-ls} directory holding the unpacked distribution, not {@code null}
     * @return the found configuration file, or {@code null} if {@code installDir} does not exist or holds
     *     no such file
     * @throws IOException if the distribution tree cannot be walked
     */
    private static Path findCfgFile(Path installDir) throws IOException
    {
        if (!Files.isDirectory(installDir))
        {
            return null;
        }
        try (Stream<Path> walk = Files.walk(installDir))
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
     * Recursively deletes a directory tree, tolerating a directory that does not exist.
     *
     * @param dir the directory to delete, not {@code null}
     * @throws IOException if the tree cannot be walked
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
     * Builds the expected native asset file name for an operating-system classifier.
     *
     * @param osClassifier the operating-system classifier, not {@code null}
     * @return the asset file name, never {@code null}
     */
    private static String assetName(String osClassifier)
    {
        return ASSET_PREFIX + osClassifier + ZIP_SUFFIX;
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
