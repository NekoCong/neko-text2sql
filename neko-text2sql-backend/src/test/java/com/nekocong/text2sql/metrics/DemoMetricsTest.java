package com.nekocong.text2sql.metrics;

import com.nekocong.text2sql.component.DemoComponent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies that {@link DemoMetrics} binds the {@link DemoComponent} state as a gauge so it becomes
 * visible on the Actuator metrics endpoint.
 */
class DemoMetricsTest {

    /**
     * Retained as a field: a Micrometer {@link Gauge} holds a weak reference to its state object, so
     * a component that is not strongly referenced can be GC'd and make the gauge report NaN.
     */
    private final DemoComponent demoComponent = new DemoComponent();

    /** The gauge reports the current value returned by {@link DemoComponent#check()}. */
    @Test
    void bindsGaugeReflectingDemoComponentState() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new DemoMetrics(demoComponent).bindTo(registry);

        Gauge gauge = registry.find(DemoMetrics.METRIC_NAME).gauge();
        assertNotNull(gauge, "gauge should be registered under " + DemoMetrics.METRIC_NAME);
        assertEquals(1.0d, gauge.value(), "demo component reports a healthy state of 1");
    }
}
