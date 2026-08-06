/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.util.Comparator;
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

/** Tests for {@link ScannerInstaller}. */
public class ScannerInstallerTest
{
    private static final String EXECUTABLE_BODY = "echo";

    private Path stateDir;

    @Before
    public void setUp() throws IOException
    {
        stateDir = Files.createTempDirectory("sonarq-scanner-test");
    }

    @After
    public void tearDown() throws IOException
    {
        try (Stream<Path> walk = Files.walk(stateDir))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static String executableEntry()
    {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String classifier;
        if (os.contains("win"))
        {
            classifier = "windows-x64";
        }
        else if (os.contains("mac"))
        {
            classifier = "macosx-aarch64";
        }
        else
        {
            classifier = "linux-x64";
        }
        String executable = os.contains("win") ? "sonar-scanner.bat" : "sonar-scanner";
        return "sonar-scanner-7.1.0.4889-" + classifier + "/bin/" + executable;
    }

    private static byte[] validZip() throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes))
        {
            zip.putNextEntry(new ZipEntry(executableEntry()));
            zip.write(EXECUTABLE_BODY.getBytes(StandardCharsets.UTF_8));
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
    public void extractsExecutableWithContent() throws IOException
    {
        byte[] archive = validZip();
        AtomicInteger downloads = new AtomicInteger();
        DownloadFunction download = url ->
        {
            downloads.incrementAndGet();
            return new ByteArrayInputStream(archive);
        };

        Path executable = ScannerInstaller.ensureScanner(stateDir, download, new NullProgressMonitor());

        assertTrue(Files.exists(executable));
        assertTrue(Files.isExecutable(executable));
        assertEquals(EXECUTABLE_BODY, Files.readString(executable, StandardCharsets.UTF_8));
        assertEquals(1, downloads.get());
        assertTrue(Files.exists(stateDir.resolve("scanner").resolve(".complete")));
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

        Path first = ScannerInstaller.ensureScanner(stateDir, download, new NullProgressMonitor());
        Path second = ScannerInstaller.ensureScanner(stateDir, download, new NullProgressMonitor());

        assertEquals(first, second);
        assertEquals(1, downloads.get());
        assertTrue(Files.exists(stateDir.resolve("scanner").resolve(".complete")));
    }

    @Test
    public void partialExtractionWithoutMarkerIsRedownloaded() throws IOException
    {
        byte[] archive = validZip();
        Path scannerRoot = stateDir.resolve("scanner");
        Path staleExecutable = scannerRoot.resolve(executableEntry());
        Files.createDirectories(staleExecutable.getParent());
        Files.writeString(staleExecutable, "stale-half-extracted", StandardCharsets.UTF_8);
        AtomicInteger downloads = new AtomicInteger();
        DownloadFunction download = url ->
        {
            downloads.incrementAndGet();
            return new ByteArrayInputStream(archive);
        };

        Path executable = ScannerInstaller.ensureScanner(stateDir, download, new NullProgressMonitor());

        assertEquals(1, downloads.get());
        assertEquals(EXECUTABLE_BODY, Files.readString(executable, StandardCharsets.UTF_8));
        assertTrue(Files.exists(scannerRoot.resolve(".complete")));
    }

    @Test
    public void zipSlipEntryIsRejected() throws IOException
    {
        byte[] archive = zipSlipZip();
        DownloadFunction download = url -> new ByteArrayInputStream(archive);

        try
        {
            ScannerInstaller.ensureScanner(stateDir, download, new NullProgressMonitor());
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
            ScannerInstaller.ensureScanner(stateDir, download, monitor);
            fail("expected OperationCanceledException for a cancelled monitor");
        }
        catch (OperationCanceledException e)
        {
            // expected
        }
    }

    @Test
    public void leftoverThatCannotBeDeletedIsReportedInsteadOfExtractedInto() throws IOException
    {
        // POSIX unlinks an open file happily, so an undeletable entry cannot be simulated there. Probed on
        // a throwaway file, so the assumption never hides a regression in the code under test.
        assumeTrue("this platform deletes an open file; an undeletable entry cannot be simulated",
            canSimulateUndeletableFile(stateDir));
        byte[] archive = validZip();
        Path scannerRoot = stateDir.resolve("scanner");
        Path staleExecutable = scannerRoot.resolve(executableEntry());
        Files.createDirectories(staleExecutable.getParent());
        Files.writeString(staleExecutable, "stale-half-extracted", StandardCharsets.UTF_8);
        AtomicInteger downloads = new AtomicInteger();
        DownloadFunction download = url ->
        {
            downloads.incrementAndGet();
            return new ByteArrayInputStream(archive);
        };
        // A concurrent run holds the leftover open, exactly like this.
        try (FileOutputStream held = new FileOutputStream(staleExecutable.toFile(), true))
        {
            try
            {
                ScannerInstaller.ensureScanner(stateDir, download, new NullProgressMonitor());
                fail("expected an IOException naming the entry that could not be deleted");
            }
            catch (IOException e)
            {
                assertTrue("the failure must name the entry that could not be deleted, got: " + e.getMessage(),
                    String.valueOf(e.getMessage()).contains(staleExecutable.getFileName().toString()));
            }
            // The discriminating assertion: a swallowed delete failure would have carried on and extracted
            // on top of a tree it only believed it had cleaned.
            assertEquals("the failed cleanup must abort before the archive is fetched", 0, downloads.get());
            assertEquals("the poisoned install must not be extracted into", "stale-half-extracted",
                Files.readString(staleExecutable, StandardCharsets.UTF_8));
            assertFalse("a failed install must not leave a completion marker",
                Files.exists(scannerRoot.resolve(".complete")));
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
    public void concurrentEnsureScannerCallsInstallOnlyOnce() throws Exception
    {
        byte[] archive = validZip();
        AtomicInteger downloads = new AtomicInteger();
        CountDownLatch downloadStarted = new CountDownLatch(1);
        CountDownLatch releaseDownload = new CountDownLatch(1);
        // The second call can only ever reach this callback if the lock failed to serialize the two
        // callers (both would then observe "not installed", both would delete the same tree and both would
        // extract into it).
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
                () -> ScannerInstaller.ensureScanner(stateDir, download, new NullProgressMonitor()));
            assertTrue("first call should have started downloading", downloadStarted.await(5, TimeUnit.SECONDS));

            // The second caller must block on the install lock (held by the first, still mid-download)
            // rather than racing it; releasing the first only after submitting the second means the second
            // call's tryLock attempts genuinely overlap with the first holding the lock.
            Future<Path> second = executor.submit(
                () -> ScannerInstaller.ensureScanner(stateDir, download, new NullProgressMonitor()));
            releaseDownload.countDown();

            Path firstPath = first.get(10, TimeUnit.SECONDS);
            Path secondPath = second.get(10, TimeUnit.SECONDS);

            assertEquals(firstPath, secondPath);
            assertEquals(1, downloads.get());
            assertEquals(EXECUTABLE_BODY, Files.readString(firstPath, StandardCharsets.UTF_8));
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
        // cancellation check runs before ensureScanner ever attempts to proceed with the install.
        ScannerInstaller.INSTALL_LOCK.lock();
        try
        {
            ScannerInstaller.ensureScanner(stateDir, download, monitor);
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
            ScannerInstaller.INSTALL_LOCK.unlock();
        }
    }
}
