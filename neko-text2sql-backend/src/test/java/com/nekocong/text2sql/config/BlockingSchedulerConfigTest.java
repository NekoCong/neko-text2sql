package com.nekocong.text2sql.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * Guards that blocking knowledge work is offloaded to virtual threads rather than the shared
 * bounded-elastic pool, whose size is capped at ten times the CPU count.
 */
class BlockingSchedulerConfigTest {

    @Test
    void runsBlockingWorkOnAVirtualThread() {
        Scheduler scheduler = new BlockingSchedulerConfig().blockingScheduler();
        try {
            Boolean virtual = Mono.fromCallable(() -> Thread.currentThread().isVirtual())
                    .subscribeOn(scheduler)
                    .block();
            assertThat(virtual).isTrue();
        } finally {
            scheduler.dispose();
        }
    }

    @Test
    void namesItsThreadsForProfiling() {
        Scheduler scheduler = new BlockingSchedulerConfig().blockingScheduler();
        try {
            String name = Mono.fromCallable(() -> Thread.currentThread().getName())
                    .subscribeOn(scheduler)
                    .block();
            assertThat(name).startsWith("blocking-");
        } finally {
            scheduler.dispose();
        }
    }
}
