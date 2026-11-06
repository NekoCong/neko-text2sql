package com.nekocong.text2sql.knowledge.parse;

/**
 * Client for the MinerU precise-parse cloud API.
 *
 * <p>The interface isolates the network flow from the zip-to-block mapping so that the mapping can
 * be unit-tested with recorded fixtures.
 */
public interface MinerUClient {

    /**
     * Reports whether the client is configured (a token is present).
     *
     * @return {@code true} when the client can be used
     */
    boolean available();

    /**
     * Submits a document, waits for completion and returns the result archive.
     *
     * @param context the document to parse
     * @return the downloaded result archive
     * @throws ParseException when the request fails, times out or returns an error
     */
    MinerUResult parse(ParseContext context);
}
