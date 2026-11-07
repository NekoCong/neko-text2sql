package com.nekocong.text2sql.knowledge.parse;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekocong.text2sql.config.KnowledgeProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpMinerUClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void unavailableWithBlankToken() {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getMineru().setToken("  ");
        HttpMinerUClient client = new HttpMinerUClient(properties);

        assertFalse(client.available());
        ParseException exception = assertThrows(ParseException.class, () -> client.parse(
                new ParseContext("a.pdf", "application/pdf", new byte[] {1}, null)));
        assertTrue(exception.getMessage().contains("Token"));
    }

    @Test
    void availableWithToken() {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getMineru().setToken("secret-token");

        assertTrue(new HttpMinerUClient(properties).available());
    }

    /**
     * Regression guard: MinerU's upload URL is an Aliyun OSS presigned URL whose signature covers
     * the Content-Type header. Sending a Content-Type (even application/octet-stream) yields
     * {@code 403 SignatureDoesNotMatch}, which broke every PDF upload in production.
     */
    @Test
    void uploadsWithoutAContentTypeHeader() throws Exception {
        byte[] uploadBody = "pdf-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] zip = zipOf(
                "full.md", "# 标题".getBytes(StandardCharsets.UTF_8),
                "content_list.json",
                "[{\"type\":\"text\",\"text\":\"标题\",\"page_idx\":0,\"bbox\":[0,0,10,10]}]"
                        .getBytes(StandardCharsets.UTF_8));
        AtomicReference<String> uploadContentType = new AtomicReference<>("unset");
        AtomicReference<byte[]> uploaded = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/api/v4/file-urls/batch", exchange -> {
            byte[] body = ("{\"code\":0,\"data\":{\"batch_id\":\"b1\",\"file_urls\":[\"http://127.0.0.1:"
                            + port + "/upload/x.pdf\"]}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            respond(exchange, body);
        });
        server.createContext("/upload/x.pdf", exchange -> {
            uploadContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            uploaded.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, new byte[0]);
        });
        server.createContext("/api/v4/extract-results/batch/b1", exchange -> {
            byte[] body = ("{\"code\":0,\"data\":{\"extract_result\":[{\"state\":\"done\","
                            + "\"full_zip_url\":\"http://127.0.0.1:" + port + "/result.zip\"}]}}")
                    .getBytes(StandardCharsets.UTF_8);
            respond(exchange, body);
        });
        server.createContext("/result.zip", exchange -> respond(exchange, zip));
        server.start();

        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getMineru().setToken("test-token");
        properties.getMineru().setBaseUrl("http://127.0.0.1:" + port);
        properties.getMineru().setPollIntervalSeconds(1);
        properties.getMineru().setPollTimeoutSeconds(10);

        MinerUResult result = new HttpMinerUClient(properties)
                .parse(new ParseContext("a.pdf", "application/pdf", uploadBody, null));

        assertArrayEquals(uploadBody, uploaded.get());
        assertNull(
                uploadContentType.get(),
                "the presigned OSS URL signs Content-Type; sending one breaks the signature");
        assertArrayEquals(zip, result.zipBytes());
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] body)
            throws java.io.IOException {
        exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    private static byte[] zipOf(String name1, byte[] data1, String name2, byte[] data2)
            throws java.io.IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name1));
            zip.write(data1);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(name2));
            zip.write(data2);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
