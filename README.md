# Hello, AI Backend

![进度](https://img.shields.io/badge/章节进度-3%2F15-blue)
![java](https://img.shields.io/badge/Java-17%2B-orange)
![spring](https://img.shields.io/badge/Spring_Boot-3.x-green)
![语言](https://img.shields.io/badge/语言-中文-red)

> **写给 Java 后端工程师的 AI 接入手册。**

你手上有一套跑了三年的 Spring Boot 系统。老板说：接入大模型。

你搜了一圈 —— 教程全是 Python 的。

Spring AI 的官方文档能告诉你 `ChatClient` 怎么调，但它不回答你真正会撞上的问题：线上密钥怎么管、主模型挂了怎么自动切、账单怎么核、任务跑到一半断了怎么续、10 个业务线共用一个模型额度怎么分。

**这本书补的就是这一段。**

在线阅读：https://pgy763.github.io/hello-ai-backend/ （部署后可用）

---

## 这本书和别处有什么不同

| | |
| --- | --- |
| **从存量系统出发** | 不是"从零建一个新项目"，而是"给已经上线的系统接上 AI"。这是绝大多数公司真实的处境 |
| **只讲生产会遇到的** | 降级链、熔断、限流、计量、状态机、成本核算 —— 这些在 demo 里一个都不会出现 |
| **每章最小可跑** | 正文代码控制在 50–100 行，clone 下来就能运行，不必先读完前面十章 |
| **给结论也给取舍** | 不只说"怎么做"，还说"什么场景下不该这么做"。选型章节直接给出决策表 |

## 你需要什么

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17+ | Spring Boot 3.x 的最低要求 |
| Spring Boot | 3.x | 全书基于 3.2+ 编写 |
| MySQL | 8.x | 密钥、额度、任务状态持久化 |
| Redis | 6+ | 令牌桶限流与服务健康状态 |
| 一个大模型 API Key | — | OpenAI 兼容协议即可（DeepSeek / 通义 / 智谱均可） |

> 没有海外网络也能跟完全书 —— 所有示例都以 OpenAI 兼容协议为准，换 `base_url` 和模型名即可对接国内厂商。

---

## 全书目录

**序章** · [写给谁，以及为什么不是 Python 教程](docs/guide/00-intro.md) ✅

### 第一部分 · 起步：先把请求打通

| 章 | 标题 | 你会得到 | 状态 |
| --- | --- | --- | --- |
| 1 | [选型：Spring AI vs LangChain4j vs 裸 HTTP](docs/guide/01-selection.md) | 一张按团队规模和技术栈说话的决策表 | ✅ |
| 2 | [第一个请求：从 Controller 到 SSE 流式响应](docs/guide/02-first-request.md) | 可流式返回的前后端联调方案，含线程池与 Nginx 配置 | ✅ |
| 3 | [统一多模型接入](docs/guide/03-multi-provider.md) | Provider 抽象层，换模型不改业务代码 | 🚧 |

### 第二部分 · 生产必备的五个机制（全书核心）

| 章 | 标题 | 你会得到 | 状态 |
| --- | --- | --- | --- |
| 4 | [虚拟密钥：多租户、额度与审计](docs/guide/04-virtual-key.md) | 一套能让多条业务线共存的密钥体系 | 📝 |
| 5 | [降级链：主备模型自动切换](docs/guide/05-fallback.md) | 上游挂了业务不挂的健康探测与切换 | 📝 |
| 6 | [熔断与限流：Redis + Lua 令牌桶](docs/guide/06-ratelimit.md) | 抗住突发流量、守住套餐 RPM 上限 | 📝 |
| 7 | [多步任务编排：状态机与断点续跑](docs/guide/07-state-machine.md) | 长任务可恢复、可观测、可重放 | 📝 |
| 8 | [可观测性：token 计量与成本核算](docs/guide/08-observability.md) | 能按业务线报账的调用日志 | 📝 |

### 第三部分 · 让 AI 读懂你的数据

| 章 | 标题 | 你会得到 | 状态 |
| --- | --- | --- | --- |
| 9 | [RAG 落地：向量库选型与切分策略](docs/guide/09-rag-basics.md) | 检索命中率可调、可评估的 RAG 基础 | 📝 |
| 10 | [把 RAG 接进 Java 服务](docs/guide/10-rag-in-java.md) | 索引流水线与增量更新 | 📝 |

### 第四部分 · 收尾

| 章 | 标题 | 你会得到 | 状态 |
| --- | --- | --- | --- |
| 11 | [压测与容量规划](docs/guide/11-capacity.md) | 一张真实的价格 × 延迟取舍表 | 📝 |
| 12 | [生产事故复盘集](docs/guide/12-postmortem.md) | 别人踩过的坑，你不必再踩 | 📝 |
| 13 | [面试题：AI 后端工程师会被问到的 30 问](docs/guide/13-interview.md) | 求职直击 | 📝 |
| 14 | [毕业设计：给你自己的系统接上 AI](docs/guide/14-capstone.md) | 一个能写进简历的完整项目 | 📝 |

图例：✅ 已完稿 · 🚧 撰写中 · 📝 已规划

---

## 配套项目

本书的完整参考实现是一个**独立可跑的项目**，正文只讲其中最关键的 50–100 行。

> `reference/` 目录 —— 一个带虚拟密钥、降级链、熔断、令牌桶、状态机编排的 AI 接入层。写作中，第 8 章结束后放出。

如果你现在就想看一个能跑的版本，可以先看作者另一个仓库 [llm-gateway](https://github.com/pgy763/llm-gateway) —— 它是这本书参考实现的前身，同样是 Spring Boot 写的。

## 怎么读

**推荐路径**（按章节顺序）：

```
序章 → 1 → 2 → 3  （打通请求）
     → 4 → 5 → 6 → 7 → 8  （补上生产机制）
     → 9 → 10  （接自己的数据）
     → 11 → 12 → 13  （上线与面试）
```

**赶时间路径**（只有一天）：

```
序章 → 1（选型决策表） → 5（降级） → 8（计量） → 12（事故集）
```

**已经在做 AI 项目的人**：直接跳第 5、6、7、8 章 —— 这四章是市面资料最缺的部分。

## 环境踩坑

Windows 下跑这套东西会遇到编码、SQLite 版本、代理等一堆问题，已经单独整理成按报错索引的手册：

👉 [llm-dev-pitfalls](https://github.com/pgy763/llm-dev-pitfalls) —— 把报错复制进去 `Ctrl+F` 搜就行。

## 贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)。

最省事的方式：**开一个 Issue**，把你卡住的地方贴上来。我会把有代表性的整理成章节补充。

## License

- 代码（`examples/`、`reference/`、正文代码块）：[MIT](LICENSE)
- 文档正文：[CC BY-NC-SA 4.0](LICENSE-docs)

可自由用于学习与团队内部分享，请勿用于商业转载或二次售卖。

---

<sub>如果你正在把 AI 接进一个真实的 Java 系统里，这个仓库就是为你写的。点个 Star 方便下次找到。</sub>
