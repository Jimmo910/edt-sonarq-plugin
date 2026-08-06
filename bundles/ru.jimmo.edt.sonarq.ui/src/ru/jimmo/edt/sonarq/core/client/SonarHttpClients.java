/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.client;

/**
 * Hands out the shared {@link SonarHttpClient} of a connection configuration, so a client - and the JDK
 * {@link java.net.http.HttpClient} with the selector thread and connection pool it owns - is created once per
 * server configuration instead of once per refresh.
 *
 * <p>Before this holder every refresh (including every unattended auto-sync cycle), every "Test connection"
 * click and every project search built a client of its own and dropped it on the floor, leaving its teardown
 * to the garbage collector. Reuse also keeps the authentication scheme the client discovered - a server that
 * rejects {@code Bearer} makes {@link SonarHttpClient} fall back to HTTP Basic after one extra round trip -
 * instead of re-probing it on every refresh.
 *
 * <p>A client is only ever replaced when the connection settings actually change (URL, token or timeout); the
 * superseded one is then closed, because nothing can sensibly use it any more. That is the single case in
 * which an in-flight request can be cut short, and it is a request against a server the user has just stopped
 * pointing at. Steady-state refreshes never close anything.
 *
 * <p>Thread-safe: every method is synchronized on the class, and the returned client is itself safe for
 * concurrent use.
 */
public final class SonarHttpClients
{
    private static SonarConnection currentConnection;

    private static SonarHttpClient currentClient;

    private SonarHttpClients()
    {
    }

    /**
     * Returns the shared client of a connection, creating it on first use and reusing it afterwards.
     *
     * @param connection the connection settings, not {@code null}
     * @return the shared client for {@code connection}, never {@code null} and never closed
     */
    public static synchronized SonarHttpClient shared(SonarConnection connection)
    {
        if (currentClient != null && connection.equals(currentConnection))
        {
            return currentClient;
        }
        closeCurrent();
        currentConnection = connection;
        currentClient = new SonarHttpClient(connection);
        return currentClient;
    }

    /**
     * Closes the shared client, if any, and forgets it, so the next {@link #shared(SonarConnection)} call
     * builds a fresh one. Called when the plug-in stops, so a dynamic update or uninstall leaves no selector
     * thread behind; safe to call repeatedly and when nothing was ever created.
     */
    public static synchronized void closeAll()
    {
        closeCurrent();
    }

    private static void closeCurrent()
    {
        if (currentClient != null)
        {
            currentClient.close();
            currentClient = null;
            currentConnection = null;
        }
    }
}
