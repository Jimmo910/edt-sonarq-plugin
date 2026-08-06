/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;

import org.junit.Test;

/** Tests for {@link SecretScrubber}. */
public class SecretScrubberTest
{
    private static final String TOKEN = "glptt-0a1b2c3d4e5f6071";

    @Test
    public void dropsTheQueryStringOfAUrlButKeepsSchemeHostAndPath()
    {
        String text = "https://gitlab.example.com/api/v4/projects/123/trigger/pipeline?token=" + TOKEN
            + "&ref=main";

        String scrubbed = SecretScrubber.scrub(text);

        assertEquals("https://gitlab.example.com/api/v4/projects/123/trigger/pipeline?***", scrubbed);
    }

    /**
     * The finding this class exists for: a malformed CI URL template makes {@code URI.create} throw with
     * the whole URL - token included - in the exception message, which then reaches the status line, the
     * tooltip, the Details dialog and the Eclipse error log.
     */
    @Test
    public void redactsTheTokenInAMalformedUriExceptionMessage()
    {
        String url = "https://gitlab.example.com/api/v4/projects/123/trigger/pipeline?token=" + TOKEN
            + "&ref={branch";
        String message;
        try
        {
            URI.create(url);
            throw new AssertionError("URI.create was expected to reject the malformed template");
        }
        catch (IllegalArgumentException e)
        {
            message = String.valueOf(e.getMessage());
        }
        assertTrue("precondition: the raw message must leak the token", message.contains(TOKEN));

        String scrubbed = SecretScrubber.scrub(message);

        assertFalse(scrubbed.contains(TOKEN));
        assertTrue("the host and path must survive so the error stays actionable",
            scrubbed.contains("gitlab.example.com/api/v4/projects/123/trigger/pipeline"));
    }

    @Test
    public void redactsEverythingAfterTheQuestionMarkEvenWhenTheQueryContainsWhitespace()
    {
        String scrubbed = SecretScrubber.scrub("Illegal character at 5: http://h/p?token=abc def&ref=x");

        assertFalse(scrubbed.contains("abc"));
        assertFalse(scrubbed.contains("def"));
        assertTrue(scrubbed.startsWith("Illegal character at 5: http://h/p?***"));
    }

    @Test
    public void redactsKnownLiteralSecretsAnywhereInTheText()
    {
        String scrubbed = SecretScrubber.scrub("invalid header value: Bearer " + TOKEN, "Bearer " + TOKEN);

        assertEquals("invalid header value: ***", scrubbed);
    }

    @Test
    public void ignoresNullAndBlankKnownSecrets()
    {
        String scrubbed = SecretScrubber.scrub("plain text", null, "", "   ");

        assertEquals("plain text", scrubbed);
    }

    @Test
    public void redactsUrlUserInformation()
    {
        String scrubbed = SecretScrubber.scrub("failed to connect to https://user:s3cr3t@ci.example.com/hook");

        assertFalse(scrubbed.contains("s3cr3t"));
        assertTrue(scrubbed.contains("https://***@ci.example.com/hook"));
    }

    @Test
    public void redactsBareCredentialAssignmentsWithoutAUrl()
    {
        String scrubbed = SecretScrubber.scrub("private_token=" + TOKEN + " rejected");

        assertFalse(scrubbed.contains(TOKEN));
        assertTrue(scrubbed.startsWith("private_token=***"));
    }

    @Test
    public void leavesTextWithoutCredentialsUnchanged()
    {
        String text = "Connection timed out after 30 seconds";

        assertEquals(text, SecretScrubber.scrub(text));
    }

    @Test
    public void passesNullAndEmptyThrough()
    {
        assertNull(SecretScrubber.scrub(null));
        assertEquals("", SecretScrubber.scrub(""));
    }
}
