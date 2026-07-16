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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.jimmo.edt.sonarq.core.analysis.DownloadFunction;

/** Tests for {@link BslServerInstaller}. */
public class BslServerInstallerTest
{
    private static final byte[] JAR_BYTES = "PK-fake-jar-bytes".getBytes(StandardCharsets.UTF_8);

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

    @Test
    public void downloadUrlPinsVersionAndExecJar()
    {
        String url = BslServerInstaller.downloadUrl();
        assertTrue(url.contains(BslServerInstaller.VERSION));
        assertTrue(url.endsWith("bsl-language-server-" + BslServerInstaller.VERSION + "-exec.jar"));
        assertTrue(url.contains("/v" + BslServerInstaller.VERSION + "/"));
    }

    @Test
    public void downloadsJarIntoBslLsDirectory() throws IOException
    {
        AtomicInteger downloads = new AtomicInteger();
        DownloadFunction download = url ->
        {
            downloads.incrementAndGet();
            return new ByteArrayInputStream(JAR_BYTES);
        };

        Path jar = BslServerInstaller.ensureServer(stateDir, download, new NullProgressMonitor());

        assertTrue(Files.exists(jar));
        assertEquals(stateDir.resolve("bsl-ls").resolve(BslServerInstaller.jarName()), jar);
        assertEquals("PK-fake-jar-bytes", Files.readString(jar, StandardCharsets.UTF_8));
        assertEquals(1, downloads.get());
    }

    @Test
    public void secondCallIsIdempotentWithoutDownload() throws IOException
    {
        AtomicInteger downloads = new AtomicInteger();
        DownloadFunction download = url ->
        {
            downloads.incrementAndGet();
            return new ByteArrayInputStream(JAR_BYTES);
        };

        Path first = BslServerInstaller.ensureServer(stateDir, download, new NullProgressMonitor());
        Path second = BslServerInstaller.ensureServer(stateDir, download, new NullProgressMonitor());

        assertEquals(first, second);
        assertEquals(1, downloads.get());
    }

    @Test
    public void preCancelledMonitorAbortsBeforeDownload()
    {
        AtomicInteger downloads = new AtomicInteger();
        DownloadFunction download = url ->
        {
            downloads.incrementAndGet();
            return new ByteArrayInputStream(JAR_BYTES);
        };
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
        catch (IOException e)
        {
            fail("unexpected IOException: " + e);
        }
        assertEquals("download must not start once already cancelled", 0, downloads.get());
    }

    @Test
    public void cancellationDuringCopyRemovesPartialFile()
    {
        DownloadFunction download = url -> new ByteArrayInputStream(new byte[512 * 1024]);
        // Not cancelled on the pre-check, cancelled once the chunked copy loop starts polling.
        NullProgressMonitor monitor = new NullProgressMonitor()
        {
            private int checks;

            @Override
            public boolean isCanceled()
            {
                checks++;
                return checks > 1;
            }
        };

        try
        {
            BslServerInstaller.ensureServer(stateDir, download, monitor);
            fail("expected OperationCanceledException during copy");
        }
        catch (OperationCanceledException e)
        {
            // expected
        }
        catch (IOException e)
        {
            fail("unexpected IOException: " + e);
        }

        Path serverDir = stateDir.resolve("bsl-ls");
        assertFalse(Files.exists(serverDir.resolve(BslServerInstaller.jarName())));
        assertFalse(Files.exists(serverDir.resolve(BslServerInstaller.jarName() + ".part")));
    }
}
