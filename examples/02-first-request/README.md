# 02-first-request · SSE 流式输出

第 2 章的完整可运行版本。**这是 examples 里目前唯一真能跑起来的模块。**

## 演示什么

不加任何模型 SDK，只用 JDK 自带的 `HttpClient` 打通整条链路：

```
浏览器 ──fetch──> Controller ──线程池──> HttpClient ──SSE──> 模型厂商
   ▲                  │                                    │
   └──── SseEmitter ──┘ <──────── 逐块解析 data: ──────────┘
```

对应正文的四件事：

| 正文 | 这里的实现 |
| --- | --- |
| 2.3 MVC 下的完整实现 | `StreamChatController` —— `SseEmitter` + 独立线程池，绝不在 Tomcat 请求线程上跑完整段回答 |
| 2.4 流式响应为什么不能用普通 `@ResponseBody` | 看 `produces = text/event-stream`：返回的是一个「还会继续写」的对象 |
| 客户端断开要停止上游 | `StreamHandler.onNext` 里 `send()` 抛 `IOException` → 抛 `StreamAbortedException` → `try-with-resources` 关掉上游连接 |
| 2.5 前端怎么消费 | `resources/static/index.html`，`fetch` + `ReadableStream`，带「停止」按钮和首字延迟计时 |

## 怎么跑

```bash
cd examples/02-first-request

# 1) 配 key（二选一）
export LLM_API_KEY=sk-xxxx                     # 方式一：环境变量
cp ../application.example.yml application-local.yml   # 方式二：本地配置文件

# 2) 起服务
mvn spring-boot:run
```

然后打开 <http://localhost:8080>。

换厂商只改三行（`llm.base-url` / `llm.api-key` / `llm.model`），任何 OpenAI 兼容的服务都能用：

```bash
LLM_BASE_URL=https://api.deepseek.com LLM_MODEL=deepseek-chat mvn spring-boot:run
```

没有 key 也能跑：服务能起来、页面能打开，只是发消息会失败 —— 启动日志里会有一行 `没有配 LLM_API_KEY` 的警告。

> `application-local.yml` 是按**启动目录**找的（`spring.config.import: optional:file:application-local.yml`）。
> 在模块目录里 `mvn spring-boot:run` 没问题；如果你从别的地方 `java -jar`，要么先 `cd` 过来，要么改用环境变量。

## 预期看到什么

**页面**：输入框 + 发送/停止按钮，回答逐字出现，下面一行实时显示首字延迟和总耗时。

**控制台**：字是一块块出现的，不是等几秒后整段蹦出来。这一条是流式输出唯一的意义 —— 首字延迟从 5 秒降到 0.5 秒，用户感知完全不同。

**点「停止」或者直接关掉页面**：日志会出现

```
AiStreamClient : 客户端已断开，上游调用已中止
```

这一行的意思是：**上游连接关了，从这一刻起不再为这次回答付钱。** 没有它，用户关页面后模型还会把整段回答生成完，钱照付。

## 这里有几个坑，跑起来才看得见

作者踩过，正文里也写了：

1. **不要给 `HttpRequest` 设 `timeout()`** —— 那是整个请求的总超时，会在回答说到一半时把流掐断。
2. **客户端断开是靠「下一次写失败」发现的**，所以断开越大块越快发现；一次只写几十字节时，可能要好几块才报错。想更快，要靠心跳或上游自带的超时。
3. **断开后不要 `completeWithError()`** —— 错误发不出去，只会让 Spring 记一条 `dispatcherServlet threw exception` 的 ERROR，把真正的问题淹没掉。安静 `complete()` 就够了。
4. **`ObjectMapper` 不要每个请求 new 一个**，`HttpClient` 也是 —— 两者都线程安全，全局一份即可。

## 已经验证过的

| 项 | 结果 |
| --- | --- |
| `mvn package` | 通过（Maven 3.9 / JDK 21 编出 17 字节码） |
| 启动 | 2.1 秒，`/api/chat/ping` 返回 `{"ok":"true"}` |
| 流式是真流 | 11 个数据块，实测到达间隔 ~90ms，不是攒完一次性发 |
| 前端解析 | 与页面同一套逻辑实测：11 帧拼成完整文本 |
| 中途断开 | 上游在第 8/60 块被关闭，日志有「客户端已断开」，**无 ERROR、无堆栈** |
| 不带 key 启动 | 能起，日志给出明确警告 |

## 工程量

4 个类、约 240 行 Java，一个 `pom.xml`，一个 `index.html`。没有模型 SDK、没有 Guava、没有额外依赖 —— 只有 `spring-boot-starter-web`。