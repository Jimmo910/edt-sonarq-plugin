/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.client;

/**
 * SonarQube server connection settings.
 *
 * <p>{@link #toString()} redacts the token: the record is passed around freely (it is the key of the shared
 * client holder and a field of the analysis request), and the default record {@code toString} would print
 * the user's SonarQube token verbatim into whatever log line or status message ever interpolates it
 * (review minor M3).
 *
 * @param baseUrl the server URL without a trailing slash, not {@code null}
 * @param token the user token, not {@code null}, may be empty
 * @param timeoutSeconds the request timeout in seconds, positive
 */
public record SonarConnection(String baseUrl, String token, int timeoutSeconds)
{
    /**
     * Creates a connection normalizing the base URL.
     *
     * @param baseUrl the server URL, not {@code null}
     * @param token the user token, not {@code null}
     * @param timeoutSeconds the request timeout in seconds, positive
     * @return the connection, never {@code null}
     */
    public static SonarConnection of(String baseUrl, String token, int timeoutSeconds)
    {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl; //$NON-NLS-1$
        return new SonarConnection(normalized, token, timeoutSeconds);
    }

    /**
     * Returns a debug description of these settings with the token redacted; the URL and the timeout, which
     * are exactly what a connection problem has to be diagnosed from, stay visible.
     *
     * @return the description, never {@code null}
     */
    @Override
    public String toString()
    {
        return "SonarConnection[baseUrl=" + baseUrl //$NON-NLS-1$
            + ", token=" + describeToken() //$NON-NLS-1$
            + ", timeoutSeconds=" + timeoutSeconds + ']'; //$NON-NLS-1$
    }

    private String describeToken()
    {
        return token == null || token.isEmpty() ? "<none>" : "***"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
