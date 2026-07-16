/**
 * SonarQ in EDT
 * Copyright (C) 2026 Jimmo910
 * Licensed under EPL-2.0
 */

package ru.jimmo.edt.sonarq.core.client;

/** Signals a failed SonarQube Web API call. */
public class SonarServerException extends Exception
{
    private static final long serialVersionUID = 1L;

    private final int statusCode;

    /**
     * Creates an exception for an HTTP error response.
     *
     * @param statusCode the HTTP status code
     * @param message the error message, not {@code null}
     */
    public SonarServerException(int statusCode, String message)
    {
        super(message);
        this.statusCode = statusCode;
    }

    /**
     * Creates an exception for a transport failure.
     *
     * @param message the error message, may be {@code null}
     * @param cause the underlying failure, not {@code null}
     */
    public SonarServerException(String message, Throwable cause)
    {
        super(message, cause);
        this.statusCode = 0;
    }

    /**
     * Returns the HTTP status code.
     *
     * @return the status code, {@code 0} for transport failures
     */
    public int getStatusCode()
    {
        return statusCode;
    }
}
