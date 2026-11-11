package com.nekocong.text2sql.health;

import com.nekocong.text2sql.component.DemoComponent;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/**
 * 健康检查demo
 *
 * @author chenpengyu
 * @since 2026/10/11
 */
@Component
public class DemoIndicator extends AbstractHealthIndicator {

	private final DemoComponent demoComponent;

	public DemoIndicator(DemoComponent demoComponent, MeterRegistry meterRegistry) {
		this.demoComponent = demoComponent;
	}

	@Override
	protected void doHealthCheck(Health.Builder builder) throws Exception {
		int check = demoComponent.check();
		if (check == 1) {
			builder.up();
		} else {
			builder.down();
		}
	}
}