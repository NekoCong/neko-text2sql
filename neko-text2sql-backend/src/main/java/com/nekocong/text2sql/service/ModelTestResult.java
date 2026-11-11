package com.nekocong.text2sql.service;

/**
 * Outcome of a model connectivity test.
 *
 * @param ok whether the test succeeded
 * @param message a human-readable message
 * @param elapsedMs how long the test took, in milliseconds
 */
public record ModelTestResult(boolean ok, String message, long elapsedMs) {}
