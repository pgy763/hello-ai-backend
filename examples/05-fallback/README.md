# 05-fallback · 降级链：主备模型自动切换

第 5 章的完整可运行版本。**不需要 API key、不需要外网** —— 自带三家可以被"打坏"的假上游。

## 演示什么

一条三跳的降级链：

```
qwen-primary (qwen-plus, 900ms)
      ↓ 失败
deepseek-backup (deepseek-chat, 900ms)
      ↓ 失败
qwen-small (qwen-turbo, 600ms)
```

整条链总预算 2500ms，超过就返回 503，不让用户继续等。

**核心不是"按顺序试"，是"什么时候不该试"。** 九种故障场景里，有两种会**立刻停止**、不做任何降级：

| 场景 | 结局 | 降级 |
| --- | --- | --- |
| 主路 500 / 429 | `SERVER_ERROR` | ✅ |
| 主路卡住不返回 | `TIMEOUT` | ✅ |
| 主路 200 但 content 为空 | `MALFORMED` | ✅ |
| 主路额度用完（`insufficient_quota`） | `NO_QUOTA` | ✅ |
| 主路 400 参数错（`invalid_request_error`） | `CLIENT_ERROR` | ❌ 换一家还是同样的错 |
| 主路 401 key 失效 | `AUTH` | ❌ 是我们自己配置坏了 |

## 怎么跑

```bash
cd examples/05-fallback
mvn spring-boot:run
```

## 预期看到什么

**1. 正常情况：主路回答**

```bash
curl -s 'localhost:8080/api/ask?q=你好'
```

```json
{"answer":"[primary] 收到：你好","target":"qwen-primary","model":"qwen-plus",
 "degraded":false,"attempts":[{"target":"qwen-primary","outcome":"OK","ms":50}],"totalMs":57}
```

**2. 主路 500：自动切到备路**

```bash
curl -sX POST 'localhost:8080/api/fault/primary/E500'
curl -s 'localhost:8080/api/ask'
```

```json
{"answer":"[backup] 收到：...","target":"deepseek-backup","degraded":true,
 "attempts":[{"target":"qwen-primary","outcome":"SERVER_ERROR","ms":2},
             {"target":"deepseek-backup","outcome":"OK","ms":3}],"totalMs":9}
```

**3. 主路卡死：超时预算生效**

```bash
curl -sX POST 'localhost:8080/api/fault/reset'
curl -sX POST 'localhost:8080/api/fault/primary/HANG'
curl -s 'localhost:8080/api/ask'
```

主路 **913ms** 后判超时（预算 900ms），备路 3ms 顶上，**总共 919ms**。

**4. 主路参数错：不降级，直接失败**

```bash
curl -sX POST 'localhost:8080/api/fault/reset'
curl -sX POST 'localhost:8080/api/fault/primary/E400_INVALID'
curl -s 'localhost:8080/api/ask'
```

```json
{"error":"all_targets_failed","totalMs":58,
 "attempts":[{"target":"qwen-primary","outcome":"CLIENT_ERROR","ms":47,
              "detail":"HTTP 400 invalid_request_error"}]}
```

**只尝试了 1 次。** 同样的请求体换一家厂商，还是同样的 400 —— 降级只会让你在备路上再撞一次墙，还多花 900ms。

**5. 主路 200 但内容是空的**

```bash
curl -sX POST 'localhost:8080/api/fault/reset'
curl -sX POST 'localhost:8080/api/fault/primary/GARBAGE'
curl -s 'localhost:8080/api/ask'
```

`outcome=MALFORMED`，切备路。这是最坑的一种故障：**监控看板上全绿，用户拿到空回复。**

**6. 三条全挂**

```bash
curl -s 'localhost:8080/api/all-down?fault=HANG'
curl -s 'localhost:8080/api/ask'
```

三次尝试依次 925ms / 911ms / 602ms，**总共 2443ms**，然后 503。

**7. 预算不够时主动跳过**

```bash
java -jar target/05-fallback-0.0.1-SNAPSHOT.jar --fallback.total-budget-ms=1200
```

同样三条全卡死，第二跳直接被跳过：

```
qwen-primary   TIMEOUT   929ms
deepseek-backup SKIPPED    0ms   ← 剩余预算 267ms，不够启动这一跳
qwen-small     TIMEOUT   277ms
```

**不是"再试试看"，是"算了别试了"** —— 剩 267ms 启动一跳注定超时的请求，只会让用户多等。

## 已经验证过的

| 场景 | 结果 |
| --- | --- |
| 全部正常 | 主路回答，57ms，`degraded=false` |
| 主路 500 | 9ms 切到备路，`degraded=true` |
| 主路卡死 | 主路 913ms 超时，备路 3ms，总 919ms |
| 主路 400 参数错 | **仅 1 次尝试**，58ms 失败 |
| 主路 401 | **仅 1 次尝试**，4ms 失败 |
| 主路额度用完 | 12ms 切备路 |
| 主路 200 空回复 | 5ms 切备路，`MALFORMED` |
| 主路慢 700ms 但成功 | 不降级，710ms 正常返回 |
| 三条全卡死 | 3 次尝试，2443ms 后 503 |
| 总预算压到 1200ms | 第二跳 `SKIPPED`，总 1212ms |

## 一个实测撞出来的坑

`HttpRequest.timeout()` 和 `CompletableFuture.orTimeout()` 都会超时，但抛的异常不一样：

- `orTimeout` → `TimeoutException`
- `HttpRequest.timeout` → `HttpTimeoutException`（是 `IOException`）

不归一化，同一个"超时"会报成两种 outcome，统计和告警全对不上。示例里两者都归到 `TIMEOUT`。

## 这个示例没做的

- **健康探测**：示例用「调用失败即判失败」，没有主动探测。正文 5.6 讲了为什么不建议主动探测
- **熔断**：降级是"换一条路"，熔断是"停止打某一条路"，那是第 6 章
- **真实厂商**：假上游只模拟协议形态，不模拟真实延迟分布