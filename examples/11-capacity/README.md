# 11-capacity · 压测与容量规划

第 11 章的完整可运行版本。不需要 key：上游是一个可以配置并发槽位和延迟的假服务。

## 演示什么

| 组件 | 作用 | 正文 |
| --- | --- | --- |
| `MockUpstream` | 假上游：N 个并发槽位、固定延迟、可配 RPM | 11.3 |
| `LoadTester` | 自压：按并发数打自己的 `/api/chat`，算 P50/P95/P99 | 11.2、11.3 |
| `CapacityModel` | 利特尔法则的正向/反向估算 | 11.5 |

## 怎么跑

```bash
cd examples/11-capacity
mvn spring-boot:run
```

## 预期看到什么

### 1. 饱和曲线（上游 8 槽位 / 400ms）

```bash
curl -s -X POST 'localhost:8080/api/config?slots=8&latencyMs=400&rpm=100000'
for c in 1 2 4 8 16 32 64; do
  curl -s -X POST "localhost:8080/api/load?concurrency=$c&perWorker=8" \
    | jq -c '{concurrency,rps,p50,p95,avgInFlight}'
done
```

| 并发 | RPS | P50 | P95 | 在飞 |
| --- | --- | --- | --- | --- |
| 1 | 2.40 | 414ms | 450ms | 1.0 |
| 2 | 4.85 | 414ms | 419ms | 2.0 |
| 4 | 9.72 | 415ms | 417ms | 4.0 |
| 8 | **19.47** | 407ms | 417ms | 8.0 |
| 16 | 19.82 | 805ms | 815ms | 15.5 |
| 32 | 19.77 | 1612ms | 1623ms | 30.4 |
| 64 | 19.78 | 3232ms | 3248ms | 60.4 |

**并发超过 8 之后，吞吐不变，延迟翻倍。** 8 ÷ 0.4s = 20 RPS 就是天花板。

### 2. 限流返回 429 而不是 500

```bash
curl -s -X POST 'localhost:8080/api/config?slots=8&latencyMs=100&rpm=100'
curl -s -X POST 'localhost:8080/api/load?concurrency=16&perWorker=20' | jq
```

```
总数=320 成功=100 429=220   ← 320 次请求只放过 100 次
P50=1ms                      ← 被拒的瞬间返回，平均延迟反而更好看
```

### 3. 容量估算

```bash
# 正向：5 万 DAU 要多少并发、多少配额、多少钱
curl -s -X POST 'localhost:8080/api/model' | jq

# 反向：上游给 50 并发，能扛多少 QPS
curl -s 'localhost:8080/api/model/from-upstream?concurrency=50&latencyMs=2500&targetQps=25' | jq
```

正向结果：峰值 QPS 17.36、峰值并发 43.4、Tomcat 66 线程、RPM 1354、月成本 48,600 元。
反向结果：50 并发 / 2500ms = **理论上限 20 QPS**，目标 25 → 缺口 5 QPS。

## 已经验证过的

| 项 | 结果 |
| --- | --- |
| 并发 ≤ 8 | 吞吐线性增长（2.4 → 19.5 RPS），延迟稳定 410ms |
| 并发 = 8 | 到达饱和点 19.47 RPS（理论 20） |
| 并发 16 / 32 / 64 | 吞吐 19.82 / 19.77 / 19.78 —— 不变；延迟 805 / 1612 / 3232 —— 翻倍 |
| 利特尔法则 | 在飞请求数 ≈ 并发数（1.0 → 60.4） |
| RPM 限流 | 320 次请求放行 100、429 计 220，P50 从 216ms 掉到 1ms |
| 正向估算 | 300,000 次/日 → 17.36 峰值 QPS → 43.4 并发 → 66 线程 |
| 反向估算 | 50 并发 ÷ 2.5s = 20 QPS，对 25 QPS 目标缺口 5 |

## 踩过的坑（正文也写了）

- **HttpClient 复用压测线程池 → 自锁。** 并发 1 时压测线程阻塞在 `send()`，HttpClient 又需要这个线程干活，一个请求都发不出去。用默认执行器即可。
- **限流抛普通异常 → Spring 返回 500。** 压测器把它记成「失败」，调用方也会当成故障去重试。必须显式映射成 429 + `Retry-After`。

## 这个示例没做的

- **真实模型压测**：上游是假的，成本是 0。真链路小流量验证见正文 11.6
- **分布式压测**：单机压测器本身会成为瓶颈（几千 QPS 时）
- **背压**：示例让请求排队，真实系统应该更早拒绝（信号量 + 快速失败）