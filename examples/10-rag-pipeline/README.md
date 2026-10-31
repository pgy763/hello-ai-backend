# 10-rag-pipeline · 增量索引、权限过滤与死信

第 10 章的完整可运行版本。不需要 key、不需要数据库。

## 演示什么

| 能力 | 正文 |
| --- | --- |
| 用内容哈希判断「要不要重建」，只重算改动的那篇 | 10.2 |
| 元数据（权限）变了也必须重建，否则越权 | 10.3 |
| 删除文档时连带清理它的块 | 10.4 |
| 检索时按权限过滤，同一查询不同用户不同结果 | 10.5 |
| 失败计数 → 死信 → 人工重入 | 10.6 |

## 怎么跑

```bash
cd examples/10-rag-pipeline
mvn spring-boot:run
```

## 预期看到什么

### 1. 首次索引

```bash
curl -s -X POST localhost:8080/api/demo/load | jq
curl -s -X POST localhost:8080/api/reindex | jq
curl -s localhost:8080/api/state | jq '.docs'
```

```json
{"processed":3,"failed":0,"dead":0,"embedCallsTotal":6}
[{"docId":"d1","status":"INDEXED","chunks":2}, ...]
```

### 2. 只改一篇 → 只重算那一篇

```bash
# 改 d2 正文后重新索引
curl -s -X POST localhost:8080/api/reindex | jq
# {"processed":1,"embedCallsTotal":9}   ← 增量 3，不是 6
```

内容没变再灌一次：

```json
{"result":"跳过：正文和元数据都没变"}
```

### 3. 只改权限、正文不动 → 也必须重建

```json
{"result":"已排队：元数据变了（权限/标题），必须重建，否则块里还是旧权限"}
```

重建前后，`alice` 搜「专票」的结果从「看不到 d2」变成「d2 排第一」。

### 4. 删除连带清块

```bash
curl -s -X DELETE localhost:8080/api/docs/d2 | jq
# {"result":"已删除，连带清理 2 个块"}

curl -s -G localhost:8080/api/search --data-urlencode 'q=发票' -d 'user=carol' -d 'k=5' | jq '.hits[].docId'
# 删除前: d2, d2, d3, d1, d1
# 删除后: d3, d1, d1, d3          ← d2 彻底消失
```

### 5. 权限过滤

```bash
for u in alice bob carol; do
  curl -s -G localhost:8080/api/search --data-urlencode 'q=框架协议' -d "user=$u" -d k=5 | jq -c '{user,allowedPermissions,hits:[.hits[].docId]}'
done
```

```json
{"user":"alice","allowedPermissions":["public"],"hits":["d1","d1"]}
{"user":"bob","allowedPermissions":["finance","public"],"hits":["d1","d1"]}
{"user":"carol","allowedPermissions":["cs","finance","public"],"hits":["d3","d3","d1","d1"]}
```

**只有 carol 能看到正确答案（d3 大客户合同）。**

### 6. 失败三次进死信，人工重入

```bash
for i in 1 2 3; do
  curl -s -X POST localhost:8080/api/fail/d3
  curl -s -X POST localhost:8080/api/reindex | jq -c
done
curl -s localhost:8080/api/state | jq '.docs[] | select(.docId=="d3")'
# {"status":"DEAD","attempt":3,"error":"模拟解析失败：文档格式不支持"}

curl -s -X POST localhost:8080/api/dlq/d3/requeue | jq
curl -s -X POST localhost:8080/api/reindex | jq -c
# {"processed":1,...}  → d3 变回 INDEXED
```

## 已经验证过的

| 场景 | 结果 |
| --- | --- |
| 3 篇首次索引 | 6 块 / 6 次 embedding |
| 改 1 篇后重新索引 | `processed=1`，embedding 增量 3（只算改动那篇的块） |
| 内容不变重灌 | 「跳过」，embedding 不增加 |
| 只改权限 | 排队重建；重建后 alice 从看不到变成看得到 d2 |
| 删除文档 | 连带清理 2 个块；后续检索不再出现该文档 |
| 权限过滤 | alice/bob 看不到 cs 文档，carol 能看到且排第一 |
| 失败重试 | attempt 1 → 2 → 3 → `DEAD`，不再自动重试 |
| 死信重入 | `requeue` 后 attempt 归零，重新索引成功 |

## 这个示例没做的

- **真正的调度**：`/api/reindex` 是手动触发，真实系统用定时任务 + 消息队列
- **解析器**：只吃纯文本，PDF/Word/HTML 需要接 Tika 之类
- **向量库**：块和向量都在内存里，重启即失
- **权限组**：示例用的是权限字符串，正文 10.3 讲了为什么组 ID 更稳