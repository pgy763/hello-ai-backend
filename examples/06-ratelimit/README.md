# 06-ratelimit · 令牌桶与熔断器

第 6 章的完整可运行版本。**默认不需要 Redis、不需要 key** —— 进程内的限流器和熔断器都能直接跑；Redis + Lua 那条路默认也支持，本地有 Redis 就行。

## 演示什么

| 组件 | 为什么要有 | 正文 |
| --- | --- | --- |
| `LocalTokenBucket` | 进程内的正确实现：判断和扣减在同一个临界区 | 6.3 |
| `NaiveTokenBucket` | **故意写错**的版本，用来证明「不原子会超发多少」 | 6.3 |
| `RedisTokenBucket` + `bucket.lua` | 多实例部署唯一正确的做法 | 6.4 |
| `CircuitBreaker` | 闭 / 开 / 半开三态，让失败快速失败 | 6.6 |

## 怎么跑

```bash
cd examples/06-ratelimit
mvn spring-boot:run
```

要验证 Redis 那条路，先起一个 Redis：

```bash
docker run -d -p 6379:6379 redis:7-alpine
```

没起也不影响：应用照常启动，只是 `/api/burn?impl=redis` 会报连接失败。

## 预期看到什么

### 1. 不原子会超发多少

```bash
# 正确实现：容量 10，40 个并发
curl -s 'localhost:8080/api/burn?impl=atomic&n=40'
# {"passed":10,"rejected":30,"oversold":0,"elapsedMs":9}

# 故意写错：读 → 算 → 写 三步分开
curl -s 'localhost:8080/api/burn?impl=naive&n=40'
# {"passed":40,"rejected":0,"oversold":30,"elapsedMs":5}
```

**容量 10 的桶，放过去了 40 个。** 这就是限流器只做了一半的样子 —— 而且它还让你以为自己在限流。

### 2. Redis + Lua 也是 10 个

```bash
curl -s 'localhost:8080/api/burn?impl=redis&n=40'
# {"passed":10,"rejected":30,"oversold":0,"elapsedMs":110}
```

同样是 10 个，代价是 110ms（进程内 9ms）—— 每个请求多一次 Redis 往返。

### 3. 补充速率真的生效

```bash
curl -s 'localhost:8080/api/burn?impl=atomic&n=40'          # 打空：通过 10
sleep 1
curl -s 'localhost:8080/api/burn?impl=atomic&n=8&reset=false'
# {"passed":5,"rejected":3}   ← 1 秒补 5 个（refillPerSec=5）
```

### 4. 熔断器走一遍完整状态机

```bash
curl -s 'localhost:8080/api/cb/selftest'
```

```json
{"finalState":"CLOSED",
 "steps":["上游持续失败期：放行，上游失败（2µs）→ CLOSED", "...", "...→ OPEN",
          "熔断期内：拒绝（0µs，未打上游）", "..."],
 "counters":{"upstreamCallsTotal":7,"upstreamCallsDuringOpen":0,"rejectAvgMicros":1},
 "timeline":["CLOSED -> OPEN：连续失败 5 次，熔断 800ms",
             "OPEN -> HALF_OPEN：冷却结束，放一个探测请求",
             "HALF_OPEN -> OPEN：探测失败，再等一轮",
             "OPEN -> HALF_OPEN：冷却结束，放一个探测请求",
             "HALF_OPEN -> CLOSED：探测成功，恢复正常"]}
```

三个关键数字：

- 熔断期间上游被调用 **0** 次
- 熔断期间单次拒绝 **~1µs**
- 全程上游总共被调用 **7** 次（5 次失败 + 2 次探测）

## 已经验证过的

| 项 | 结果 |
| --- | --- |
| 原子实现 40 并发 | 通过 10 / 拒绝 30 / 超发 0，9ms |
| naive 实现 40 并发 | 通过 **40** / 超发 **30** |
| Redis Lua 40 并发 | 通过 10 / 拒绝 30，110ms |
| 1 秒后补充 | 通过 5（refillPerSec=5） |
| 熔断阈值 | 连续 5 次失败 → OPEN |
| 熔断期间 | 8 次拒绝，上游调用 0 次，每次 ~1µs |
| 半开探测失败 | HALF_OPEN → OPEN |
| 上游恢复后 | HALF_OPEN → CLOSED |

## 一个值得记住的翻车记录

第一版「原子实现」我写的是**判断在锁外、只给写入加锁**。同样的 40 并发，通过数是 **11** —— 超发 1 个。

原因：两个线程都在锁外读到 `tokens=10`，一个写成功，另一个也写成功（因为只加了锁、没做比较）。**只锁一半等于没锁，而且只超发一个，压测不仔细根本发现不了。**

修法是把回填、判断、扣减全都放进同一个 `synchronized` 方法里（`Bucket.tryTake`）。

## 这个示例没做的

- **多维限流**（全局 / 租户 / 模型）：正文 6.5 讲了怎么用一条 Lua 扣多个 key，示例只做单维度
- **`Retry-After` 计算**：正文给了公式，示例没有暴露这个接口
- **全局熔断**（计数器放 Redis）：正文 6.7 解释了为什么多数场景不值得