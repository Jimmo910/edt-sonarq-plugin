/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/** Tests for {@link SonarHttpClients}. */
public class SonarHttpClientsTest
{
    private static final SonarConnection FIRST = SonarConnection.of("http://localhost:9001", "t", 10);

    private static final SonarConnection SECOND = SonarConnection.of("http://localhost:9002", "t", 10);

    @After
    public void tearDown()
    {
        SonarHttpClients.closeAll();
    }

    @Test
    public void repeatedRequestsForTheSameConnectionShareOneClient()
    {
        // The point of the holder: a refresh (and every auto-sync cycle) must not build a new JDK HttpClient,
        // and therefore a new selector thread, every time it runs.
        SonarHttpClient client = SonarHttpClients.shared(FIRST);
        assertSame(client, SonarHttpClients.shared(FIRST));
        assertSame(client, SonarHttpClients.shared(SonarConnection.of("http://localhost:9001/", "t", 10)));
        assertFalse(client.isClosed());
    }

    @Test
    public void changingTheConnectionReplacesAndClosesThePreviousClient()
    {
        SonarHttpClient first = SonarHttpClients.shared(FIRST);
        SonarHttpClient second = SonarHttpClients.shared(SECOND);
        assertNotSame(first, second);
        assertTrue("the superseded client must be closed, not left to the garbage collector", first.isClosed());
        assertFalse(second.isClosed());
    }

    @Test
    public void aDifferentTokenOrTimeoutAlsoReplacesTheClient()
    {
        SonarHttpClient withToken = SonarHttpClients.shared(FIRST);
        SonarHttpClient withOtherToken =
            SonarHttpClients.shared(SonarConnection.of("http://localhost:9001", "other", 10));
        assertNotSame(withToken, withOtherToken);
        SonarHttpClient withOtherTimeout =
            SonarHttpClients.shared(SonarConnection.of("http://localhost:9001", "other", 30));
        assertNotSame(withOtherToken, withOtherTimeout);
        assertTrue(withOtherToken.isClosed());
    }

    @Test
    public void closeAllClosesTheSharedClientAndTheNextCallBuildsAFreshOne()
    {
        SonarHttpClient client = SonarHttpClients.shared(FIRST);
        SonarHttpClients.closeAll();
        assertTrue(client.isClosed());
        SonarHttpClient rebuilt = SonarHttpClients.shared(FIRST);
        assertNotSame(client, rebuilt);
        assertFalse(rebuilt.isClosed());
    }

    @Test
    public void closeAllIsSafeWhenNothingWasEverCreated()
    {
        SonarHttpClients.closeAll();
        SonarHttpClients.closeAll();
    }
}
