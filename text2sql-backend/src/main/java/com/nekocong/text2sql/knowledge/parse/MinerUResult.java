package com.nekocong.text2sql.knowledge.parse;

/**
 * The raw output of one MinerU precise-parse job.
 *
 * @param zipBytes the downloaded result archive ({@code full.md}, content list, images)
 * @param engineVersion the engine version reported by the service, e.g. {@code v4}
 */
public record MinerUResult(byte[] zipBytes, String engineVersion) {
}
