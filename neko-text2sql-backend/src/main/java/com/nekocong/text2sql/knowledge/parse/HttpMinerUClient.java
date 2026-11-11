package com.nekocong.text2sql.knowledge.parse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nekocong.text2sql.config.KnowledgeProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * {@link MinerUClient} implementation using the JDK HTTP client and MinerU's v4 batch API.
 *
 * <p>Flow: request upload URLs, PUT the raw bytes to the returned object-storage URL, poll the
 * batch result until {@code done}/{@code failed}, then download the result zip. The token is only
 * sent to the MinerU API host, never to the upload/download URLs, and is never logged.
 */
public class HttpMinerUClient implements MinerUClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(120);
    private static final String ENGINE_VERSION = "v4";

    private final KnowledgeProperties.Mineru config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Semaphore semaphore;

    /**
     * Creates the client.
     *
     * @param properties the knowledge configuration holding the MinerU settings
     */
    public HttpMinerUClient(KnowledgeProperties properties) {
        this.config = Objects.requireNonNull(properties, "properties").getMineru();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
        this.semaphore = new Semaphore(Math.max(1, config.getMaxConcurrency()), true);
    }

    @Override
    public boolean available() {
        return config.getToken() != null && !config.getToken().isBlank();
    }

    @Override
    public MinerUResult parse(ParseContext context) {
        if (!available()) {
            throw new ParseException(
                    "MinerU 未配置 Token，无法解析 PDF/图片；请配置 MINERU_TOKEN 或切换解析引擎");
        }
        if (context == null || context.bytes() == null || context.bytes().length == 0) {
            throw new ParseException("MinerU 解析失败：文档内容为空");
        }

        int timeoutSeconds = Math.max(1, config.getPollTimeoutSeconds());
        boolean acquired = false;
        try {
            acquired = semaphore.tryAcquire(timeoutSeconds + 30L, TimeUnit.SECONDS);
            if (!acquired) {
                throw new ParseException("MinerU 并发任务已满，请稍后重试");
            }
            return doParse(context, timeoutSeconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ParseException("MinerU 解析被中断", e);
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    private MinerUResult doParse(ParseContext context, int timeoutSeconds) throws InterruptedException {
        String baseUrl = trimTrailingSlash(config.getBaseUrl());
        String filename = context.filename() == null || context.filename().isBlank()
                ? "document.pdf"
                : context.filename();
        String dataId = UUID.randomUUID().toString();

        ObjectNode payload = objectMapper.createObjectNode();
        ArrayNode files = payload.putArray("files");
        ObjectNode file = files.addObject();
        file.put("name", filename);
        file.put("data_id", dataId);
        payload.put("model_version", config.getModelVersion());
        payload.put("enable_ocr", config.isOcr());
        payload.put("enable_formula", config.isFormula());
        payload.put("enable_table", config.isTable());

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new ParseException("构造 MinerU 请求失败", e);
        }

        HttpRequest createRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v4/file-urls/batch"))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + config.getToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> createResponse = send(createRequest, HttpResponse.BodyHandlers.ofByteArray());
        if (!isSuccessful(createResponse.statusCode())) {
            throw new ParseException("申请 MinerU 上传链接失败（HTTP " + createResponse.statusCode() + "）: "
                    + snippet(createResponse.body()));
        }

        JsonNode createJson = readJson(createResponse.body(), "申请 MinerU 上传链接");
        int code = createJson.path("code").asInt(0);
        if (code != 0) {
            throw new ParseException("申请 MinerU 上传链接失败: " + errorMessage(createJson, code));
        }
        JsonNode data = createJson.path("data");
        String batchId = textOrNull(data, "batch_id");
        JsonNode fileUrls = data.path("file_urls");
        String uploadUrl = fileUrls.isArray() && !fileUrls.isEmpty()
                ? textOrNull(fileUrls.get(0))
                : null;
        if (isBlank(batchId) || isBlank(uploadUrl)) {
            throw new ParseException("MinerU 未返回有效的 batch_id 或上传链接");
        }

        // Do NOT set Content-Type here. MinerU returns an Aliyun OSS presigned URL whose V1
        // signature covers the Content-Type header (signed as empty); sending
        // application/octet-stream makes OSS answer 403 SignatureDoesNotMatch.
        HttpRequest uploadRequest = HttpRequest.newBuilder(URI.create(uploadUrl))
                .timeout(REQUEST_TIMEOUT)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(context.bytes()))
                .build();
        HttpResponse<byte[]> uploadResponse =
                send(uploadRequest, HttpResponse.BodyHandlers.ofByteArray());
        if (!isSuccessful(uploadResponse.statusCode())) {
            throw new ParseException("上传文件到 MinerU 失败（HTTP "
                    + uploadResponse.statusCode() + "）: " + snippet(uploadResponse.body()));
        }

        String zipUrl = awaitResult(baseUrl, batchId, timeoutSeconds);
        return new MinerUResult(download(zipUrl), ENGINE_VERSION);
    }

    private String awaitResult(String baseUrl, String batchId, int timeoutSeconds)
            throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        String encodedBatchId = URLEncoder.encode(batchId, StandardCharsets.UTF_8);
        URI resultUri = URI.create(baseUrl + "/api/v4/extract-results/batch/" + encodedBatchId);
        int intervalSeconds = Math.max(1, config.getPollIntervalSeconds());

        while (true) {
            if (Instant.now().isAfter(deadline)) {
                throw new ParseException("MinerU 解析超时（超过 " + timeoutSeconds + " 秒）");
            }
            Thread.sleep(intervalSeconds * 1000L);

            HttpRequest pollRequest = HttpRequest.newBuilder(resultUri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + config.getToken())
                    .GET()
                    .build();
            HttpResponse<byte[]> pollResponse = send(pollRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (!isSuccessful(pollResponse.statusCode())) {
                throw new ParseException("查询 MinerU 解析结果失败（HTTP " + pollResponse.statusCode() + "）");
            }

            JsonNode pollJson = readJson(pollResponse.body(), "查询 MinerU 解析结果");
            int code = pollJson.path("code").asInt(0);
            if (code != 0) {
                throw new ParseException("查询 MinerU 解析结果失败: " + errorMessage(pollJson, code));
            }
            JsonNode pollData = pollJson.path("data");
            JsonNode extractResult = pollData.path("extract_result");
            JsonNode item = extractResult.isArray() && !extractResult.isEmpty()
                    ? extractResult.get(0)
                    : pollData;
            String state = item.path("state").asText("");
            if ("done".equalsIgnoreCase(state)) {
                String zipUrl = textOrNull(item, "full_zip_url");
                if (isBlank(zipUrl)) {
                    throw new ParseException("MinerU 解析完成但未返回下载链接");
                }
                return zipUrl;
            }
            if ("failed".equalsIgnoreCase(state)) {
                String error = item.path("err_msg").asText("");
                throw new ParseException("MinerU 解析失败: " + (isBlank(error) ? "未知原因" : error));
            }
        }
    }

    private byte[] download(String zipUrl) throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(zipUrl))
                .timeout(DOWNLOAD_TIMEOUT)
                .GET()
                .build();
        HttpResponse<byte[]> response = send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (!isSuccessful(response.statusCode())) {
            throw new ParseException("下载 MinerU 解析结果失败（HTTP " + response.statusCode() + "）");
        }
        byte[] body = response.body();
        if (body == null || body.length == 0) {
            throw new ParseException("MinerU 返回的解析结果为空");
        }
        return body;
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws InterruptedException {
        try {
            return httpClient.send(request, handler);
        } catch (IOException e) {
            throw new ParseException("请求 MinerU 服务失败: " + readable(e), e);
        }
    }

    private JsonNode readJson(byte[] body, String action) {
        if (body == null || body.length == 0) {
            throw new ParseException(action + "失败：响应为空");
        }
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            throw new ParseException(action + "失败：响应不是有效 JSON", e);
        }
    }

    private static String errorMessage(JsonNode node, int code) {
        String message = node.path("msg").asText("");
        if (isBlank(message)) {
            message = node.path("message").asText("");
        }
        return isBlank(message) ? "code=" + code : message;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static boolean isSuccessful(int status) {
        return status >= 200 && status < 300;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimTrailingSlash(String baseUrl) {
        String value = baseUrl == null || baseUrl.isBlank() ? "https://mineru.net" : baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String snippet(byte[] body) {
        if (body == null || body.length == 0) {
            return "(空响应)";
        }
        String text = new String(body, StandardCharsets.UTF_8);
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    private static String readable(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
