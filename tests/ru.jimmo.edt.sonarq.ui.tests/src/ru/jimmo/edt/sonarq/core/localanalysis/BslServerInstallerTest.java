/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.localanalysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
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
}
