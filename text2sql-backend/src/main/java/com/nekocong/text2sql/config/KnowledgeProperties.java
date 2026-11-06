package com.nekocong.text2sql.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the persistent knowledge base, bound from {@code text2sql.knowledge.*}.
 *
 * <p>Disabled by default: when {@code enabled=false} the application keeps using the in-memory
 * knowledge service and the full-schema provider, so the demo (and the H2 test suite) needs no
 * PostgreSQL, MinIO or external parser.
 */
@ConfigurationProperties(prefix = "text2sql.knowledge")
public class KnowledgeProperties {

    /** Whether the persistent three-type knowledge base is active. */
    private boolean enabled = false;

    /** Maximum chunk size, in characters. */
    private int chunkSize = 800;

    /** Default number of chunks returned by a search. */
    private int topK = 5;

    /** Candidate tables retrieved before foreign-key expansion and LLM selection. */
    private int candidateTables = 8;

    /** Minimum number of candidate tables before the LLM table selector is consulted. */
    private int llmSelectThreshold = 6;

    /** Whether an LLM may pick the final table set (falls back to the rule set on failure). */
    private boolean llmSelectEnabled = true;

    /** Timeout for the table-selection model call, in milliseconds. */
    private long llmSelectTimeoutMs = 8_000;

    /** Whether a deterministic lexical fallback is allowed when no embedding model is present. */
    private boolean lexicalFallback = true;

    private final Pgvector pgvector = new Pgvector();

    private final Minio minio = new Minio();

    private final Parser parser = new Parser();

    private final Mineru mineru = new Mineru();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getCandidateTables() {
        return candidateTables;
    }

    public void setCandidateTables(int candidateTables) {
        this.candidateTables = candidateTables;
    }

    public int getLlmSelectThreshold() {
        return llmSelectThreshold;
    }

    public void setLlmSelectThreshold(int llmSelectThreshold) {
        this.llmSelectThreshold = llmSelectThreshold;
    }

    public boolean isLlmSelectEnabled() {
        return llmSelectEnabled;
    }

    public void setLlmSelectEnabled(boolean llmSelectEnabled) {
        this.llmSelectEnabled = llmSelectEnabled;
    }

    public long getLlmSelectTimeoutMs() {
        return llmSelectTimeoutMs;
    }

    public void setLlmSelectTimeoutMs(long llmSelectTimeoutMs) {
        this.llmSelectTimeoutMs = llmSelectTimeoutMs;
    }

    public boolean isLexicalFallback() {
        return lexicalFallback;
    }

    public void setLexicalFallback(boolean lexicalFallback) {
        this.lexicalFallback = lexicalFallback;
    }

    public Pgvector getPgvector() {
        return pgvector;
    }

    public Minio getMinio() {
        return minio;
    }

    public Parser getParser() {
        return parser;
    }

    public Mineru getMineru() {
        return mineru;
    }

    /** PostgreSQL + pgvector connection. */
    public static class Pgvector {

        /** JDBC URL of the vector database. */
        private String url = "jdbc:postgresql://localhost:5433/text2sql";

        private String username = "postgres";

        private String password = "postgres";

        /** Pool size for the dedicated knowledge connection pool. */
        private int poolSize = 4;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }
    }

    /** MinIO / S3-compatible object storage. */
    public static class Minio {

        private String endpoint = "http://localhost:9000";

        /**
         * Endpoint used only to sign download URLs, when it differs from {@link #endpoint}.
         *
         * <p>In a container deployment the backend reaches MinIO over the internal network, while the
         * browser must use the host's public address. Presigning is pure computation, so a second
         * client with the public endpoint produces URLs the browser can open without the backend
         * ever connecting to that address.
         */
        private String publicEndpoint = "";

        private String accessKey = "minioadmin";

        private String secretKey = "minioadmin";

        private String bucket = "text2sql-knowledge";

        /** Lifetime of a generated preview URL, in seconds. */
        private int presignTtlSeconds = 600;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getPublicEndpoint() {
            return publicEndpoint;
        }

        public void setPublicEndpoint(String publicEndpoint) {
            this.publicEndpoint = publicEndpoint;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public int getPresignTtlSeconds() {
            return presignTtlSeconds;
        }

        public void setPresignTtlSeconds(int presignTtlSeconds) {
            this.presignTtlSeconds = presignTtlSeconds;
        }
    }

    /** Document parsing limits and engine selection. */
    public static class Parser {

        /** Engine for PDFs: {@code mineru} (page + bbox) or {@code tika} (text only). */
        private String pdfEngine = "mineru";

        /** Engine for images: {@code mineru} (page + bbox) or {@code tika} (text only). */
        private String imageEngine = "mineru";

        /** Maximum accepted upload size, in megabytes. */
        private int maxFileSizeMb = 200;

        /** Maximum accepted page count (MinerU's own limit). */
        private int maxPages = 200;

        public String getPdfEngine() {
            return pdfEngine;
        }

        public void setPdfEngine(String pdfEngine) {
            this.pdfEngine = pdfEngine;
        }

        public String getImageEngine() {
            return imageEngine;
        }

        public void setImageEngine(String imageEngine) {
            this.imageEngine = imageEngine;
        }

        public int getMaxFileSizeMb() {
            return maxFileSizeMb;
        }

        public void setMaxFileSizeMb(int maxFileSizeMb) {
            this.maxFileSizeMb = maxFileSizeMb;
        }

        public int getMaxPages() {
            return maxPages;
        }

        public void setMaxPages(int maxPages) {
            this.maxPages = maxPages;
        }
    }

    /** MinerU precise-parse cloud API. */
    public static class Mineru {

        /** API token; when blank, MinerU parsing is unavailable and PDFs fail with a clear reason. */
        private String token = "";

        private String baseUrl = "https://mineru.net";

        /** {@code vlm} (recommended, keeps layout) or {@code pipeline}. */
        private String modelVersion = "vlm";

        private boolean ocr = true;

        private boolean formula = true;

        private boolean table = true;

        /** Total polling budget, in seconds. */
        private int pollTimeoutSeconds = 180;

        /** Polling interval, in seconds. */
        private int pollIntervalSeconds = 3;

        /** Maximum concurrent MinerU jobs. */
        private int maxConcurrency = 2;

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModelVersion() {
            return modelVersion;
        }

        public void setModelVersion(String modelVersion) {
            this.modelVersion = modelVersion;
        }

        public boolean isOcr() {
            return ocr;
        }

        public void setOcr(boolean ocr) {
            this.ocr = ocr;
        }

        public boolean isFormula() {
            return formula;
        }

        public void setFormula(boolean formula) {
            this.formula = formula;
        }

        public boolean isTable() {
            return table;
        }

        public void setTable(boolean table) {
            this.table = table;
        }

        public int getPollTimeoutSeconds() {
            return pollTimeoutSeconds;
        }

        public void setPollTimeoutSeconds(int pollTimeoutSeconds) {
            this.pollTimeoutSeconds = pollTimeoutSeconds;
        }

        public int getPollIntervalSeconds() {
            return pollIntervalSeconds;
        }

        public void setPollIntervalSeconds(int pollIntervalSeconds) {
            this.pollIntervalSeconds = pollIntervalSeconds;
        }

        public int getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }
    }
}
