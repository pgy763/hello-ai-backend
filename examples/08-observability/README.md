# 08-observability · token 计量与成本核算

第 8 章的完整可运行版本。不需要 key、不需要外部数据库（内存版 H2）。

## 演示什么

一次调用 → 一行日志 → 一张能算钱的表。四个接口：

| 接口 | 干什么 |
| --- | --- |
| `POST /api/chat` | 打一次调用，自动记账 |
| `GET /api/logs/{traceId}` | 一个 trace 下的所有尝试（重试就是多行） |
| `GET /api/usage?groupBy=tenant\|model\|feature` | 成本归因 |
| `GET /api/summary` | 总量、估算比例、平均 TTFT |

## 怎么跑

```bash
cd examples/08-observability
mvn spring-boot:run
```

## 预期看到什么

### 1. 普通调用：有 `usage`，精确计价

```bash
curl -s -X POST 'localhost:8080/api/chat?tenant=t1&feature=ticket&model=demo-large' | jq
```

```json
{"estimated":false,"promptTokens":2229,"completionTokens":159,
 "costMicros":5730,"costYuan":"0.005730","ttftMs":259,"totalMs":819}
```

TTFT 259ms、总时长 819ms —— **差的那 560ms 用户是在读，不是在等。**

### 2. 流式但不给 usage：只能估，偏差 -12.6%

```bash
curl -s -X POST 'localhost:8080/api/chat?tenant=t1&feature=chat&model=demo-large&stream=true&includeUsage=false' | jq
```

```json
{"estimated":true,"promptTokens":1950,"completionTokens":139,
 "trueCompletionTokens":159,"errorRate":"-12.6%","costMicros":5012}
```

### 3. 流式 + `include_usage`：精确

```bash
curl -s -X POST 'localhost:8080/api/chat?tenant=t1&feature=chat&model=demo-large&stream=true&includeUsage=true' | jq
```

```json
{"estimated":false,"promptTokens":2229,"completionTokens":159,"costMicros":5730}
```

**同一个请求，估算少算了 12.6% 的钱。**

### 4. 缓存命中：成本降 21%

```bash
curl -s -X POST 'localhost:8080/api/chat?tenant=t2&feature=batch&model=demo-large&cachedTokens=800' | jq
```

```json
{"promptTokens":2229,"cachedTokens":800,"costMicros":4530}   # 无缓存时是 5730
```

### 5. 429 重试：两行日志，只计一次费

```bash
curl -s -X POST 'localhost:8080/api/chat?tenant=t2&feature=batch&model=demo-small&failFirst=true' | jq
curl -s 'localhost:8080/api/logs/<traceId>' | jq
```

```json
[{"attempt":1,"status":"RATE_LIMITED","errorCode":"429","costMicros":0},
 {"attempt":2,"status":"OK","costMicros":1353,"promptTokens":2229,"completionTokens":159}]
```

### 6. 归因

```bash
curl -s 'localhost:8080/api/usage?groupBy=tenant' | jq
curl -s 'localhost:8080/api/usage?groupBy=model' | jq
curl -s 'localhost:8080/api/usage?groupBy=feature' | jq
curl -s 'localhost:8080/api/summary' | jq
```

```json
{"calls":6,"ok_calls":5,"estimated_calls":1,"avg_ttft_ms":259.2,
 "costMicros":22355,"costYuan":"0.022355"}
```

## 已经验证过的

| 场景 | 结果 |
| --- | --- |
| 普通调用（有 usage） | 2229 / 159 token，5730 微元，TTFT 259ms / 总 819ms |
| 流式无 usage（估算） | 估 1950 / 139，**偏差 -12.6%** |
| 流式 + include_usage | 精确 2229 / 159，与普通调用一致 |
| 缓存 800 token | 5730 → **4530**（↓21%） |
| 429 后重试 | 2 行，第一行 0 计费，第二行正常计费 |
| 按租户聚合 | t1 = 5730+5012+5730 = 16472 ✓ |
| 按模型聚合 | demo-large 4 次 = 21002 ✓ |
| 汇总 | 6 次调用 / 5 成功 / 1 次估算 / 合计 22355 微元 |

## 三个坑（都在代码里踩过）

1. **成本不能查询时现算** —— 单价一改，历史账全变。所以 `cost_micros` 落库。
2. **缓存 token 必须从输入里减掉再算**，否则缓存省的钱一分都体现不出来。
3. **用整数（微元）存钱，别用 double** —— 一次 0.00573 元，几十万次累加会飘。

## 这个示例没做的

- **异步落库**：真实系统不该在请求线程里同步写库，要用队列 + 批量写
- **按时间段聚合**：示例没有 `where created_at between ...`，做日报时自己加
- **厂商账单核对**：`request_id` 字段留着，但没做对账任务