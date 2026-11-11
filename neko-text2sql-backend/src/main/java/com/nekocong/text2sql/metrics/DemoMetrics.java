package com.nekocong.text2sql.metrics;

import com.nekocong.text2sql.component.DemoComponent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * 指标 demo：把 {@link DemoComponent} 的运行状态绑定为 Micrometer 指标。
 *
 * <p>作为 {@link MeterBinder} Bean，Spring Boot 启动时会自动将其绑定到 {@link MeterRegistry}，
 * 之后可通过 Actuator 的 metrics 端点读取实时值：
 *
 * <pre>GET /actuator/metrics/demo.component.check</pre>
 *
 * <p>采用 Gauge（实时读取 {@link DemoComponent#check()}，0 成本持续采集），
 * 因此上报值始终反映组件当前状态：1=正常，0=异常。
 *
 * @author chenpengyu
 * @since 2026/10/12
 */
@Component
public class DemoMetrics implements MeterBinder {

    /** 指标名，对应 Actuator metrics 端点路径 {@code /actuator/metrics/demo.component.check}。 */
    public static final String METRIC_NAME = "demo.component.check";

    private final DemoComponent demoComponent;

    public DemoMetrics(DemoComponent demoComponent) {
        this.demoComponent = demoComponent;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(METRIC_NAME, demoComponent, DemoComponent::check)
                .description("DemoComponent 运行状态：1=正常，0=异常")
                .tag("component", "demo")
                .register(registry);
    }
}
