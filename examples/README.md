# examples

按章节存放正文中的**最小可运行示例**。

## 约定

- 一个章节一个目录，命名 `NN-slug`，与 `docs/guide/` 下的文件名保持一致
- 每个目录是一个**独立的 Maven 模块**，可以单独 `mvn spring-boot:run`
- 示例代码控制在 50–100 行 —— 目的是让读者看清机制，不是给一个完整项目
- 每个目录必须带 `README.md`，写清三件事：**演示什么 / 怎么跑 / 预期看到什么**

## 配置

示例模块会共用一份 `application.example.yml` 模板（随第一个模块一起提交）：

```bash
cp application.example.yml application-local.yml
# 填入你自己的 API Key
```

`application-local.yml` 已在 `.gitignore` 中，**永远不会被提交**。

## 计划中的模块

| 目录 | 对应章节 | 状态 |
| --- | --- | --- |
| `01-selection/` | 第 1 章 · 三种方案的最小实现对比 | 📝 |
| [`01-selection/`](./01-selection/) | 第 1 章 · 同一个需求，三种写法 | ✅ 可跑（假上游，对照实验） |
| [`02-first-request/`](./02-first-request/) | 第 2 章 · SSE 流式输出 | ✅ 可跑 |
| [`03-multi-provider/`](./03-multi-provider/) | 第 3 章 · Provider 抽象 | ✅ 可跑（无需 key） |
| [`04-virtual-key/`](./04-virtual-key/) | 第 4 章 · 虚拟密钥与额度 | ✅ 可跑（H2 + 并发自检） |
| [`05-fallback/`](./05-fallback/) | 第 5 章 · 降级链 | ✅ 可跑（九种故障场景） |
| [`06-ratelimit/`](./06-ratelimit/) | 第 6 章 · 令牌桶与熔断器 | ✅ 可跑（可选 Redis） |
| [`07-state-machine/`](./07-state-machine/) | 第 7 章 · 任务状态机 | ✅ 可跑（可验强杀续跑） |
| [`08-observability/`](./08-observability/) | 第 8 章 · 计量与成本 | ✅ 可跑（自带单价表） |
| [`09-rag-basics/`](./09-rag-basics/) | 第 9 章 · 切分与检索召回 | ✅ 可跑（替身 embedding，无向量库） |
| [`10-rag-pipeline/`](./10-rag-pipeline/) | 第 10 章 · 增量索引与权限 | ✅ 可跑（含死信重入） |
| [`11-capacity/`](./11-capacity/) | 第 11 章 · 压测与容量估算 | ✅ 可跑（假上游，零成本压测） |
| [`14-capstone/`](./14-capstone/) | 第 14 章 · 毕业设计：工单智能助手 | ✅ 可跑（端到端，含故障注入） |

> **当前状态：第 1、2–11、14 章的 12 个模块都能直接跑。CI 会编译全部模块。**
> - `02-first-request/` 需要一把真 key（或任何 OpenAI 兼容服务），打开网页就能对话
> - `03-multi-provider/` 自带假上游，**不需要 key、不需要外网**，能看见差异翻译的全过程
> - `04-virtual-key/` 用 H2 内存库替代 MySQL，自带 50 线程并发自检，能压出「不超卖」的结果
> - `09-rag-basics/` 用一个替身 embedding 把「切分粒度怎么影响召回」测成数字，不需要向量库
> - `10-rag-pipeline/` 演示增量索引、权限过滤和死信重入，不需要数据库
> - `11-capacity/` 用自己的假上游压自己的接入层，能压出饱和曲线且不花一分钱
> - `14-capstone/` 是把前六章能力装到一起的完整项目：限流 + 降级 + 状态机 + RAG + 计量 + 死信

第 9、10 章也做了示例 —— 关键在于把 embedding 抽成接口、用替身实现，这样不用起向量库也能把「切分、召回、增量、权限」这些机制跑出来，换真模型只改一个类。

第 12、13 章是复盘与面试题，本来就没有独立示例。这部分以 [`reference/`](../reference/) 和正文代码为准。

## 环境

| 组件 | 版本 |
| --- | --- |
| JDK | 17+ |
| Maven | 3.9+ |
| MySQL | 8.x（第 4 章起需要） |
| Redis | 6+（第 6 章起需要） |
