package com.nekocong.text2sql.knowledge.config;

import com.nekocong.text2sql.config.KnowledgeProperties;
import com.nekocong.text2sql.knowledge.parse.DocumentParser;
import com.nekocong.text2sql.knowledge.parse.DocumentParserSelector;
import com.nekocong.text2sql.knowledge.parse.HttpMinerUClient;
import com.nekocong.text2sql.knowledge.parse.MinerUClient;
import com.nekocong.text2sql.knowledge.parse.MinerUDocumentParser;
import com.nekocong.text2sql.knowledge.parse.TikaDocumentParser;
import com.nekocong.text2sql.knowledge.parse.UrlDocumentFetcher;
import com.nekocong.text2sql.knowledge.repo.JdbcKnowledgeRepository;
import com.nekocong.text2sql.knowledge.repo.KnowledgeRepository;
import com.nekocong.text2sql.knowledge.store.MinioObjectStore;
import com.nekocong.text2sql.knowledge.store.ObjectStore;
import com.nekocong.text2sql.knowledge.store.PgVectorStore;
import com.nekocong.text2sql.knowledge.store.VectorStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.concurrent.Executor;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires the persistent knowledge base: a dedicated PostgreSQL/pgvector datasource, MinIO object
 * storage, the parser stack and the async ingestion executor.
 *
 * <p>Everything here is conditional on {@code text2sql.knowledge.enabled=true}, so the default demo
 * (and the H2 test suite) needs no PostgreSQL, MinIO, MinerU token or embedding key.
 */
@Configuration
@ConditionalOnProperty(prefix = "text2sql.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeConfig {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeConfig.class);

    /**
     * The dedicated vector-database pool. It is intentionally separate from the reactive application
     * database and from the analysed datasources.
     *
     * @param properties the knowledge configuration
     * @return the pooled datasource
     */
    @Bean(name = "knowledgeDataSource", destroyMethod = "close")
    DataSource knowledgeDataSource(KnowledgeProperties properties) {
        KnowledgeProperties.Pgvector pgvector = properties.getPgvector();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(pgvector.getUrl());
        config.setUsername(pgvector.getUsername());
        config.setPassword(pgvector.getPassword());
        config.setMaximumPoolSize(Math.max(1, pgvector.getPoolSize()));
        config.setPoolName("knowledge-pgvector");
        config.setReadOnly(false);
        return new HikariDataSource(config);
    }

    /**
     * The JDBC template used by the knowledge repositories.
     *
     * @param dataSource the knowledge datasource
     * @return the template
     */
    @Bean(name = "knowledgeJdbcTemplate")
    JdbcTemplate knowledgeJdbcTemplate(@Qualifier("knowledgeDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * The transaction manager for knowledge writes.
     *
     * @param dataSource the knowledge datasource
     * @return the transaction manager
     */
    @Bean(name = "knowledgeTransactionManager")
    PlatformTransactionManager knowledgeTransactionManager(
            @Qualifier("knowledgeDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /**
     * Creates the knowledge tables idempotently. A failure (database not yet reachable) is logged
     * instead of aborting startup: the retrieval path then reports a readable "vector store
     * unavailable" error rather than the process dying.
     *
     * @param dataSource the knowledge datasource
     * @return the initializer (run eagerly as a bean)
     */
    @Bean
    KnowledgeSchemaInitializer knowledgeSchemaInitializer(
            @Qualifier("knowledgeDataSource") DataSource dataSource) {
        return new KnowledgeSchemaInitializer(dataSource);
    }

    /**
     * The JDBC knowledge repository.
     *
     * @param jdbc the knowledge template
     * @return the repository
     */
    @Bean
    KnowledgeRepository knowledgeRepository(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbc) {
        return new JdbcKnowledgeRepository(jdbc);
    }

    /**
     * The pgvector-backed chunk store.
     *
     * @param jdbc the knowledge template
     * @return the vector store
     */
    @Bean
    VectorStore vectorStore(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbc) {
        return new PgVectorStore(jdbc);
    }

    /**
     * The MinIO object store, with the private bucket created on first use.
     *
     * @param properties the knowledge configuration
     * @return the object store
     */
    @Bean
    ObjectStore objectStore(KnowledgeProperties properties) {
        MinioObjectStore store = new MinioObjectStore(properties.getMinio());
        try {
            store.initialize();
        } catch (RuntimeException ex) {
            log.warn("MinIO 初始化失败，原文与预览将不可用：{}", ex.getMessage());
        }
        return store;
    }

    /**
     * The Tika parser (text-oriented formats).
     *
     * @param properties the knowledge configuration
     * @return the parser
     */
    @Bean
    DocumentParser tikaDocumentParser(KnowledgeProperties properties) {
        return new TikaDocumentParser(properties);
    }

    /**
     * The MinerU client (precise layout parsing).
     *
     * @param properties the knowledge configuration
     * @return the client
     */
    @Bean
    MinerUClient minerUClient(KnowledgeProperties properties) {
        return new HttpMinerUClient(properties);
    }

    /**
     * The MinerU parser (PDF / images, page + bbox output).
     *
     * @param properties the knowledge configuration
     * @param client the MinerU client
     * @return the parser
     */
    @Bean
    DocumentParser minerUDocumentParser(KnowledgeProperties properties, MinerUClient client) {
        return new MinerUDocumentParser(properties, client);
    }

    /**
     * The parser selector.
     *
     * @param properties the knowledge configuration
     * @param parsers every registered parser
     * @return the selector
     */
    @Bean
    DocumentParserSelector documentParserSelector(
            KnowledgeProperties properties, List<DocumentParser> parsers) {
        return new DocumentParserSelector(properties, parsers);
    }

    /**
     * The URL fetcher used by web imports.
     *
     * @param properties the knowledge configuration
     * @return the fetcher
     */
    @Bean
    UrlDocumentFetcher urlDocumentFetcher(KnowledgeProperties properties) {
        return new UrlDocumentFetcher(properties);
    }

    /**
     * The async executor that drives the ingestion state machine. Writes never block on parsing or
     * embedding.
     *
     * @param properties the knowledge configuration
     * @return the executor
     */
    @Bean(name = "knowledgeExecutor")
    Executor knowledgeExecutor(KnowledgeProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int concurrency = Math.max(1, properties.getMineru().getMaxConcurrency());
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency * 2);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("knowledge-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /** Runs the knowledge DDL on startup, retrying briefly so a slow database does not lose the schema. */
    static final class KnowledgeSchemaInitializer {

        private static final int ATTEMPTS = 10;

        private static final long RETRY_MILLIS = 2_000L;

        private KnowledgeSchemaInitializer(DataSource dataSource) {
            for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                try (java.sql.Connection connection = dataSource.getConnection()) {
                    ScriptUtils.executeSqlScript(
                            connection, new ClassPathResource("db/knowledge-schema.sql"));
                    log.info("知识库表结构已就绪");
                    return;
                } catch (Exception ex) {
                    if (attempt == ATTEMPTS) {
                        log.warn("知识库表结构初始化失败（向量库可能不可用）：{}", ex.getMessage());
                        return;
                    }
                    if (attempt == 1) {
                        log.warn("等待向量库就绪（第 1 次尝试失败）：{}", ex.getMessage());
                    }
                    try {
                        Thread.sleep(RETRY_MILLIS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }
}
