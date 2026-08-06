/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    public void deleteServerIsNoOpWhenNothingInstalled() throws IOException
    {
        // stateDir exists but has no "bsl-ls" subdirectory at all (server never installed).
        BslServerInstaller.deleteServer(stateDir);
        // No exception means success; nothing else to assert.
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
}
