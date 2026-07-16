/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

import ru.jimmo.edt.sonarq.core.model.CeTask;
import ru.jimmo.edt.sonarq.core.model.IssueQuery;
import ru.jimmo.edt.sonarq.core.model.IssuesPage;

/** Tests for {@link SonarHttpClient} against an in-process HTTP server. */
public class SonarHttpClientTest
{
    private HttpServer server;
    private String baseUrl;
    private final List<String> authHeaders = new ArrayList<>();

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

    private void respond(String path, int status, String body)
    {
        server.createContext(path, exchange ->
        {
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private SonarHttpClient client()
    {
        return new SonarHttpClient(SonarConnection.of(baseUrl + "/", "secret-token", 10));
    }

    @Test
    public void sendsBearerTokenAndReturnsVersion() throws Exception
    {
        respond("/api/server/version", 200, "10.4.1");
        assertEquals("10.4.1", client().serverVersion());
        assertEquals("Bearer secret-token", authHeaders.get(0));
    }

    @Test
    public void fallsBackToBasicAuthOn401() throws Exception
    {
        server.createContext("/api/server/version", exchange ->
        {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authHeaders.add(auth);
            int status = auth != null && auth.startsWith("Basic ") ? 200 : 401;
            byte[] bytes = "9.9".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        assertEquals("9.9", client().serverVersion());
        assertEquals(2, authHeaders.size());
        String expected = "Basic " + Base64.getEncoder()
            .encodeToString("secret-token:".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, authHeaders.get(1));
    }

    @Test
    public void retriesOncePerCallAndSticksToBasicAfterwards() throws Exception
    {
        server.createContext("/api/server/version", exchange ->
        {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authHeaders.add(auth);
            int status = auth != null && auth.startsWith("Basic ") ? 200 : 401;
            byte[] bytes = "9.9".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        SonarHttpClient sharedClient = client();
        assertEquals("9.9", sharedClient.serverVersion());
        assertEquals(2, authHeaders.size());
        assertEquals("9.9", sharedClient.serverVersion());
        assertEquals(3, authHeaders.size());
        assertTrue(authHeaders.get(2).startsWith("Basic "));
    }

    @Test
    public void errorStatusRaisesExceptionWithCode()
    {
        respond("/api/server/version", 500, "boom");
        try
        {
            client().serverVersion();
            fail("expected SonarServerException");
        }
        catch (SonarServerException e)
        {
            assertEquals(500, e.getStatusCode());
        }
    }

    @Test
    public void searchIssuesPassesBranchAndPaging() throws Exception
    {
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/api/issues/search", exchange ->
        {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] bytes = """
                { "paging": { "pageIndex": 2, "pageSize": 500, "total": 1 }, "issues": [] }"""
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        IssuesPage page = client().searchIssuesPage(new IssueQuery("my:key", "feature/x"), 2);
        assertEquals(2, page.pageIndex());
        assertTrue(query.get().contains("componentKeys=my%3Akey"));
        assertTrue(query.get().contains("branch=feature%2Fx"));
        assertTrue(query.get().contains("resolved=false"));
        assertTrue(query.get().contains("ps=500"));
        assertTrue(query.get().contains("p=2"));
    }

    @Test
    public void branchesReturnEmptyListOn404() throws Exception
    {
        respond("/api/project_branches/list", 404, "{\"errors\":[]}");
        assertTrue(client().listBranches("my:key").isEmpty());
    }

    @Test
    public void serverLanguagesHitsLanguagesListEndpoint() throws Exception
    {
        respond("/api/languages/list", 200,
            "{\"languages\":[{\"key\":\"bsl\",\"name\":\"BSL\"},{\"key\":\"java\",\"name\":\"Java\"}]}");
        Set<String> languages = client().serverLanguages();
        assertEquals(2, languages.size());
        assertTrue(languages.contains("bsl"));
        assertTrue(languages.contains("java"));
    }

    @Test
    public void ceTaskStatusHitsCeTaskEndpointWithEncodedId() throws Exception
    {
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/api/ce/task", exchange ->
        {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] bytes = "{\"task\":{\"status\":\"SUCCESS\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        CeTask task = client().ceTaskStatus("task id/1");
        assertTrue(task.success());
        assertEquals("id=task+id%2F1", query.get());
    }
}
