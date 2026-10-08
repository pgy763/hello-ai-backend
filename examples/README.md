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
| `02-first-request/` | 第 2 章 · SSE 流式输出 | 📝 |
| `03-multi-provider/` | 第 3 章 · Provider 抽象 | 📝 |
| `04-virtual-key/` | 第 4 章 · 虚拟密钥与额度 | 📝 |
| `05-fallback/` | 第 5 章 · 降级链 | 📝 |
| `06-ratelimit/` | 第 6 章 · 令牌桶与熔断器 | 📝 |
| `07-state-machine/` | 第 7 章 · 任务状态机 | 📝 |
| `08-observability/` | 第 8 章 · 计量与成本 | 📝 |

> **当前状态：只有这份约定文档，示例代码还没开始补。**
> 第 1、2 章的正文代码是完整的，可以复制到自己的项目里直接跑；这个目录要放的是「每个机制一个独立可运行模块」的那一版。

第 9 章往后**不打算单独建示例模块** —— RAG 那两章的代码要跑起来得先起一个向量库，塞进「最小 Maven 模块」反而跑不通；第 11–14 章是压测、复盘、面试和毕设，本来就没有独立示例。这部分以 [`reference/`](../reference/) 和正文代码为准。

## 环境

| 组件 | 版本 |
| --- | --- |
| JDK | 17+ |
| Maven | 3.9+ |
| MySQL | 8.x（第 4 章起需要） |
| Redis | 6+（第 6 章起需要） |
