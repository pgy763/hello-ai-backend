# examples

按章节存放正文中的**最小可运行示例**。

## 约定

- 一个章节一个目录，命名 `NN-slug`，与 `docs/guide/` 下的文件名保持一致
- 每个目录是一个**独立的 Maven 模块**，可以单独 `mvn spring-boot:run`
- 示例代码控制在 50–100 行 —— 目的是让读者看清机制，不是给一个完整项目
- 每个目录必须带 `README.md`，写清三件事：**演示什么 / 怎么跑 / 预期看到什么**

## 配置

所有示例共用一份 `application.example.yml` 模板：

```bash
cp application.example.yml application-local.yml
# 填入你自己的 API Key
```

`application-local.yml` 已在 `.gitignore` 中，**永远不会被提交**。

## 计划中的模块

| 目录 | 对应章节 | 状态 |
| --- | --- | --- |
| `01-selection/` | 第 1 章 · 三种方案的最小实现对比 | 🚧 |
| `02-first-request/` | 第 2 章 · SSE 流式输出 | 📝 |
| `03-multi-provider/` | 第 3 章 · Provider 抽象 | 📝 |
| `04-virtual-key/` | 第 4 章 · 虚拟密钥与额度 | 📝 |
| `05-fallback/` | 第 5 章 · 降级链 | 📝 |
| `06-ratelimit/` | 第 6 章 · 令牌桶与熔断器 | 📝 |
| `07-state-machine/` | 第 7 章 · 任务状态机 | 📝 |
| `08-observability/` | 第 8 章 · 计量与成本 | 📝 |

> 当前阶段：骨架已建，示例代码待第 1 章完稿后开始逐个补齐。

## 环境

| 组件 | 版本 |
| --- | --- |
| JDK | 17+ |
| Maven | 3.9+ |
| MySQL | 8.x（第 4 章起需要） |
| Redis | 6+（第 6 章起需要） |
