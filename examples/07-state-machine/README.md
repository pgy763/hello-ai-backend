# 07-state-machine · 任务状态机与断点续跑

第 7 章的完整可运行版本。不需要 key、不需要外部数据库（用文件版 H2，进程重启状态还在）。

## 演示什么

一个「工单分析」任务，四步：

```
extract(LLM) → classify(LLM) → summarize(LLM) → persist(本地)
```

- **步骤状态落库**：每一步是 `PENDING / RUNNING / SUCCEEDED / FAILED`，`attempt` 记着试了几次
- **断点续跑**：从第一个不是 `SUCCEEDED` 的步骤继续，已完成的不重跑、不重复花钱
- **CAS 抢占**：连点两次、或者两个实例同时续跑，只有一个能拿到任务
- **租约 + 僵尸回收**：进程被 `kill -9`，租约过期后任务能被接手

## 怎么跑

```bash
cd examples/07-state-machine
mvn spring-boot:run
```

想验证「进程被杀之后还能续跑」，用这个更慢的配置（每步 2 秒，方便中途强杀）：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--demo.step-delay-ms=2000
```

## 预期看到什么

### 1. 一次跑完

```bash
TASK=$(curl -s -X POST 'localhost:8080/api/tasks?tenant=t1' | jq -r .taskId)
curl -s -X POST "localhost:8080/api/tasks/$TASK/run" | jq
curl -s 'localhost:8080/api/stats'
```

```json
{"steps":["extract=SUCCEEDED/a1","classify=SUCCEEDED/a1",
          "summarize=SUCCEEDED/a1","persist=SUCCEEDED/a1"]}
{"llmCalls":3,"tokens":600}
```

### 2. 第三步失败

```bash
curl -s -X POST 'localhost:8080/api/fault?step=summarize'   # 让下一步在这失败一次
curl -s -X POST "localhost:8080/api/tasks/$TASK/run" | jq .result
# {"status":"FAILED","failedStep":"summarize","claimed":true,...}
curl -s "localhost:8080/api/tasks/$TASK" | jq '.steps[] | {name,status,attempt}'
# extract=SUCCEEDED/1  classify=SUCCEEDED/1  summarize=FAILED/1  persist=PENDING/0
```

此时已经花掉 **2 次调用、400 token**。

### 3. 续跑：只补一步

```bash
curl -s -X POST "localhost:8080/api/tasks/$TASK/resume" | jq .result
# {"status":"SUCCEEDED",...}
curl -s 'localhost:8080/api/stats'
# {"llmCalls":3,"tokens":600}      ← 只多了 1 次，不是 3 次
```

**从头重跑要 5 次调用（1000 token），续跑只要 3 次（600 token）。**

### 4. 中途 kill -9，重启后接着跑

```bash
# 慢速启动
mvn spring-boot:run -Dspring-boot.run.arguments=--demo.step-delay-ms=2000
TASK=$(curl -s -X POST 'localhost:8080/api/tasks?tenant=t1' | jq -r .taskId)
curl -s -X POST "localhost:8080/api/tasks/$TASK/run" &   # 后台跑
sleep 5 && kill -9 <pid>                                # 趁 summarize 在跑时杀掉
```

重启服务（同一个 H2 文件库），等 8 秒让租约过期：

```bash
curl -s "localhost:8080/api/tasks/$TASK" | jq '{status:.task.status, error:.task.error}'
# {"status":"FAILED","error":"runner lost: 租约过期"}
curl -s -X POST 'localhost:8080/api/recover'      # 启动时其实已经自动扫过一遍
curl -s -X POST "localhost:8080/api/tasks/$TASK/resume" | jq .result
curl -s 'localhost:8080/api/stats'
# {"llmCalls":1,"tokens":200}    ← 新进程只补了被打断的那一步
```

### 5. 并发续跑只成功一个

```bash
# 边跑边再打两次 resume
curl -s -X POST "localhost:8080/api/tasks/$TASK/resume" | jq .result
# {"status":"RUNNING","claimed":false,"message":"任务当前状态为 RUNNING，不能在这里启动"}
```

## 已经验证过的

| 场景 | LLM 调用 | token | 步骤状态 |
| --- | --- | --- | --- |
| 一次跑完 | 3 | 600 | 全部 `SUCCEEDED/a1` |
| 第 3 步失败 | 2 | 400 | `summarize=FAILED/a1`，`persist=PENDING/a0` |
| 续跑 | 3（+1） | 600 | `summarize` 变 `SUCCEEDED/a2`，前两步 attempt 仍是 1 |
| **从头重跑的对照** | 5 | 1000 | —— 这就是续跑省下来的 |
| kill -9 后重启 | 新进程 1 | 200 | 已完成的 2 步 attempt 仍是 1，只有被中断那步重跑 |
| 3 个并发续跑 | 3（不重复） | 600 | 只有 1 个 `claimed=true` |

## 一个踩出来的坑：写延迟会让「已完成」白丢

第一次测强杀时，明明是「已完成 extract、classify 后才杀的」，续跑却把 classify 重跑了一遍。

原因是 H2 默认允许最多 1 秒的写延迟，`kill -9` 把最后一条 `SUCCEEDED` 吞掉了。把 `WRITE_DELAY=0` 加到 JDBC URL 之后，行为就稳定了。

**这条在生产里等价于「状态写入必须真正落盘」** —— MySQL 用默认的 `innodb_flush_log_at_trx_commit=1` 就没这个问题，而为了性能把它调成 2 的时候，你得接受同样的风险。

## 这个示例没做的

- **异步执行**：`/run` 是同步的，真实系统要丢线程池或 MQ，接口立刻返回 taskId
- **重试与退避**：`attempt` 已经在记了，但没有「失败后自动重试 N 次」的策略
- **补偿/回滚**：步骤产生的副作用（发消息、发邮件）怎么做逆向操作