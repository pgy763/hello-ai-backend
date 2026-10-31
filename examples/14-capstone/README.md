# 14-capstone · 工单智能助手（毕业设计）

第 14 章的完整项目。**默认不用 key、不联网**，克隆下来就能跑；想接真模型，加两个环境变量即可。

## 演示什么

| 能力 | 来自 | 类 |
| --- | --- | --- |
| 令牌桶限流（5 容量 / 2 QPS） | 第 6 章 | `LlmGateway.TokenBucket` |
| 主模型 → 降级 | 第 5 章 | `LlmGateway.complete` |
| 状态机 + CAS 抢占 | 第 7 章 | `TicketService.tick` |
| 切分与检索（RAG） | 第 9 章 | `Kb` |
| 退避重试 + 死信 | 第 10 章 | `TicketService.process` |
| token 计量与成本归因 | 第 8 章 | `llm_call` 表 |

## 怎么跑（不用 key）

```bash
cd examples/14-capstone
mvn spring-boot:run
```

```bash
curl -s -X POST localhost:8080/api/tickets \
  -H 'Content-Type: application/json' \
  -d '{"text":"我买的东西退款多久能到账？"}' | jq
# {"id":"t-1ffe373b","status":"PENDING"}

sleep 3
curl -s localhost:8080/api/tickets/t-1ffe373b | jq
```

预期：状态 `ANSWERED`，分类「退款」，答案带 `[1] [2]` 引用，成本约 0.0007 元。

## 用自己的 API key（可选）

**key 只从环境变量读，不写进任何文件。** 代码里也没有默认值 —— 没设置就是空串，调用时直接报错，不会把半个请求发出去。

```powershell
# PowerShell：只在当前窗口生效
$env:HELLOAI_MODE = "real"
$env:HELLOAI_BASE_URL = "https://api.deepseek.com/v1"   # 换成你的厂商
$env:HELLOAI_API_KEY = "sk-你的key"
$env:HELLOAI_MODEL = "deepseek-chat"

mvn spring-boot:run
```

```bash
# bash 同理
export HELLOAI_MODE=real
export HELLOAI_BASE_URL=https://api.deepseek.com/v1
export HELLOAI_API_KEY=sk-你的key
export HELLOAI_MODEL=deepseek-chat
mvn spring-boot:run
```

任何 **OpenAI 兼容**接口都能用（DeepSeek、通义、智谱、Kimi、vLLM 本地部署等），换厂商只改 `HELLOAI_BASE_URL` 和 `HELLOAI_MODEL`。

跑完清掉环境变量：

```powershell
Remove-Item Env:\HELLOAI_API_KEY, Env:\HELLOAI_MODE, Env:\HELLOAI_BASE_URL, Env:\HELLOAI_MODEL
```

### 四条规矩（别踩）

1. **不要把 key 写进 `application.yml` 或任何被提交的文件。** 本模块的 yml 里只有 `${HELLOAI_API_KEY:}`，没有真值。
2. **不要把 key 贴进 Issue、聊天记录、截图。** 一旦贴出去就当泄漏处理 —— 立刻去控制台**吊销**，而不是删文件。
3. **`application-local.yml` 已在 `.gitignore` 里**，但它仍然是个文件；能不用就不用，环境变量更安全。
4. **拿真 key 跑之前先想清楚在验证什么。** 只是看降级/死信的话，用 `fake` 模式就够，一分钱不花。

真实模式下的成本按你的账单算：一次工单 2 次调用（分类 + 生成），实测 0.0005–0.0008 元量级；但单价是示例值，换成你的真实单价后请改 `capstone.pricing.*`。

启动日志会告诉你当前是什么模式，**但不会打印 key 的内容**：

```
模型模式: REAL   base-url=https://api.deepseek.com/v1   api-key=已设置   model=deepseek-chat
```

## 预期看到什么

### 1. 正常路径

| 工单 | 状态 | 分类 | 成本 |
| --- | --- | --- | --- |
| 我买的东西退款多久能到账？ | ANSWERED | 退款 | 0.0008 元 |
| 开专票需要提供哪些资料？ | ANSWERED | 发票 | 0.0007 元 |
| 新疆发货一般要几天？ | ANSWERED | 物流 | 0.0007 元 |

### 2. 主模型故障 → 自动降级

```bash
curl -s -X POST 'localhost:8080/api/fault?mode=primary_down'
```

新工单仍然 `ANSWERED`，但 `/api/cost` 的 `byProvider` 里出现 `small-model`，成本降到约 0.0001 元。

### 3. 全挂 → 退避重试 → 死信 → 恢复

```bash
curl -s -X POST 'localhost:8080/api/fault?mode=all_down'
```

| 时刻 | 状态 | attempt |
| --- | --- | --- |
| 775ms | PENDING | 1 |
| 2248ms | PENDING | 2 |
| 4487ms | **DEAD** | 3 |

```bash
curl -s -X POST 'localhost:8080/api/fault?mode=ok'
curl -s -X POST localhost:8080/api/tickets/<id>/retry
```

### 4. 成本账本

```bash
curl -s localhost:8080/api/cost | jq
```

6 次调用 / 2136 微元（0.0021 元），其中生成 2000、分类 136。

## 已经验证过的

| 项 | 结果 |
| --- | --- |
| 三张工单 | 全部 ANSWERED，分类正确（退款/发票/物流） |
| 单张成本 | 0.0007–0.0008 元（6 次调用 2136 微元） |
| 主模型故障 | 降级 small-model，单张成本 0.0001 元（省 86%） |
| 全挂重试 | attempt 1→2→3，775ms/2248ms/4487ms，进 DEAD |
| 死信恢复 | retry 后 2 秒内 ANSWERED |
| **real 模式** | 用本地 mock 的 OpenAI 兼容服务验证过：请求带 `Authorization`，用返回的 `usage` 记账，链路通 |
| **real 模式缺 key** | 立刻报错「capstone.llm.api-key 为空」，不会发出请求 |

## 这个示例没做的

- **真正的权限体系**：知识库没有按用户过滤（第 10 章讲了怎么加）
- **多实例**：抢占是单实例写法，多实例要加 CAS（第 7 章）
- **持久化知识库**：向量在内存里，重启重建
- **流式**：工单是异步的，不需要 SSE