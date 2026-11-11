package com.nekocong.text2sql.datasource;

/**
 * Outcome of a datasource connectivity test.
 *
 * @param ok whether the connection succeeded
 * @param message a human-readable message (success text or the failure reason)
 * @param elapsedMs how long the test took, in milliseconds
 */
public record ConnectionTestResult(boolean ok, String message, long elapsedMs) {}
