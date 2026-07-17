/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

/**
 * Opens an HTTP(S) download stream with bounded connect and read timeouts.
 *
 * <p>Used as the {@link DownloadFunction} for the managed analyzer downloads (the SonarScanner CLI and the
 * BSL Language Server distribution). A bare {@code URL.openStream()} inherits the JVM default of no
 * timeout, so a stalled connection or a server that accepts the connection but never sends data would hang
 * the download forever, with cancellation only observable between archive entries. Bounding both phases
 * lets a wedged download fail with an {@link IOException} instead.
 */
public final class TimeoutDownloads
{
    private static final int CONNECT_TIMEOUT_MILLIS = 30_000;
    private static final int READ_TIMEOUT_MILLIS = 60_000;

    private TimeoutDownloads()
    {
    }

    /**
     * Opens a stream over the given URL with connect and read timeouts applied.
     *
     * @param url the download URL, not {@code null}
     * @return the response body stream, never {@code null}
     * @throws IOException if the connection cannot be opened or times out
     */
    public static InputStream open(String url) throws IOException
    {
        URLConnection connection = new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        return connection.getInputStream();
    }
}
