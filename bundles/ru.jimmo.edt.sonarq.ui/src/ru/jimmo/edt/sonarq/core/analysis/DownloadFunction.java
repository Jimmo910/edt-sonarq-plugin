/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.analysis;

import java.io.IOException;
import java.io.InputStream;

/** Opens a byte stream for a download URL, abstracting the network for testability. */
@FunctionalInterface
public interface DownloadFunction
{
    /**
     * Opens a stream for the given URL.
     *
     * @param url the resource URL, not {@code null}
     * @return an open input stream, never {@code null}; the caller closes it
     * @throws IOException if the resource cannot be opened
     */
    InputStream open(String url) throws IOException;
}
