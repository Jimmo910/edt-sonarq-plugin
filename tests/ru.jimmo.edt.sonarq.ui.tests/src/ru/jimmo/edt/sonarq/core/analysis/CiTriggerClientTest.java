/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
}
