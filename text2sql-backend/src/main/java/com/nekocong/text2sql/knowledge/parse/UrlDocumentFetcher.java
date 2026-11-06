package com.nekocong.text2sql.knowledge.parse;

import com.nekocong.text2sql.config.KnowledgeProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * Fetches a web page over HTTP(S) for knowledge ingestion.
 *
 * <p>Only {@code http}/{@code https} URLs are accepted. Redirects are followed, non-2xx responses
 * and bodies larger than {@code parser.max-file-size-mb} are rejected, and failures are reported as
 * readable {@link ParseException}s.
 */
public class UrlDocumentFetcher {

    /**
     * The result of fetching a web page for knowledge ingestion.
     *
     * @param finalUrl the URL after redirects
     * @param contentType the detected media type (parameters stripped)
     * @param body the response bytes
     * @param fetchedAt the fetch timestamp
     */
    public record FetchedPage(String finalUrl, String contentType, byte[] body, Instant fetchedAt) {
    }

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String USER_AGENT = "text2sql-knowledge/1.0";

    private final KnowledgeProperties properties;
    private final HttpClient httpClient;

    /**
     * Creates the fetcher.
     *
     * @param properties the knowledge configuration (used for the size limit)
     */
    public UrlDocumentFetcher(KnowledgeProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Fetches the given URL.
     *
     * @param url the page URL
     * @return the fetched page
     * @throws ParseException when the URL is invalid, the request fails or the body is too large
     */
    public FetchedPage fetch(String url) {
        if (url == null || url.isBlank()) {
            throw new ParseException("抓取网页失败：URL 为空");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new ParseException("抓取网页失败：URL 格式不正确: " + url, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new ParseException("抓取网页失败：仅支持 http/https 链接: " + url);
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,text/plain,application/pdf,image/*;q=0.8,*/*;q=0.5")
                .GET()
                .build();

        try {
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                closeQuietly(response.body());
                throw new ParseException("抓取网页失败（HTTP " + response.statusCode() + "）: " + url);
            }
            byte[] body;
            try (InputStream input = response.body()) {
                body = readLimited(input, maxBytes());
            }
            return new FetchedPage(
                    response.uri().toString(),
                    resolveContentType(response, uri),
                    body,
                    Instant.now());
        } catch (IOException e) {
            throw new ParseException("抓取网页失败: " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ParseException("抓取网页被中断: " + url, e);
        }
    }

    private long maxBytes() {
        int megabytes = Math.max(1, properties.getParser().getMaxFileSizeMb());
        return (long) megabytes * 1024L * 1024L;
    }

    private static byte[] readLimited(InputStream input, long limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new ParseException("网页内容超过大小上限 " + (limit / (1024L * 1024L)) + " MB");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String resolveContentType(HttpResponse<?> response, URI uri) {
        String header = response.headers().firstValue("Content-Type").orElse("");
        if (!header.isBlank()) {
            int semicolon = header.indexOf(';');
            String mime = semicolon >= 0 ? header.substring(0, semicolon) : header;
            mime = mime.trim().toLowerCase(Locale.ROOT);
            if (!mime.isBlank()) {
                return mime;
            }
        }
        return contentTypeFromExtension(uri.getPath());
    }

    private static String contentTypeFromExtension(String path) {
        return switch (DocumentFormats.extension(path)) {
            case "html", "htm" -> "text/html";
            case "pdf" -> "application/pdf";
            case "txt" -> "text/plain";
            case "md", "markdown" -> "text/markdown";
            case "csv" -> "text/csv";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "tif", "tiff" -> "image/tiff";
            default -> "application/octet-stream";
        };
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // Nothing useful to do with a close failure on an already-rejected response.
        }
    }
}
