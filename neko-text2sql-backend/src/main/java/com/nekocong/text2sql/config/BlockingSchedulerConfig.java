package com.nekocong.text2sql.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * The scheduler used for work that is inherently blocking: knowledge-base JDBC, target-datasource
 * introspection/query execution, MinIO and document parsing.
 *
 * <p>These calls cannot be made non-blocking — the project supports arbitrary JDBC target
 * datasources, the MinIO SDK is synchronous and Tika/MinerU parse on the calling thread. What can
 * be avoided is tying them to {@link Schedulers#boundedElastic()}, whose pool is capped at ten times
 * the CPU count: a burst of knowledge searches would queue behind long parses.
 *
 * <p>On Java 21+ a virtual thread per task keeps the blocking style (simple, debuggable) while
 * letting the JVM park thousands of blocked calls cheaply. The scheduler is disposed on shutdown,
 * which shuts its executor down.
 */
@Configuration
public class BlockingSchedulerConfig {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(BlockingSchedulerConfig.class);

    /** Bean name injected wherever blocking work is offloaded. */
    public static final String BLOCKING_SCHEDULER = "blockingScheduler";

    /**
     * Creates the virtual-thread scheduler.
     *
     * @return a scheduler backed by one virtual thread per task
     */
    @Bean(name = BLOCKING_SCHEDULER, destroyMethod = "dispose")
    Scheduler blockingScheduler() {
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("blocking-", 0).factory());
        log.info("阻塞任务调度器已启用：每个任务一个虚拟线程（bean={}）", BLOCKING_SCHEDULER);
        return Schedulers.fromExecutorService(executor);
    }
}
