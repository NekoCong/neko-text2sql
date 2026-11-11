# 可观测性指南（Health / Metrics / Traces）

面向 `neko-text2sql-backend` 与本地 `otel-lgtm` 监控栈的实操文档，包含**配置步骤**、
**自定义 Health 端点**、**自定义 Metrics 端点**，以及本仓库踩过的坑（FAQ）。

- 适用对象：本仓库后端（Java 25 + Spring Boot 4.1 + WebFlux + AgentScope）
- 监控栈：`docker-compose.yml` 中的 `grafana/otel-lgtm`（单容器集成整套后端）

---

## 1. 总览

可观测性三大支柱 + 日志：

| 支柱 | 本仓库实现 | 存储/查看 |
|------|-----------|-----------|
| 健康 Health | Spring Boot Actuator health 端点 + 自定义 `DemoIndicator` | `GET /actuator/health` |
| 指标 Metrics | Micrometer（内置 + 自定义 `DemoMetrics`） | `GET /actuator/metrics/**`；远端 Prometheus |
| 链路 Traces | `spring-boot-starter-opentelemetry` 自动埋点 | 远端 Tempo（Grafana 查看） |
| 日志 Logs | Logback → OTLP | 远端 Loki（Grafana 查看） |

数据流（**全程 push，OTLP 协议**）：

```
Spring Boot app :8080
   │  OTLP HTTP :4318  (trace + metric + log)
   ▼
text2sql-otel-lgtm 容器
   ├─ OpenTelemetry Collector ──┬──> Prometheus :9090   (指标，OTLP 接收)
   │                            ├──> Tempo             (链路)
   │                            ├──> Loki              (日志)
   │                            └──> Pyroscope         (profiling)
   └─（Collector 每秒抓取自身 :8888，产出 otelcol_* 自监控指标）
   ▼
Grafana :3000  ← 统一查询/展示（数据源已自动预置）
```

