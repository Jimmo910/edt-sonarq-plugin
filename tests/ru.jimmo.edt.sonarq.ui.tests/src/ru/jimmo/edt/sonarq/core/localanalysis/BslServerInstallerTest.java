/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.github._1c_syntax.utils.downloader.DownloadProgressListener;

import ru.jimmo.edt.sonarq.core.analysis.DownloadFunction;

/** Tests for {@link BslServerInstaller}. */
public class BslServerInstallerTest
{
    private static final String LAUNCHER_BODY = "#!native-launcher";

    private Path stateDir;

    @Before
    public void setUp() throws IOException
    {
        stateDir = Files.createTempDirectory("sonarq-bsl-ls-test");
    }

    @After
    public void tearDown() throws IOException
    {
        try (Stream<Path> walk = Files.walk(stateDir))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static boolean isWindows()
    {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isMac()
    {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static String osClassifier()
    {
        if (isWindows())
        {
            return "win";
        }
        if (isMac())
        {
            return "mac";
        }
        return "nix";
    }

    /** The launcher path inside the asset zip, that is relative to a version directory. */
    private static String launcherEntry()
    {
        if (isWindows())
        {
            return "bsl-language-server/bsl-language-server.exe";
        }
        if (isMac())
        {
            return "bsl-language-server.app/Contents/MacOS/bsl-language-server";
        }
        return "bsl-language-server/bin/bsl-language-server";
    }

    /** The launcher path of the pinned install, that is under {@code <stateDir>/bsl-ls/<VERSION>/}. */
    private Path pinnedLauncher()
    {
        return stateDir.resolve("bsl-ls").resolve(BslServerInstaller.VERSION).resolve(launcherEntry());
    }

    private static byte[] validZip() throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes))
        {
            zip.putNextEntry(new ZipEntry("bsl-language-server/"));
            zip.closeEntry();
            // A representative bundled runtime file, which the upstream layout does not chmod.
            zip.putNextEntry(new ZipEntry("bsl-language-server/runtime/bin/java"));
            zip.write("runtime".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(launcherEntry()));
            zip.write(LAUNCHER_BODY.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static byte[] zipSlipZip() throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes))
        {
            zip.putNextEntry(new ZipEntry("../../evil.txt"));
            zip.write("pwned".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test
    public void downloadUrlPinsVersionAndNativeZip()
    {
        String url = BslServerInstaller.downloadUrl();
        assertTrue(url.contains("/v" + BslServerInstaller.VERSION + "/"));
        assertTrue("expected the " + osClassifier() + " asset in " + url,
            url.endsWith("bsl-language-server_" + osClassifier() + ".zip"));
    }

    @Test
    public void binaryPathFollowsTheUpstreamWindowsLayout()
    {
        Path installDir = Paths.get("state", "bsl-ls");

        Path binary = BslServerInstaller.binaryPath(installDir, "1.2.3", "win");

        assertEquals(installDir.resolve("1.2.3").resolve("bsl-language-server").resolve("bsl-language-server.exe"),
            binary);
    }

    @Test
    public void binaryPathFollowsTheUpstreamLinuxLayout()
    {
        Path installDir = Paths.get("state", "bsl-ls");

        Path binary = BslServerInstaller.binaryPath(installDir, "1.2.3", "nix");

        assertEquals(installDir.resolve("1.2.3")
            .resolve("bsl-language-server")
            .resolve("bin")
            .resolve("bsl-language-server"), binary);
    }

    @Test
    public void binaryPathFollowsTheUpstreamMacLayout()
    {
        Path installDir = Paths.get("state", "bsl-ls");

        Path binary = BslServerInstaller.binaryPath(installDir, "1.2.3", "mac");

        assertEquals(installDir.resolve("1.2.3")
            .resolve("bsl-language-server.app")
            .resolve("Contents")
            .resolve("MacOS")
            .resolve("bsl-language-server"), binary);
    }

    @Test
    public void fixedChannelExtractsLauncherIntoTheVersionedLayout() throws IOException
    {
        byte[] archive = validZip();
        AtomicInteger downloads = new AtomicInteger();
        DownloadFunction download = url ->
        {
            downloads.incrementAndGet();
            return new ByteArrayInputStream(archive);
        };

        Path executable =
            BslServerInstaller.ensureServer(stateDir, download, BslUpdateChannel.FIXED, new NullProgressMonitor());

        assertEquals(pinnedLauncher(), executable);
        assertTrue(Files.exists(executable));
        assertTrue(Files.isExecutable(executable));
        assertEquals(LAUNCHER_BODY, Files.readString(executable, StandardCharsets.UTF_8));
        assertEquals(1, downloads.get());
        assertEquals(Optional.of(BslServerInstaller.VERSION), BslServerInstaller.installedVersion(stateDir));
    }

    @Test
    public void fixedChannelDownloadsFromThePinnedReleaseUrlOnly() throws IOException
    {
        byte[] archive = validZip();
        AtomicInteger apiOpens = new AtomicInteger();
        AtomicInteger assetOpens = new AtomicInteger();
        DownloadFunction download = url ->
        {
            if (url.startsWith("https://api.github.com"))
            {
                apiOpens.incrementAndGet();
            }
            else
            {
                assetOpens.incrementAndGet();
                assertEquals(BslServerInstaller.downloadUrl(), url);
            }
            return new ByteArrayInputStream(archive);
        };

        BslServerInstaller.ensureServer(stateDir, download, BslUpdateChannel.FIXED, new NullProgressMonitor());

        assertEquals("the FIXED channel must resolve the pinned version without any GitHub API call", 0,
            apiOpens.get());
        assertEquals(1, assetOpens.get());
    }

    @Test
    public void secondFixedCallIsIdempotentWithoutDownload() throws IOException
    {
        byte[] archive = validZip();
        AtomicInteger downloads = new AtomicInteger();
        DownloadFunction download = url ->
        {
            downloads.incrementAndGet();
            return new ByteArrayInputStream(archive);
        };

        Path first =
            BslServerInstaller.ensureServer(stateDir, download, BslUpdateChannel.FIXED, new NullProgressMonitor());
        Path second =
            BslServerInstaller.ensureServer(stateDir, download, BslUpdateChannel.FIXED, new NullProgressMonitor());

        assertEquals(first, second);
        assertEquals(1, downloads.get());
    }

    @Test
    public void halfExtractedVersionDirectoryIsReplaced() throws IOException
    {
        byte[] archive = validZip();
        // A version directory left behind by an interrupted install: present, but without the launcher.
        Path stale = stateDir.resolve("bsl-ls").resolve(BslServerInstaller.VERSION).resolve("bsl-language-server");
        Files.createDirectories(stale);
        Files.writeString(stale.resolve("leftover.txt"), "half-extracted", StandardCharsets.UTF_8);
        AtomicInteger downloads = new AtomicInteger();
        DownloadFunction download = url ->
        {
            downloads.incrementAndGet();
            return new ByteArrayInputStream(archive);
        };

        Path executable =
            BslServerInstaller.ensureServer(stateDir, download, BslUpdateChannel.FIXED, new NullProgressMonitor());

        assertEquals(1, downloads.get());
        assertEquals(LAUNCHER_BODY, Files.readString(executable, StandardCharsets.UTF_8));
        assertFalse("the stale tree must be replaced, not merged into", Files.exists(stale.resolve("leftover.txt")));
    }

    @Test
    public void zipSlipEntryIsRejected() throws IOException
    {
        byte[] archive = zipSlipZip();
        DownloadFunction download = url -> new ByteArrayInputStream(archive);

        try
        {
            BslServerInstaller.ensureServer(stateDir, download, BslUpdateChannel.FIXED, new NullProgressMonitor());
            fail("expected IOException for zip-slip entry");
        }
        catch (IOException e)
        {
            // expected
        }
        assertFalse("nothing must be written outside the install directory",
            Files.exists(stateDir.resolve("evil.txt")));
    }

    @Test
    public void cancelledMonitorAborts() throws IOException
    {
        byte[] archive = validZip();
        DownloadFunction download = url -> new ByteArrayInputStream(archive);
        NullProgressMonitor monitor = new NullProgressMonitor();
        monitor.setCanceled(true);

        try
        {
            BslServerInstaller.ensureServer(stateDir, download, BslUpdateChannel.FIXED, monitor);
            fail("expected OperationCanceledException for a cancelled monitor");
        }
        catch (OperationCanceledException e)
        {
            // expected
        }
    }

    @Test
    public void concurrentEnsureServerCallsInstallOnlyOnce() throws Exception
    {
        byte[] archive = validZip();
        AtomicInteger downloads = new AtomicInteger();
        CountDownLatch downloadStarted = new CountDownLatch(1);
        CountDownLatch releaseDownload = new CountDownLatch(1);
        // The second call can only ever reach this callback if the lock failed to serialize the two
        // callers (both would then observe "not installed" and both would download).
        DownloadFunction download = url ->
        {
            downloads.incrementAndGet();
            downloadStarted.countDown();
            try
            {
                releaseDownload.await(5, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            return new ByteArrayInputStream(archive);
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try
        {
            Future<Path> first = executor.submit(
                () -> BslServerInstaller.ensureServer(stateDir, download, BslUpdateChannel.FIXED,
                    new NullProgressMonitor()));
            assertTrue("first call should have started downloading", downloadStarted.await(5, TimeUnit.SECONDS));

            // The second caller must block on the install lock (held by the first, still mid-download)
            // rather than racing it; releasing the first only after submitting the second means the second
            // call's tryLock attempts genuinely overlap with the first holding the lock.
            Future<Path> second = executor.submit(
                () -> BslServerInstaller.ensureServer(stateDir, download, BslUpdateChannel.FIXED,
                    new NullProgressMonitor()));
            releaseDownload.countDown();

            Path firstPath = first.get(10, TimeUnit.SECONDS);
            Path secondPath = second.get(10, TimeUnit.SECONDS);

            assertEquals(firstPath, secondPath);
            assertEquals(1, downloads.get());
        }
        finally
        {
            executor.shutdownNow();
        }
    }

    @Test
    public void cancelledMonitorWhileLockHeldAbortsWithoutDownloading() throws InterruptedException
    {
        DownloadFunction download = url ->
        {
            fail("must not download when the monitor is already cancelled");
            return null;
        };
        NullProgressMonitor monitor = new NullProgressMonitor();
        monitor.setCanceled(true);

        // Hold the lock from this thread (reentrant, so this does not itself block) to prove the
        // cancellation check runs before ensureServer ever attempts to proceed with the install.
        BslServerInstaller.INSTALL_LOCK.lock();
        try
        {
            BslServerInstaller.ensureServer(stateDir, download, BslUpdateChannel.FIXED, monitor);
            fail("expected OperationCanceledException");
        }
        catch (OperationCanceledException e)
        {
            // expected
        }
        catch (IOException e)
        {
            fail("expected OperationCanceledException, got IOException: " + e.getMessage());
        }
        finally
        {
            BslServerInstaller.INSTALL_LOCK.unlock();
        }
    }

    @Test
    public void managedFailureWithSomethingInstalledReturnsInstalledExecutable() throws IOException
    {
        Path installed = fakeInstall("9.9.9");
        DownloadFunction download = url ->
        {
            fail("the pinned floor must not be downloaded while an engine is installed");
            return null;
        };

        Path result = BslServerInstaller.installWithFallback(() ->
        {
            throw new IOException("offline");
        }, () -> Optional.of(installed), stateDir.resolve("bsl-ls"), download, new NullProgressMonitor());

        assertEquals(installed, result);
    }

    @Test
    public void managedUncheckedFailureWithSomethingInstalledReturnsInstalledExecutable() throws IOException
    {
        Path installed = fakeInstall("9.9.9");
        DownloadFunction download = url ->
        {
            fail("the pinned floor must not be downloaded while an engine is installed");
            return null;
        };

        // A 200 response with an unexpected body shape makes the upstream JSON parsing throw unchecked; that
        // must degrade to the installed engine exactly like an IOException does.
        Path result = BslServerInstaller.installWithFallback(() ->
        {
            throw new IllegalStateException("unexpected response body");
        }, () -> Optional.of(installed), stateDir.resolve("bsl-ls"), download, new NullProgressMonitor());

        assertEquals(installed, result);
    }

    @Test
    public void managedFailureWithNothingInstalledDownloadsThePinnedFloor() throws IOException
    {
        byte[] archive = validZip();
        AtomicInteger downloads = new AtomicInteger();
        DownloadFunction download = url ->
        {
            downloads.incrementAndGet();
            assertEquals(BslServerInstaller.downloadUrl(), url);
            return new ByteArrayInputStream(archive);
        };

        Path result = BslServerInstaller.installWithFallback(() ->
        {
            throw new IOException("offline");
        }, Optional::empty, stateDir.resolve("bsl-ls"), download, new NullProgressMonitor());

        assertEquals(pinnedLauncher(), result);
        assertTrue(Files.exists(result));
        assertEquals(1, downloads.get());
    }

    @Test
    public void managedResultThatDoesNotExistFallsBackToTheFloor() throws IOException
    {
        byte[] archive = validZip();
        AtomicInteger downloads = new AtomicInteger();
        DownloadFunction download = url ->
        {
            downloads.incrementAndGet();
            return new ByteArrayInputStream(archive);
        };
        Path phantom = stateDir.resolve("bsl-ls").resolve("7.7.7").resolve("bsl-language-server");

        Path result = BslServerInstaller.installWithFallback(() -> phantom, Optional::empty,
            stateDir.resolve("bsl-ls"), download, new NullProgressMonitor());

        assertEquals("a path that does not exist must never be returned", pinnedLauncher(), result);
        assertTrue(Files.exists(result));
        assertEquals(1, downloads.get());
    }

    @Test
    public void floorFailureAfterAManagedFailurePropagates()
    {
        DownloadFunction download = url ->
        {
            throw new IOException("no network at all");
        };

        try
        {
            BslServerInstaller.installWithFallback(() ->
            {
                throw new IOException("offline");
            }, Optional::empty, stateDir.resolve("bsl-ls"), download, new NullProgressMonitor());
            fail("expected the pinned floor IOException to propagate");
        }
        catch (IOException e)
        {
            assertEquals("no network at all", e.getMessage());
        }
    }

    @Test
    public void cancellationDuringTheManagedInstallIsNeverSwallowed() throws IOException
    {
        Path installed = fakeInstall("9.9.9");
        DownloadFunction download = url ->
        {
            fail("cancellation must not fall back to a download");
            return null;
        };

        try
        {
            BslServerInstaller.installWithFallback(() ->
            {
                throw new OperationCanceledException();
            }, () -> Optional.of(installed), stateDir.resolve("bsl-ls"), download, new NullProgressMonitor());
            fail("expected OperationCanceledException to propagate");
        }
        catch (OperationCanceledException e)
        {
            // expected
        }
    }

    @Test
    public void configureHeapReplacesExistingXmxLineAndRemovesTheOldOne() throws IOException
    {
        Path cfg = stateDir.resolve("bsl-ls").resolve("1.0.4").resolve("app").resolve("bsl-language-server.cfg");
        Files.createDirectories(cfg.getParent());
        Files.writeString(cfg, "[JavaOptions]\njava-options=--other-flag\njava-options=-Xmx4g\n");

        BslServerInstaller.configureHeap(stateDir, 8);

        String content = Files.readString(cfg, StandardCharsets.UTF_8);
        assertTrue("expected the new -Xmx8g line, got: " + content, content.contains("java-options=-Xmx8g"));
        assertFalse("expected the old -Xmx4g line to be gone, got: " + content, content.contains("-Xmx4g"));
        assertTrue("expected unrelated lines to survive, got: " + content, content.contains("--other-flag"));
    }

    @Test
    public void configureHeapIsIdempotentAcrossRepeatedCalls() throws IOException
    {
        Path cfg = stateDir.resolve("bsl-ls").resolve("1.0.4").resolve("app").resolve("bsl-language-server.cfg");
        Files.createDirectories(cfg.getParent());
        Files.writeString(cfg, "[JavaOptions]\njava-options=-Xmx4g\n");

        BslServerInstaller.configureHeap(stateDir, 8);
        BslServerInstaller.configureHeap(stateDir, 8);

        List<String> lines = Files.readAllLines(cfg, StandardCharsets.UTF_8);
        long xmxLines = lines.stream().filter(line -> line.contains("-Xmx")).count();
        assertEquals(1, xmxLines);
        assertTrue(lines.contains("java-options=-Xmx8g"));
    }

    @Test
    public void configureHeapFindsCfgUnderTheVersionedLinuxLibAppLayout() throws IOException
    {
        Path cfg = stateDir.resolve("bsl-ls")
            .resolve("1.2.3")
            .resolve("bsl-language-server")
            .resolve("lib")
            .resolve("app")
            .resolve("bsl-language-server.cfg");
        Files.createDirectories(cfg.getParent());
        Files.writeString(cfg, "[JavaOptions]\njava-options=-Xmx4g\n");

        BslServerInstaller.configureHeap(stateDir, 12);

        String content = Files.readString(cfg, StandardCharsets.UTF_8);
        assertTrue(content.contains("java-options=-Xmx12g"));
        assertFalse(content.contains("-Xmx4g"));
    }

    @Test
    public void configureHeapClampsBelowMinimumToOne() throws IOException
    {
        Path cfg = stateDir.resolve("bsl-ls").resolve("1.0.4").resolve("app").resolve("bsl-language-server.cfg");
        Files.createDirectories(cfg.getParent());
        Files.writeString(cfg, "[JavaOptions]\njava-options=-Xmx4g\n");

        BslServerInstaller.configureHeap(stateDir, 0);

        String content = Files.readString(cfg, StandardCharsets.UTF_8);
        assertTrue(content.contains("java-options=-Xmx1g"));
    }

    @Test
    public void configureHeapOnMissingServerDirectoryDoesNotThrow() throws IOException
    {
        // stateDir exists but has no "bsl-ls" subdirectory at all (server never installed).
        BslServerInstaller.configureHeap(stateDir, 8);
        // No exception means success; nothing else to assert.
    }

    @Test
    public void configureHeapOnMissingCfgFileDoesNotThrow() throws IOException
    {
        Files.createDirectories(stateDir.resolve("bsl-ls").resolve("1.0.4"));
        BslServerInstaller.configureHeap(stateDir, 8);
        // No exception means success; nothing else to assert.
    }

    @Test
    public void isInstalledTrueForAnyVersionDirectoryHoldingALauncher() throws IOException
    {
        fakeInstall("9.9.9");

        assertTrue(BslServerInstaller.isInstalled(stateDir));
        assertEquals(Optional.of("9.9.9"), BslServerInstaller.installedVersion(stateDir));
    }

    @Test
    public void isInstalledFalseWhenTheVersionDirectoryHoldsNoLauncher() throws IOException
    {
        Files.createDirectories(stateDir.resolve("bsl-ls").resolve("9.9.9").resolve("bsl-language-server"));

        assertFalse(BslServerInstaller.isInstalled(stateDir));
        assertEquals(Optional.empty(), BslServerInstaller.installedVersion(stateDir));
    }

    @Test
    public void isInstalledIgnoresAStagingDirectory() throws IOException
    {
        Path staging = stateDir.resolve("bsl-ls").resolve(".staging-1.0.4").resolve(launcherEntry());
        Files.createDirectories(staging.getParent());
        Files.writeString(staging, LAUNCHER_BODY, StandardCharsets.UTF_8);

        assertFalse("a half-written staging tree must not count as installed",
            BslServerInstaller.isInstalled(stateDir));
    }

    @Test
    public void isInstalledFalseWhenNothingInstalled()
    {
        assertFalse(BslServerInstaller.isInstalled(stateDir));
        assertEquals(Optional.empty(), BslServerInstaller.installedVersion(stateDir));
    }

    @Test
    public void deleteServerRemovesEveryVersionDirectory() throws IOException
    {
        fakeInstall("9.9.9");
        fakeInstall(BslServerInstaller.VERSION);
        Files.writeString(stateDir.resolve("bsl-ls").resolve("SERVER-INFO"), "{}", StandardCharsets.UTF_8);

        BslServerInstaller.deleteServer(stateDir);

        assertFalse(Files.exists(stateDir.resolve("bsl-ls")));
        assertFalse(BslServerInstaller.isInstalled(stateDir));
    }

    @Test
    public void deleteServerReportsAnEntryItCouldNotDeleteInsteadOfClaimingSuccess() throws IOException
    {
        // POSIX unlinks an open file happily, so an undeletable entry cannot be simulated there. Probed on
        // a throwaway file, so the assumption never hides a regression in the code under test.
        assumeTrue("this platform deletes an open file; an undeletable entry cannot be simulated",
            canSimulateUndeletableFile(stateDir));
        Path launcher = fakeInstall(BslServerInstaller.VERSION);
        // A running analysis holds the launcher open, exactly like this.
        try (FileOutputStream held = new FileOutputStream(launcher.toFile(), true))
        {
            try
            {
                BslServerInstaller.deleteServer(stateDir);
                fail("expected an IOException naming the entry that could not be deleted");
            }
            catch (IOException e)
            {
                assertTrue("the failure must name the entry that could not be deleted, got: " + e.getMessage(),
                    String.valueOf(e.getMessage()).contains(launcher.getFileName().toString()));
            }
            assertTrue("the engine is still installed, so it must never be reported as deleted",
                BslServerInstaller.isInstalled(stateDir));
        }
    }

    /**
     * Tells whether holding a file open on this platform really does make its deletion fail, so a test that
     * needs an undeletable entry can skip honestly instead of asserting nothing.
     *
     * @param dir an existing directory to probe in
     * @return {@code true} if an open file cannot be deleted here
     * @throws IOException if the probe file cannot be created or cleaned up
     */
    private static boolean canSimulateUndeletableFile(Path dir) throws IOException
    {
        Path probe = Files.createTempFile(dir, "undeletable-probe", ".tmp");
        try
        {
            // Deliberately java.io, not java.nio: Files.newOutputStream opens with FILE_SHARE_DELETE on
            // Windows and would let the deletion through.
            try (FileOutputStream held = new FileOutputStream(probe.toFile(), true))
            {
                Files.deleteIfExists(probe);
                return false;
            }
            catch (IOException e)
            {
                return true;
            }
        }
        finally
        {
            Files.deleteIfExists(probe);
        }
    }

    @Test
    public void deleteServerIsNoOpWhenNothingInstalled() throws IOException
    {
        // stateDir exists but has no "bsl-ls" subdirectory at all (server never installed).
        BslServerInstaller.deleteServer(stateDir);
        // No exception means success; nothing else to assert.
    }

    @Test
    public void installedVersionComparesVersionsSemanticallyNotLexicographically() throws IOException
    {
        fakeInstall("1.0.4");
        fakeInstall("1.0.10");

        assertEquals("1.0.10 is newer than 1.0.4, even though it sorts below it as a string",
            Optional.of("1.0.10"), BslServerInstaller.installedVersion(stateDir));
    }

    @Test
    public void installedVersionPrefersAParseableVersionOverAnUnparseableDirectoryName() throws IOException
    {
        fakeInstall("nightly");
        fakeInstall("1.0.10");

        assertEquals(Optional.of("1.0.10"), BslServerInstaller.installedVersion(stateDir));
    }

    @Test
    public void installedVersionFallsBackToLexicographicOrderForUnparseableNames() throws IOException
    {
        fakeInstall("nightly-a");
        fakeInstall("nightly-b");

        assertEquals(Optional.of("nightly-b"), BslServerInstaller.installedVersion(stateDir));
    }

    @Test
    public void fixedInstallRemovesOtherVersionDirectories() throws IOException
    {
        // A version an earlier STABLE-channel run installed; switching the channel back to FIXED must not
        // leave it behind, or installedVersion would report a version the analysis does not run (and the
        // ~350 MB it occupies would leak).
        Path superseded = fakeInstall("9.9.9");
        byte[] archive = validZip();
        DownloadFunction download = url -> new ByteArrayInputStream(archive);

        Path executable =
            BslServerInstaller.ensureServer(stateDir, download, BslUpdateChannel.FIXED, new NullProgressMonitor());

        assertEquals(pinnedLauncher(), executable);
        assertTrue(Files.exists(executable));
        assertFalse("the superseded version directory must be removed", Files.exists(superseded));
        assertFalse(Files.exists(stateDir.resolve("bsl-ls").resolve("9.9.9")));
        assertEquals(Optional.of(BslServerInstaller.VERSION), BslServerInstaller.installedVersion(stateDir));
    }

    @Test
    public void configureHeapIgnoresACfgLeftBehindInAStagingTree() throws IOException
    {
        // A staging tree from an interrupted install sorts before the version directories on most file
        // systems, so an unfiltered walk would rewrite its cfg and leave the running engine at its old heap.
        Path stagingCfg = stateDir.resolve("bsl-ls")
            .resolve(".staging-" + BslServerInstaller.VERSION)
            .resolve("app")
            .resolve("bsl-language-server.cfg");
        Files.createDirectories(stagingCfg.getParent());
        Files.writeString(stagingCfg, "[JavaOptions]\njava-options=-Xmx4g\n");
        Path installedCfg = stateDir.resolve("bsl-ls")
            .resolve(BslServerInstaller.VERSION)
            .resolve("app")
            .resolve("bsl-language-server.cfg");
        Files.createDirectories(installedCfg.getParent());
        Files.writeString(installedCfg, "[JavaOptions]\njava-options=-Xmx4g\n");

        BslServerInstaller.configureHeap(stateDir, 8);

        assertTrue("the installed version's cfg must be the one rewritten",
            Files.readString(installedCfg, StandardCharsets.UTF_8).contains("java-options=-Xmx8g"));
        assertFalse("the staging tree must be left untouched",
            Files.readString(stagingCfg, StandardCharsets.UTF_8).contains("-Xmx8g"));
    }

    @Test
    public void configureHeapResolvesTheCfgOfTheCurrentlyInstalledVersionWhenTwoVersionsCoexist() throws IOException
    {
        // Two real (launcher-bearing) version directories can briefly coexist - for example right after a
        // managed-channel update, before the upstream cleanup removes the superseded one. The heap rewrite
        // must target the version installedVersion() reports as current (the newer one, semantically), not
        // whichever cfg an unscoped tree walk happens to meet first.
        fakeInstall("1.0.4");
        fakeInstall("1.0.10");
        Path supersededCfg =
            stateDir.resolve("bsl-ls").resolve("1.0.4").resolve("app").resolve("bsl-language-server.cfg");
        Files.createDirectories(supersededCfg.getParent());
        Files.writeString(supersededCfg, "[JavaOptions]\njava-options=-Xmx4g\n");
        Path currentCfg =
            stateDir.resolve("bsl-ls").resolve("1.0.10").resolve("app").resolve("bsl-language-server.cfg");
        Files.createDirectories(currentCfg.getParent());
        Files.writeString(currentCfg, "[JavaOptions]\njava-options=-Xmx4g\n");

        BslServerInstaller.configureHeap(stateDir, 8);

        assertTrue("the current version's (1.0.10) cfg must be the one rewritten",
            Files.readString(currentCfg, StandardCharsets.UTF_8).contains("java-options=-Xmx8g"));
        assertFalse("the superseded version's (1.0.4) cfg must be left untouched",
            Files.readString(supersededCfg, StandardCharsets.UTF_8).contains("-Xmx8g"));
    }

    @Test
    public void managedInstalledBinaryThatDoesNotExistFallsBackToTheFloor() throws IOException
    {
        byte[] archive = validZip();
        AtomicInteger downloads = new AtomicInteger();
        DownloadFunction download = url ->
        {
            downloads.incrementAndGet();
            return new ByteArrayInputStream(archive);
        };
        // An upstream lookup reporting a launcher that is not on disk (a deleted install it still records)
        // must not be handed out as the analysis executable.
        Path phantom = stateDir.resolve("bsl-ls").resolve("7.7.7").resolve(launcherEntry());

        Path result = BslServerInstaller.installWithFallback(() ->
        {
            throw new IOException("offline");
        }, () -> Optional.of(phantom), stateDir.resolve("bsl-ls"), download, new NullProgressMonitor());

        assertEquals(pinnedLauncher(), result);
        assertTrue(Files.exists(result));
        assertEquals(1, downloads.get());
    }

    @Test
    public void cancellationOnTheSecondCheckAbortsInsideTheExtractionLoop() throws IOException
    {
        byte[] archive = validZip();
        AtomicInteger downloads = new AtomicInteger();
        DownloadFunction download = url ->
        {
            downloads.incrementAndGet();
            return new ByteArrayInputStream(archive);
        };
        // The floor's own pre-check is the first isCanceled() call, so a monitor that only reports itself
        // cancelled from the second call on can only be caught by the per-entry check inside the unpacking
        // loop - the path the pre-checks otherwise hide.
        CountingMonitor monitor = new CountingMonitor(2);

        try
        {
            BslServerInstaller.installWithFallback(() ->
            {
                throw new IOException("offline");
            }, Optional::empty, stateDir.resolve("bsl-ls"), download, monitor);
            fail("expected OperationCanceledException from the extraction loop");
        }
        catch (OperationCanceledException e)
        {
            // expected
        }
        assertEquals("the abort must happen after the archive stream was opened, that is inside the loop", 1,
            downloads.get());
        assertFalse("a cancelled install must not leave a version directory behind",
            Files.exists(stateDir.resolve("bsl-ls").resolve(BslServerInstaller.VERSION)));
    }

    @Test
    public void progressListenerCancelsTheUpstreamDownloadWhenTheMonitorIsCancelled()
    {
        RecordingMonitor monitor = new RecordingMonitor();
        DownloadProgressListener listener = BslServerInstaller.progressListener(monitor);

        listener.onProgress(256L * 1024L, 170L * 1024L * 1024L);
        listener.onProgress(512L * 1024L, 170L * 1024L * 1024L);
        assertEquals("the download sub-task must be reported once, not per chunk", 1, monitor.subTasks.size());

        monitor.setCanceled(true);
        try
        {
            listener.onProgress(768L * 1024L, 170L * 1024L * 1024L);
            fail("expected OperationCanceledException once the monitor is cancelled");
        }
        catch (OperationCanceledException e)
        {
            // expected: the upstream downloader turns it into an aborted download with the partial file gone
        }
    }

    @Test
    public void progressListenerWithoutAMonitorIsTheUpstreamNoOp()
    {
        assertSame(DownloadProgressListener.NONE, BslServerInstaller.progressListener(null));
    }

    /**
     * Writes a launcher into the versioned layout, as either the upstream downloader or the pinned floor
     * would leave it.
     *
     * @param version the version directory name
     * @return the launcher path
     * @throws IOException if the files cannot be written
     */
    private Path fakeInstall(String version) throws IOException
    {
        Path launcher = stateDir.resolve("bsl-ls").resolve(version).resolve(launcherEntry());
        Files.createDirectories(launcher.getParent());
        Files.writeString(launcher, LAUNCHER_BODY, StandardCharsets.UTF_8);
        return launcher;
    }

    /** A monitor that reports itself cancelled only from its {@code cancelAtCall}-th cancellation check on. */
    private static final class CountingMonitor
        extends NullProgressMonitor
    {
        private final int cancelAtCall;
        private int calls;

        CountingMonitor(int cancelAtCall)
        {
            this.cancelAtCall = cancelAtCall;
        }

        @Override
        public boolean isCanceled()
        {
            calls++;
            return calls >= cancelAtCall;
        }
    }

    /** A monitor recording the sub-task names reported to it. */
    private static final class RecordingMonitor
        extends NullProgressMonitor
    {
        private final List<String> subTasks = new ArrayList<>();

        @Override
        public void subTask(String name)
        {
            subTasks.add(name);
        }
    }
}
