package com.nekocong.text2sql.knowledge.parse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekocong.text2sql.config.KnowledgeProperties;
import org.junit.jupiter.api.Test;

class HttpMinerUClientTest {

    @Test
    void unavailableWithBlankToken() {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getMineru().setToken("  ");
        HttpMinerUClient client = new HttpMinerUClient(properties);

        assertFalse(client.available());
        ParseException exception = assertThrows(ParseException.class, () -> client.parse(
                new ParseContext("a.pdf", "application/pdf", new byte[]{1}, null)));
        assertTrue(exception.getMessage().contains("Token"));
    }

    @Test
    void availableWithToken() {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getMineru().setToken("secret-token");

        assertTrue(new HttpMinerUClient(properties).available());
    }
}
