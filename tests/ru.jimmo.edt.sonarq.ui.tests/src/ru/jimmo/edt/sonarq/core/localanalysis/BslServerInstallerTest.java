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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

    private static byte[] validZip() throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes))
        {
            zip.putNextEntry(new ZipEntry("bsl-language-server/"));
            zip.closeEntry();
            // A representative bundled runtime file in a bin directory (must become executable on POSIX).
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
            zip.putNextEntry(new ZipEntry("../evil.txt"));
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
        String expectedAsset;
        if (isWindows())
        {
            expectedAsset = "bsl-language-server_win.zip";
        }
        else if (isMac())
        {
            expectedAsset = "bsl-language-server_mac.zip";
        }
        else
        {
            expectedAsset = "bsl-language-server_nix.zip";
        }
        assertTrue("expected asset " + expectedAsset + " in " + url, url.endsWith(expectedAsset));
    }

    @Test
    public void extractsLauncherWithContent() throws IOException
    {
        byte[] archive = validZip();
        AtomicInteger downloads = new AtomicInteger();
        DownloadFunction download = url ->
        {
            downloads.incrementAndGet();
            return new ByteArrayInputStream(archive);
        };

        Path executable = BslServerInstaller.ensureServer(stateDir, download, new NullProgressMonitor());

        // The launcher nests under <bsl-ls>/bsl-language-server/ per the real distribution layout.
        // Path.resolve accepts the forward-slashed entry on every platform.
        Path expected = stateDir.resolve("bsl-ls").resolve(launcherEntry());
        assertEquals(expected, executable);
        assertTrue(Files.exists(executable));
        assertTrue(Files.isExecutable(executable));
        assertEquals(LAUNCHER_BODY, Files.readString(executable, StandardCharsets.UTF_8));
        assertEquals(1, downloads.get());
        assertTrue(Files.exists(stateDir.resolve("bsl-ls").resolve(".complete")));
    }

    @Test
    public void secondCallIsIdempotentWithoutDownload() throws IOException
    {
        byte[] archive = validZip();
        AtomicInteger downloads = new AtomicInteger();
        DownloadFunction download = url ->
        {
            downloads.incrementAndGet();
            return new ByteArrayInputStream(archive);
        };

        Path first = BslServerInstaller.ensureServer(stateDir, download, new NullProgressMonitor());
        Path second = BslServerInstaller.ensureServer(stateDir, download, new NullProgressMonitor());

        assertEquals(first, second);
        assertEquals(1, downloads.get());
        assertTrue(Files.exists(stateDir.resolve("bsl-ls").resolve(".complete")));
    }

    @Test
    public void partialExtractionWithoutMarkerIsRedownloaded() throws IOException
    {
        byte[] archive = validZip();
        Path serverRoot = stateDir.resolve("bsl-ls");
        Path staleExecutable = serverRoot.resolve(launcherEntry());
        Files.createDirectories(staleExecutable.getParent());
        Files.writeString(staleExecutable, "stale-half-extracted", StandardCharsets.UTF_8);
        AtomicInteger downloads = new AtomicInteger();
        DownloadFunction download = url ->
        {
            downloads.incrementAndGet();
            return new ByteArrayInputStream(archive);
        };

        Path executable = BslServerInstaller.ensureServer(stateDir, download, new NullProgressMonitor());

        assertEquals(1, downloads.get());
        assertEquals(LAUNCHER_BODY, Files.readString(executable, StandardCharsets.UTF_8));
        assertTrue(Files.exists(serverRoot.resolve(".complete")));
    }

    @Test
    public void zipSlipEntryIsRejected() throws IOException
    {
        byte[] archive = zipSlipZip();
        DownloadFunction download = url -> new ByteArrayInputStream(archive);

        try
        {
            BslServerInstaller.ensureServer(stateDir, download, new NullProgressMonitor());
            fail("expected IOException for zip-slip entry");
        }
        catch (IOException e)
        {
            // expected
        }
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
            BslServerInstaller.ensureServer(stateDir, download, monitor);
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
                () -> BslServerInstaller.ensureServer(stateDir, download, new NullProgressMonitor()));
            assertTrue("first call should have started downloading", downloadStarted.await(5, TimeUnit.SECONDS));

            // The second caller must block on the install lock (held by the first, still mid-download)
            // rather than racing it; releasing the first only after submitting the second means the second
            // call's tryLock attempts genuinely overlap with the first holding the lock.
            Future<Path> second = executor.submit(
                () -> BslServerInstaller.ensureServer(stateDir, download, new NullProgressMonitor()));
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
    public void configureHeapReplacesExistingXmxLineAndRemovesTheOldOne() throws IOException
    {
        Path cfg = stateDir.resolve("bsl-ls").resolve("app").resolve("bsl-language-server.cfg");
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
        Path cfg = stateDir.resolve("bsl-ls").resolve("app").resolve("bsl-language-server.cfg");
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
    public void configureHeapFindsCfgUnderLinuxLibAppLayout() throws IOException
    {
        Path cfg = stateDir.resolve("bsl-ls").resolve("lib").resolve("app").resolve("bsl-language-server.cfg");
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
        Path cfg = stateDir.resolve("bsl-ls").resolve("app").resolve("bsl-language-server.cfg");
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
        Files.createDirectories(stateDir.resolve("bsl-ls").resolve("app"));
        BslServerInstaller.configureHeap(stateDir, 8);
        // No exception means success; nothing else to assert.
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
            BslServerInstaller.ensureServer(stateDir, download, monitor);
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
}