> ⚠️ 关键认知：**这里的 Prometheus 是「推」模式**（启用原生 OTLP 接收器），
> 不配置任何 `scrape_configs`，所以 Prometheus 的 **Targets 页面本来就是空的**，这是正常的。
> 详见 [FAQ](#8-常见问题-faq)。

### 组件与端口

| 组件 | 地址 | 说明 |
|------|------|------|
| Grafana | http://localhost:3000 | 免登录（匿名管理员） |
| Prometheus | http://localhost:9090 | 指标查询 |
| OTLP HTTP | http://localhost:4318 | app 推送入口（`/v1/traces`、`/v1/metrics`） |
| OTLP gRPC | localhost:4317 | app 推送入口（gRPC） |

---

## 2. 配置步骤（从零到看板）

### 2.1 依赖（`neko-text2sql-backend/pom.xml`）

```xml
<!-- Actuator：暴露 health / metrics 等管理端点 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>

<!-- OpenTelemetry：自动装配 OTel SDK，把 trace/metric/log 通过 OTLP 推出去 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-opentelemetry</artifactId>
</dependency>
```

> 说明：`spring-boot-starter-opentelemetry` 已传递引入 Micrometer 及 OTLP 指标注册表，
> **无需**额外再加 `micrometer-registry-prometheus`（除非你要用 Prometheus 拉模式，见 FAQ）。

### 2.2 应用配置（`neko-text2sql-backend/src/main/resources/application.yaml`）

```yaml
management:
  endpoint:
    health:
      show-details: always          # 健康端点显示各组件明细
  endpoints:
    web:
      exposure:
        include: "*"                # 暴露全部端点（生产请收敛为具体白名单）
  # --- OpenTelemetry / OTLP ---
  opentelemetry:
    resource-attributes:
      service.name: "${spring.application.name}"   # 决定 Prometheus 里的 job/service_name
    tracing:
      export:
        otlp:
          endpoint: "${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://localhost:4318/v1/traces}"
  otlp:
    metrics:
      export:
        url: "${OTEL_EXPORTER_OTLP_METRICS_ENDPOINT:http://localhost:4318/v1/metrics}"
  tracing:
    sampling:
      probability: "${TRACING_SAMPLING_PROBABILITY:1.0}"   # 开发全采样

spring:
  reactor:
    context-propagation: auto       # WebFlux 下让 trace 上下文贯穿 Reactor 操作符
```

常用可调项（Spring Boot 4 已确认存在）：

| 键 | 含义 | 默认 |
|----|------|------|
| `management.otlp.metrics.export.enabled` | 是否推送指标 | `true` |
| `management.otlp.metrics.export.step` | 指标推送间隔 | `1m` |
| `management.otlp.metrics.export.url` | 指标推送地址 | OTLP 默认 |
| `management.otlp.tracing.export.enabled` | 是否推送链路 | `true` |
| `management.opentelemetry.tracing.export.otlp.endpoint` | 链路推送地址 | 无默认 |
| `management.tracing.sampling.probability` | 采样率 | `0.1` |

### 2.3 启动监控栈（仓库根目录）

```bash
docker compose up -d            # 启动 otel-lgtm
docker compose ps               # 期望 STATUS = Up (healthy)
docker compose down             # 停止
```

首次会拉取 `grafana/otel-lgtm:latest`（较大，约 1GB+）。若镜像加速器偶发 `502`，重试即可。

### 2.4 启动后端

```bash
cd neko-text2sql-backend
./mvnw spring-boot:run          # Windows: .\mvnw.cmd spring-boot:run
```

> 模型 API Key（如 `DASHSCOPE_API_KEY`）仅在真正调用大模型时才需要；
> `HarnessAgent` 是**每请求懒创建**的，因此不带 Key 也能正常启动、也能观测基础设施指标。

### 2.5 一键验证

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/metrics
curl "http://localhost:8080/actuator/metrics/demo.component.check"
```

浏览器打开 Grafana http://localhost:3000 → 左侧 **Dashboards**。

---

## 3. Actuator 端点清单

默认基路径 `/actuator`（可用 `management.endpoints.web.base-path` 调整）。本应用实际暴露：

| 端点 | 路径 | 说明 |
|------|------|------|
| health | `/actuator/health` | 聚合健康状态 |
| health 分组 | `/actuator/health/{liveness,readiness}` | K8s 探针 |
| metrics | `/actuator/metrics` | 列出全部指标名 |
| metrics 明细 | `/actuator/metrics/{name}` | 单个指标的测量值/标签 |
| info | `/actuator/info` | 应用信息 |
| env / configprops / beans / conditions / mappings | … | 诊断用 |
| loggers | `/actuator/loggers` | 运行时调整日志级别 |
| threaddump / scheduledtasks / sbom | … | 诊断用 |

> 只有 `include` 白名单内的端点可用。当前为 `"*"`（全部）。

---

## 4. 自定义 Health 端点

### 4.1 开箱即用

`spring-boot-starter-actuator` 自带 `diskSpace`、`ping`、`livenessState`、`readinessState`、`ssl` 等组件。
开启 `management.endpoint.health.show-details: always` 后：

```bash
curl http://localhost:8080/actuator/health
```

```json
{
  "status": "UP",
  "components": {
    "demoIndicator": { "status": "UP" },
    "diskSpace": { "status": "UP", "details": { ... } },
    "livenessState": { "status": "UP" },
    "readinessState": { "status": "UP" },
    "ping": { "status": "UP" },
    "ssl": { "status": "UP", ... }
  },
  "groups": ["liveness", "readiness"]
}
```

**探针**：`/actuator/health/liveness` 与 `/actuator/health/readiness` 返回 `{"status":"UP"}`。
在非 K8s 环境如需显式开启：`management.endpoint.health.probes.enabled: true`。

### 4.2 自定义 HealthIndicator（核心步骤）

Spring Boot 会自动收集所有 `HealthIndicator` 类型的 Bean，聚合进 `/actuator/health`。
**Bean 名称 → `components.<beanName>`。**

本仓库示例 `DemoIndicator`（`com.nekocong.text2sql.health`）——把 `DemoComponent` 的状态作为健康判据：

```java
package com.nekocong.text2sql.health;

import com.nekocong.text2sql.component.DemoComponent;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

@Component("demoIndicator")                 // Bean 名 = 健康组件名
public class DemoIndicator extends AbstractHealthIndicator {

    private final DemoComponent demoComponent;

    public DemoIndicator(DemoComponent demoComponent) {
        this.demoComponent = demoComponent;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        int check = demoComponent.check();          // 业务判断：1=正常
        if (check == 1) {
            builder.up().withDetail("check", check);
        } else {
            builder.down().withDetail("check", check);
        }
    }
}
```

要点：

1. **继承 `AbstractHealthIndicator`**（Spring Boot 4 的包是 `org.springframework.boot.health.contributor.*`，
   与 Boot 3 的 `org.springframework.boot.actuate.health.*` 不同）或直接实现 `HealthIndicator`。
2. 用 `@Component("xxx")` 指定 Bean 名，它会成为 `components.xxx`。
3. 状态可选：`up()` / `down()` / `outOfService()` / `unknown()`；用 `withDetail(k, v)` 附带明细。
   `show-details: always` 时明细会返回。
4. **不要吞异常**：`doHealthCheck` 抛异常会被记录为 `DOWN` 并在 details 显示错误（可观测性更好）。
5. 健康检查应**快速且无副作用**——它会被探针高频调用，别在里面做重活。

验证：

```bash
curl http://localhost:8080/actuator/health | grep demoIndicator
```

### 4.3 自定义健康分组

把相关组件聚合成一个自定义分组（供特定探针使用）：

```yaml
management:
  endpoint:
    health:
      group:
        custom:
          include: demoIndicator,diskSpace
          show-details: always
```

访问：`GET /actuator/health/custom`。

### 4.4 状态码映射（可选）

```yaml
management:
  endpoint:
    health:
      status:
        http-mapping:
          DOWN: 503
          OUT_OF_SERVICE: 503
```

（默认 `UP → 200`、`DOWN → 503`，一般无需改动。）

---

## 5. 自定义 Metrics 端点

### 5.1 开箱即用

```bash
curl http://localhost:8080/actuator/metrics                      # 列出指标名
curl http://localhost:8080/actuator/metrics/jvm.memory.used      # 看明细
curl "http://localhost:8080/actuator/metrics/http.server.requests?tag=uri:/actuator/health"
```

内置常用指标（Micrometer 命名，**点号分隔**）：

| 类别 | 名称示例 |
|------|----------|
| JVM | `jvm.memory.used`、`jvm.gc.pause`、`jvm.threads.live`、`jvm.classes.loaded` |
| HTTP | `http.server.requests`（含标签 `uri/method/status/outcome`） |
| 进程 | `process.cpu.usage`、`process.uptime`、`process.start.time` |
| 系统 | `system.cpu.usage`、`system.cpu.count` |
| 磁盘 | `disk.free`、`disk.total` |
| 日志 | `logback.events` |

### 5.2 自定义指标：推荐用 `MeterBinder` + `Gauge`

若指标来自某个组件的"当前状态"，用 `Gauge` 实时读取最省心（推送时求值，不缓存）。

本仓库示例 `DemoMetrics`（`com.nekocong.text2sql.metrics`）——把 `DemoComponent.check()` 暴露为指标：

```java
package com.nekocong.text2sql.metrics;

import com.nekocong.text2sql.component.DemoComponent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

@Component
public class DemoMetrics implements MeterBinder {

    /** Micrometer 指标名（点号分隔）。 */
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
```

为什么要用 `MeterBinder`：Spring Boot 会把容器里所有 `MeterBinder` Bean **自动绑定**到
`MeterRegistry`，无需手动 `register`，也不会重复注册。

验证：

```bash
curl "http://localhost:8080/actuator/metrics/demo.component.check"
# {"name":"demo.component.check","measurements":[{"statistic":"VALUE","value":1.0}],
#  "availableTags":[{"tag":"component","values":["demo"]}], ...}
```

### 5.3 其他常用仪表（注入 `MeterRegistry`）

若指标是"事件计数"或"耗时"，用 `Counter` / `Timer`：

```java
@Service
public class ChatMetrics {

    private final Counter requests;
    private final Timer latency;

    public ChatMetrics(MeterRegistry registry) {
        this.requests = Counter.builder("demo.chat.requests")
                .tag("type", "chat")
                .description("对话请求数")
                .register(registry);
        this.latency = Timer.builder("demo.chat.duration")
                .description("对话耗时")
                .register(registry);
    }

    public String measure(Supplier<String> action) {
        requests.increment();
        return latency.record(action);
    }
}
```

### 5.4 命名与转换规则（很重要）

| 层 | `demo.component.check` 的名字 |
|----|-------------------------------|
| Java / Micrometer | `demo.component.check`（点号） |
| Actuator 端点 | `GET /actuator/metrics/demo.component.check` |
| Prometheus | `demo_component_check`（点号→下划线） |

其他约定：

- 计数器会带 `_total` 后缀；带物理单位的计时器/直方图会插入单位，例如
  `http.server.requests` → `http_server_requests_milliseconds_*`，
  `jvm.memory.used` → `jvm_memory_used_bytes`。
- 标签（tag）在 Prometheus 里成为 label：`component="demo"`。
- **命名一旦确定就不要随意改**，否则已有看板/告警会失效。

### 5.5 指标如何进入 Prometheus / Grafana

应用把指标经 OTLP **推送**到 Collector，Collector 再推给 Prometheus 的 OTLP 接收器
（`/api/v1/otlp`）。因此：

- Prometheus 里 `job` / `service_name` = `spring.application.name` = `neko-text2sql-backend`。
- 只有**应用在运行时**才会持续推送；应用停止后约 5 分钟，其 series 会在 Prometheus 中变为 stale，
  查询返回空。

查询示例（Prometheus / Grafana Explore）：

```promql
demo_component_check
{job="neko-text2sql-backend"}
sum by (uri) (http_server_requests_milliseconds_count)
histogram_quantile(0.95, sum by (le) (rate(http_server_requests_milliseconds_bucket[5m])))
jvm_memory_used_bytes{area="heap"}
process_cpu_usage
```

> ⚠️ otel-lgtm 自带的 **RED Metrics** 看板查询的是 OTel 语义约定名
> （`http_server_request_duration_seconds_*`）与**原生直方图**，
> 与 Spring Boot 4 实际产出的 `http_server_requests_milliseconds_*`（经典直方图）**不匹配**，
> 因此这两张 RED 看板会**一直为空**。请改用 **JVM Overview** 看板，或按下节自建看板。

### 5.6 自建 RED 看板（推荐）

直接用真实指标名建面板：

```promql
# 请求速率（按接口）
sum by (uri) (rate(http_server_requests_milliseconds_count[5m]))

# 错误率
sum(rate(http_server_requests_milliseconds_count{outcome="SERVER_ERROR"}[5m]))
  / sum(rate(http_server_requests_milliseconds_count[5m]))

# P95 延迟（毫秒）
histogram_quantile(0.95, sum by (le) (rate(http_server_requests_milliseconds_bucket[5m])))
```

---

## 6. 自定义 Actuator 端点（进阶）

如果你要的是一条**全新的管理端点**（而非往 health/metrics 里加东西），用 `@Endpoint`：

```java
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

@Component
@Endpoint(id = "demo-check")
public class DemoCheckEndpoint {

    private final DemoComponent demoComponent;

    public DemoCheckEndpoint(DemoComponent demoComponent) {
        this.demoComponent = demoComponent;
    }

    @ReadOperation
    public Map<String, Object> check() {
        return Map.of("component", "demo", "value", demoComponent.check());
    }
}
```

访问：`GET /actuator/demo-check`（同样受 `management.endpoints.web.exposure.include` 控制）。

---

## 7. 链路追踪（简述）

`spring-boot-starter-opentelemetry` 会自动为每个 HTTP 请求创建 server span，
名字形如 `http get /api/text2sql/health`。

- 查看：Grafana → **Explore** → 数据源 **Tempo** → `Search` → `Service Name = neko-text2sql-backend`。
- 应用内自定义 span：使用 `@Observed`（需启用 AOP/`ObservedAspect`）或注入 `io.opentelemetry.api.trace.Tracer` 手动 start/end。
- exporter 是**异步批量**的（默认 `schedule-delay≈5s`），因此请求后需等几秒才能在 Tempo 看到。

---

## 8. 常见问题（FAQ）

### Q1. Prometheus 的 Targets 页面为什么是空的？

因为**这里用的是推送（OTLP）而非拉取（scrape）**。`otel-lgtm` 的 `prometheus.yaml`
没有任何 `scrape_configs`，只启用了原生 OTLP 接收器，所以 Prometheus 没有"抓取目标"，Targets 为空是**正常**的。
你看到的 `up` / `scrape_*` 指标，是 **Collector** 每秒抓取自身 `:8888` 后推给 Prometheus 的，不是 Prometheus 抓的。

**若确实要 Targets 有内容**（Prometheus 拉模式），需要：
1. 后端加 `micrometer-registry-prometheus` 依赖，暴露 `/actuator/prometheus`；
2. 给 Prometheus 增加指向 `host.docker.internal:8080/actuator/prometheus` 的 `scrape_configs`。

### Q2. Grafana 看板为什么没数据？

按顺序排查：
1. **后端是否在运行？** 应用停止超过约 5 分钟，指标即失效。`curl http://localhost:8080/actuator/health` 验证。
2. **时间范围**是否覆盖有数据的时段（选"最近 15 分钟"）。
3. 看板顶部 **`job` 变量**是否选为 `All` 或 `neko-text2sql-backend`。
4. **指标名是否匹配**：见 [5.5](#55-指标如何进入-prometheus--grafana)——自带的 RED 看板与 Boot 4 名字不符，会一直空。

### Q3. 启动时报 `Failed to publish metrics to OTLP receiver`

`otel-lgtm` 没启动（或 `:4318` 无接收端）。执行 `docker compose up -d` 即可。
不影响应用运行与 `/actuator/metrics`（本地注册表照常工作）。

### Q4. 大量 `sun.misc.Unsafe ... WARNING`

来自 `protobuf-java`（OTLP exporter 的依赖）在 JDK 25 下的库层面告警，**无害**。
可加 JVM 参数 `--sun-misc-unsafe-memory-access=allow` 静音，或忽略。

### Q5. 访问 `/actuator/prometheus` 返回 404

因为没有引入 `micrometer-registry-prometheus`（当前采用 OTLP 推送，不需要它）。
需要拉模式时再加该依赖。

### Q6. 不想跑 Collector，想关掉 OTLP 推送

```yaml
management:
  otlp:
    metrics:
      export:
        enabled: false
    tracing:
      export:
        enabled: false
```

`/actuator/health`、`/actuator/metrics` 不受影响。

---

## 9. 验证清单

| # | 操作 | 期望 |
|---|------|------|
| 1 | `curl localhost:8080/actuator/health` | `components.demoIndicator.status = UP` |
| 2 | `curl localhost:8080/actuator/metrics/demo.component.check` | `value = 1.0` |
| 3 | Grafana → Explore → Prometheus → `demo_component_check` | 值 = 1 |
| 4 | Grafana → Explore → Tempo → `neko-text2sql-backend` | 有 trace（如 `http get /actuator/health`） |
| 5 | Grafana → Dashboards → JVM Overview | 7/8 面板有数据（`Error %` 无 5xx 时为空属正常） |

---

## 10. 参考

- Spring Boot Actuator / Micrometer 官方文档
- OpenTelemetry Collector、`grafana/otel-lgtm` 镜像说明
- 本仓库 `application.yaml`、`docker-compose.yml`、
  `component/DemoComponent`、`health/DemoIndicator`、`metrics/DemoMetrics`
