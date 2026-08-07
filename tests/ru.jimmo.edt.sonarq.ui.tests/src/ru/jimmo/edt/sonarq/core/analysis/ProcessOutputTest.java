/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Tests for {@link ProcessOutput}, the concurrent output drain of review minor M6.
 *
 * <p>No real process is spawned: what is under test is the ordering between reading a process's output and
 * waiting for its exit, and that is modelled more precisely - and far more cheaply - by a fake process that
 * behaves the way the operating system makes a real one behave. A child whose output pipe is full blocks on
 * its next write, so it cannot exit until somebody reads; {@link PipeBufferedProcess} likewise refuses to
 * report an exit until its stream has been drained past the buffer size it models. Reading only after
 * {@code waitFor} therefore times out against it, exactly as it did against a real chatty launcher.
 */
public class ProcessOutputTest
{
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int PIPE_BUFFER = 4096;
    private static final String VERSION_LINE = "version: 1.0.4 BSL Language Server";

    /** A process that cannot exit until its output has been read past {@code bufferSize} bytes. */
    private static class PipeBufferedProcess extends Process
    {
        final AtomicInteger consumed = new AtomicInteger();
        final AtomicBoolean destroyed = new AtomicBoolean();

        private final byte[] output;
        private final int bufferSize;

        PipeBufferedProcess(byte[] output, int bufferSize)
        {
            this.output = output;
            this.bufferSize = bufferSize;
        }

        @Override
        public InputStream getInputStream()
        {
            ByteArrayInputStream source = new ByteArrayInputStream(output);
            return new InputStream()
            {
                @Override
                public int read()
                {
                    int value = source.read();
                    if (value >= 0)
                    {
                        consumed.incrementAndGet();
                    }
                    return value;
                }

                @Override
                public int read(byte[] buffer, int off, int len)
                {
                    int read = source.read(buffer, off, len);
                    if (read > 0)
                    {
                        consumed.addAndGet(read);
                    }
                    return read;
                }
            };
        }

        @Override
        public OutputStream getOutputStream()
        {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getErrorStream()
        {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException
        {
            while (!exited())
            {
                Thread.sleep(5L);
            }
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException
        {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (System.nanoTime() < deadline)
            {
                if (exited())
                {
                    return true;
                }
                Thread.sleep(5L);
            }
            return exited();
        }

        @Override
        public int exitValue()
        {
            if (!exited())
            {
                throw new IllegalThreadStateException();
            }
            return 0;
        }

        @Override
        public void destroy()
        {
            destroyed.set(true);
        }

        @Override
        public boolean isAlive()
        {
            return !exited();
        }

        @Override
        public Stream<ProcessHandle> descendants()
        {
            // The default implementation goes through toHandle(), which a fake process cannot provide.
            return Stream.empty();
        }

        boolean exited()
        {
            return destroyed.get() || consumed.get() >= Math.min(bufferSize, output.length);
        }
    }

    /** A process that prints nothing and never exits on its own; only a kill ends it. */
    private static final class HungProcess extends PipeBufferedProcess
    {
        HungProcess()
        {
            super(new byte[0], Integer.MAX_VALUE);
        }

        @Override
        public InputStream getInputStream()
        {
            return new InputStream()
            {
                @Override
                public int read() throws IOException
                {
                    while (!destroyed.get())
                    {
                        sleepBriefly();
                    }
                    // The pipe reaches end of file when the process dies.
                    return -1;
                }
            };
        }

        @Override
        boolean exited()
        {
            return destroyed.get();
        }
    }

    private static void sleepBriefly() throws IOException
    {
        try
        {
            Thread.sleep(10L);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private static byte[] chatter(int lines)
    {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lines; i++)
        {
            text.append("Picked up _JAVA_OPTIONS: -Xmx4g (warning line ").append(i).append(')')
                .append(System.lineSeparator());
        }
        text.append(VERSION_LINE).append(System.lineSeparator());
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The regression: an executable printing more than one pipe buffer on {@code --version} used to hang
     * until the bounded wait gave up, and the hang was then reported to the user as a timeout.
     */
    @Test
    public void capturesOutputLargerThanThePipeBuffer() throws Exception
    {
        byte[] chatty = chatter(200);
        assertTrue("the fixture must exceed the modelled pipe buffer", chatty.length > PIPE_BUFFER);
        PipeBufferedProcess process = new PipeBufferedProcess(chatty, PIPE_BUFFER);

        Optional<String> captured = ProcessOutput.awaitMergedOutput(process, TIMEOUT, StandardCharsets.UTF_8);

        assertTrue("output larger than the pipe buffer must not be reported as a timeout", captured.isPresent());
        assertTrue(captured.get(), captured.get().contains(VERSION_LINE));
        assertFalse("a process that exited on its own must not be killed", process.destroyed.get());
    }

    @Test
    public void capturesShortOutput() throws Exception
    {
        byte[] output = "version: 1.0.4\n".getBytes(StandardCharsets.UTF_8);

        Optional<String> captured = ProcessOutput.awaitMergedOutput(new PipeBufferedProcess(output, PIPE_BUFFER),
            TIMEOUT, StandardCharsets.UTF_8);

        assertEquals("version: 1.0.4\n", captured.orElseThrow());
    }

    /** A process that really is stuck must still be reported as a timeout - and killed, not left running. */
    @Test
    public void reportsEmptyAndTerminatesTheProcessOnTimeout() throws Exception
    {
        HungProcess stuck = new HungProcess();

        Optional<String> captured =
            ProcessOutput.awaitMergedOutput(stuck, Duration.ofMillis(200), StandardCharsets.UTF_8);

        assertTrue(captured.isEmpty());
        assertTrue("a process that outlived the timeout must be terminated", stuck.destroyed.get());
    }

    /** The captured text is capped, so a runaway program truncates instead of exhausting the heap. */
    @Test
    public void capsTheCapturedTextButKeepsDraining() throws Exception
    {
        byte[] huge = new byte[512 * 1024];
        Arrays.fill(huge, (byte)'x');
        PipeBufferedProcess process = new PipeBufferedProcess(huge, PIPE_BUFFER);

        Optional<String> captured = ProcessOutput.awaitMergedOutput(process, TIMEOUT, StandardCharsets.UTF_8);

        assertTrue(captured.isPresent());
        assertTrue("captured " + captured.get().length() + " chars", captured.get().length() <= 64 * 1024);
        assertEquals("the whole stream must still be drained", huge.length, process.consumed.get());
    }
}
