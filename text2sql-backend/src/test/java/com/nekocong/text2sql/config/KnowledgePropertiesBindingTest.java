package com.nekocong.text2sql.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Guards the {@code text2sql.knowledge.*} binding, including the environment-variable names the
 * README documents.
 */
@SpringBootTest(
        properties = {
            "text2sql.knowledge.chunk-size=512",
            "text2sql.knowledge.top-k=7",
            "text2sql.knowledge.candidate-tables=11",
            "text2sql.knowledge.llm-select-enabled=false",
            "text2sql.knowledge.pgvector.url=jdbc:postgresql://pgvector:5432/knowledge",
            "text2sql.knowledge.pgvector.pool-size=6",
            "text2sql.knowledge.minio.endpoint=http://minio:9000",
            "text2sql.knowledge.minio.bucket=binding-test",
            "text2sql.knowledge.minio.presign-ttl-seconds=120",
            "text2sql.knowledge.parser.pdf-engine=tika",
            "text2sql.knowledge.parser.max-file-size-mb=64",
            "MINERU_TOKEN=binding-test-token",
            "text2sql.knowledge.mineru.poll-timeout-seconds=42"
        })
class KnowledgePropertiesBindingTest {

    @Autowired
    private KnowledgeProperties properties;

    @Test
    void knowledgeBaseStaysDisabledByDefault() {
        assertThat(properties.isEnabled()).isFalse();
    }

    @Test
    void bindsFlatAndNestedGroups() {
        assertThat(properties.getChunkSize()).isEqualTo(512);
        assertThat(properties.getTopK()).isEqualTo(7);
        assertThat(properties.getCandidateTables()).isEqualTo(11);
        assertThat(properties.isLlmSelectEnabled()).isFalse();
        assertThat(properties.getPgvector().getUrl())
                .isEqualTo("jdbc:postgresql://pgvector:5432/knowledge");
        assertThat(properties.getPgvector().getPoolSize()).isEqualTo(6);
        assertThat(properties.getMinio().getEndpoint()).isEqualTo("http://minio:9000");
        assertThat(properties.getMinio().getBucket()).isEqualTo("binding-test");
        assertThat(properties.getMinio().getPresignTtlSeconds()).isEqualTo(120);
        assertThat(properties.getParser().getPdfEngine()).isEqualTo("tika");
        assertThat(properties.getParser().getMaxFileSizeMb()).isEqualTo(64);
        assertThat(properties.getMineru().getPollTimeoutSeconds()).isEqualTo(42);
    }

    @Test
    void mineruTokenComesFromTheEnvironmentVariable() {
        assertThat(properties.getMineru().getToken()).isEqualTo("binding-test-token");
    }
}
