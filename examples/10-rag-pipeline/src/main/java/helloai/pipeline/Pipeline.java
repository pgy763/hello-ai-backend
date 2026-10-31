package helloai.pipeline;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 索引流水线。一次性脚本和流水线的区别全在这个类里：
 * 判断"要不要重建"、失败重试、死信、删除时清理孤儿块。
 */
class Pipeline {

    static final int MAX_ATTEMPTS = 3;

    record Doc(String id, String title, String permission, String content) {}

    record Chunk(String docId, String title, String permission, int seq, String text) {}

    record IndexState(String docId, String status, int attempt, String error,
                      String indexedHash, int chunks) {}

    private final Map<String, Doc> docs = new ConcurrentHashMap<>();
    private final Map<String, List<Chunk>> chunksByDoc = new ConcurrentHashMap<>();
    private final Map<String, float[]> vectors = new ConcurrentHashMap<>();   // key = docId#seq
    private final Map<String, IndexState> states = new ConcurrentHashMap<>();
    private final Set<String> failNext = ConcurrentHashMap.newKeySet();

    private final AtomicInteger embedCalls = new AtomicInteger();
    private final int chunkSize;

    Pipeline(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    // ------------------------------------------------------------ 写入口

    /** 新增或修改。内容没变就不重建 —— 这是增量索引的第一道闸。 */
    synchronized String upsert(Doc doc) {
        String hash = sha256(doc.content());
        Doc existing = docs.get(doc.id());
        docs.put(doc.id(), doc);

        IndexState st = states.get(doc.id());
        boolean contentSame = st != null && hash.equals(st.indexedHash());
        boolean metaSame = existing != null
            && existing.permission().equals(doc.permission())
            && existing.title().equals(doc.title());

        if (contentSame && metaSame) {
            return "跳过：正文和元数据都没变";
        }
        if (contentSame) {
            // 权限存在块的副本里，改了权限不重建索引，检索时用的还是旧权限 —— 越权的来源
            states.put(doc.id(), new IndexState(doc.id(), "PENDING", 0, null, null, countChunks(doc.id())));
            return "已排队：元数据变了（权限/标题），必须重建，否则块里还是旧权限";
        }

        states.put(doc.id(), new IndexState(doc.id(), "PENDING", 0, null, null, countChunks(doc.id())));
        return "已排队：PENDING";
    }

    /** 删除文档时，它的块必须一起删 —— 否则检索会召回已经不存在的文档。 */
    synchronized String delete(String docId) {
        if (docs.remove(docId) == null) {
            return "文档不存在";
        }
        List<Chunk> removed = chunksByDoc.remove(docId);
        int chunkCount = removed == null ? 0 : removed.size();
        if (removed != null) {
            removed.forEach(c -> vectors.remove(key(docId, c.seq())));
        }
        states.remove(docId);
        return "已删除，连带清理 " + chunkCount + " 个块";
    }

    // ------------------------------------------------------------ 执行

    /** 处理所有待索引的文档。返回这一轮做了多少事。 */
    synchronized Map<String, Object> processPending() {
        int processed = 0;
        int failed = 0;
        int dead = 0;

        for (String docId : List.copyOf(states.keySet())) {
            IndexState st = states.get(docId);
            if (!"PENDING".equals(st.status()) && !"FAILED".equals(st.status())) {
                continue;
            }
            Doc doc = docs.get(docId);
            if (doc == null) {
                states.remove(docId);
                continue;
            }

            try {
                if (failNext.remove(docId)) {
                    throw new IllegalStateException("模拟解析失败：文档格式不支持");
                }
                List<Chunk> chunks = chunk(doc);

                // 关键：先删旧块再写新块，避免改短之后残留旧内容
                List<Chunk> old = chunksByDoc.remove(docId);
                if (old != null) {
                    old.forEach(c -> vectors.remove(key(docId, c.seq())));
                }

                for (Chunk c : chunks) {
                    vectors.put(key(docId, c.seq()), embed(c.title() + " " + c.text()));
                    embedCalls.incrementAndGet();
                }
                chunksByDoc.put(docId, chunks);
                states.put(docId, new IndexState(docId, "INDEXED", st.attempt(),
                    null, sha256(doc.content()), chunks.size()));
                processed++;

            } catch (RuntimeException e) {
                int attempt = st.attempt() + 1;
                String status = attempt >= MAX_ATTEMPTS ? "DEAD" : "FAILED";
                states.put(docId, new IndexState(docId, status, attempt, e.getMessage(), null, 0));
                if ("DEAD".equals(status)) {
                    dead++;
                } else {
                    failed++;
                }
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("processed", processed);
        m.put("failed", failed);
        m.put("dead", dead);
        m.put("embedCallsTotal", embedCalls.get());
        return m;
    }

    /** 死信重新入队：人工确认问题修好了之后手动触发。 */
    synchronized String requeue(String docId) {
        IndexState st = states.get(docId);
        if (st == null) {
            return "文档不存在";
        }
        if (!"DEAD".equals(st.status())) {
            return "只有 DEAD 状态需要重新入队，当前是 " + st.status();
        }
        states.put(docId, new IndexState(docId, "PENDING", 0, null, null, 0));
        return "已重新入队，尝试次数归零";
    }

    void failNextTime(String docId) {
        failNext.add(docId);
    }

    // ------------------------------------------------------------ 检索

    record Hit(Chunk chunk, double score) {}

    List<Hit> search(String query, int topK, Set<String> allowedPermissions) {
        float[] q = embed(query);
        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<String, List<Chunk>> e : chunksByDoc.entrySet()) {
            for (Chunk c : e.getValue()) {
                // 权限过滤发生在检索时：不同用户看到的世界不一样
                if (allowedPermissions != null && !allowedPermissions.contains(c.permission())) {
                    continue;
                }
                float[] v = vectors.get(key(c.docId(), c.seq()));
                if (v != null) {
                    hits.add(new Hit(c, dot(q, v)));
                }
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.subList(0, Math.min(topK, hits.size()));
    }

    // ------------------------------------------------------------ 查询

    synchronized List<IndexState> states() {
        return states.values().stream().sorted(Comparator.comparing(IndexState::docId)).toList();
    }

    synchronized Map<String, Object> stats() {
        int chunkTotal = chunksByDoc.values().stream().mapToInt(List::size).sum();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docs", docs.size());
        m.put("chunks", chunkTotal);
        m.put("embedCallsTotal", embedCalls.get());
        m.put("byStatus", states.values().stream().collect(java.util.stream.Collectors.groupingBy(
            IndexState::status, java.util.stream.Collectors.counting())));
        return m;
    }

    // ------------------------------------------------------------ 内部

    private String status(String docId) {
        IndexState st = states.get(docId);
        return st == null ? "NONE" : st.status();
    }

    private int countChunks(String docId) {
        List<Chunk> c = chunksByDoc.get(docId);
        return c == null ? 0 : c.size();
    }

    private List<Chunk> chunk(Doc doc) {
        List<Chunk> chunks = new ArrayList<>();
        int seq = 0;
        for (String para : doc.content().split("\\n\\s*\\n")) {
            String text = para.replaceAll("\\s+", " ").strip();
            if (text.length() < 8) {
                continue;
            }
            if (text.length() <= chunkSize) {
                chunks.add(new Chunk(doc.id(), doc.title(), doc.permission(), seq++, text));
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (String sentence : text.split("(?<=。)")) {
                if (sb.length() + sentence.length() > chunkSize && sb.length() > 0) {
                    chunks.add(new Chunk(doc.id(), doc.title(), doc.permission(), seq++, sb.toString()));
                    sb.setLength(0);
                }
                sb.append(sentence);
            }
            if (sb.length() > 0) {
                chunks.add(new Chunk(doc.id(), doc.title(), doc.permission(), seq++, sb.toString()));
            }
        }
        return chunks;
    }

    private static String key(String docId, int seq) {
        return docId + "#" + seq;
    }

    private static float[] embed(String text) {
        int dim = 1024;
        float[] v = new float[dim];
        String norm = text.replaceAll("[\\s，。、；：？！（）\"'《》,.;:?!()]", "");
        for (int i = 0; i < norm.length(); i++) {
            v[Math.floorMod(String.valueOf(norm.charAt(i)).hashCode(), dim)] += 1.0f;
            if (i + 1 < norm.length()) {
                v[Math.floorMod(norm.substring(i, i + 2).hashCode(), dim)] += 2.0f;
            }
        }
        double sq = 0;
        for (float x : v) {
            sq += x * x;
        }
        if (sq > 0) {
            float scale = (float) (1.0 / Math.sqrt(sq));
            for (int i = 0; i < dim; i++) {
                v[i] *= scale;
            }
        }
        return v;
    }

    private static double dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}