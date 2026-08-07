/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

/** Tests for {@link CiTriggerClient} against an in-process HTTP server. */
public class CiTriggerClientTest
{
    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> capturedMethod = new AtomicReference<>();
    private final AtomicReference<String> capturedQuery = new AtomicReference<>();
    private final AtomicReference<String> capturedAuth = new AtomicReference<>();

    @Before
    public void setUp() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void tearDown()
    {
        server.stop(0);
    }

    private void respond(int status)
    {
        server.createContext("/trigger/pipeline", exchange ->
        {
            capturedMethod.set(exchange.getRequestMethod());
            capturedQuery.set(exchange.getRequestURI().getRawQuery());
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
    }

    @Test
    public void substitutesEveryBranchPlaceholderWithEncodedBranchAndUsesPost() throws Exception
    {
        respond(200);
        String template = baseUrl + "/trigger/pipeline?token=SECRET&ref={branch}&extra={branch}";
        new CiTriggerClient(10).trigger(template, "feature/x", null);
        assertEquals("POST", capturedMethod.get());
        assertTrue(capturedQuery.get().contains("ref=feature%2Fx"));
        assertTrue(capturedQuery.get().contains("extra=feature%2Fx"));
    }

    @Test
    public void sendsAuthorizationHeaderVerbatimWhenSet() throws Exception
    {
        respond(200);
        String template = baseUrl + "/trigger/pipeline?ref={branch}";
        new CiTriggerClient(10).trigger(template, "main", "Bearer my-secret-token");
        assertEquals("Bearer my-secret-token", capturedAuth.get());
    }

    @Test
    public void sendsNoAuthorizationHeaderWhenSecretIsNull() throws Exception
    {
        respond(200);
        String template = baseUrl + "/trigger/pipeline?ref={branch}";
        new CiTriggerClient(10).trigger(template, "main", null);
        assertNull(capturedAuth.get());
    }

    @Test
    public void sendsNoAuthorizationHeaderWhenSecretIsBlank() throws Exception
    {
        respond(200);
        String template = baseUrl + "/trigger/pipeline?ref={branch}";
        new CiTriggerClient(10).trigger(template, "main", "   ");
        assertNull(capturedAuth.get());
    }

    @Test
    public void returnsServerStatusCode() throws Exception
    {
        respond(201);
        String template = baseUrl + "/trigger/pipeline?ref={branch}";
        int status = new CiTriggerClient(10).trigger(template, "main", null);
        assertEquals(201, status);
    }

    /**
     * Review minor M2: the client owns a JDK {@code HttpClient} - a selector thread and a connection pool -
     * which used to be left to the garbage collector after every CI trigger. Closing must really release the
     * transport, not just flip a flag, so a request attempted afterwards has to fail rather than succeed
     * against the still-running server.
     */
    @Test
    public void closeReleasesTheTransport() throws Exception
    {
        respond(200);
        String template = baseUrl + "/trigger/pipeline?ref={branch}";
        CiTriggerClient client = new CiTriggerClient(10);
        assertEquals(200, client.trigger(template, "main", null));

        client.close();

        try
        {
            int status = client.trigger(template, "main", null);
            fail("a closed client must not be able to send: got HTTP " + status);
        }
        catch (IOException | RuntimeException e)
        {
            // Expected: the JDK rejects work submitted to a shut-down client. Which of the two it raises is
            // an implementation detail, so both are accepted here.
        }
    }

    /** Closing twice (an outer try-with-resources over an already closed client) must stay harmless. */
    @Test
    public void closeIsIdempotent()
    {
        CiTriggerClient client = new CiTriggerClient(10);
        client.close();
        client.close();
    }
}
