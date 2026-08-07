/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tests for {@link SonarConnection}, in particular the credential redaction of review minor M3. */
public class SonarConnectionTest
{
    private static final String TOKEN = "squ_2b7c9f1e5a4d";

    @Test
    public void normalizesTrailingSlashInBaseUrl()
    {
        assertEquals("https://sonar.example.com",
            SonarConnection.of("https://sonar.example.com/", TOKEN, 30).baseUrl());
    }

    /**
     * The record is passed around freely - it keys the shared client holder and is a field of the analysis
     * request - so the default record {@code toString} would put the user's SonarQube token into any log line
     * or status message that ever interpolates it.
     */
    @Test
    public void toStringNeverDisclosesTheToken()
    {
        String text = SonarConnection.of("https://sonar.example.com", TOKEN, 30).toString();

        assertFalse(text, text.contains(TOKEN));
        assertTrue(text, text.contains("***"));
    }

    /** The fields a connection problem has to be diagnosed from must survive the redaction. */
    @Test
    public void toStringKeepsTheUrlAndTimeoutVisible()
    {
        String text = SonarConnection.of("https://sonar.example.com", TOKEN, 45).toString();

        assertTrue(text, text.contains("https://sonar.example.com"));
        assertTrue(text, text.contains("45"));
    }

    @Test
    public void toStringDistinguishesAnAbsentTokenFromARedactedOne()
    {
        assertTrue(SonarConnection.of("https://sonar.example.com", "", 30).toString().contains("<none>"));
    }
}
