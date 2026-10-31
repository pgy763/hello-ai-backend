package helloai.rag;

import helloai.rag.Chunker.Chunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 内存版向量索引。真实项目换成 pgvector / Milvus，检索这一步的代码形状是一样的。 */
class RagIndex {

    record Hit(Chunk chunk, double score) {}

    private final Embedder embedder;
    private final List<Entry> entries = new ArrayList<>();

    record Entry(Chunk chunk, float[] vector) {}

    RagIndex(Embedder embedder) {
        this.embedder = embedder;
    }

    void build(List<Chunk> chunks) {
        entries.clear();
        for (Chunk chunk : chunks) {
            entries.add(new Entry(chunk, embedder.embed(chunk.title() + " " + chunk.text())));
        }
    }

    /** 检索。{@code permission} 为 null 表示不过滤；否则只在该权限范围内检索。 */
    List<Hit> search(String query, int topK, String permission) {
        float[] q = embedder.embed(query);

        List<Hit> scored = new ArrayList<>();
        for (Entry e : entries) {
            if (permission != null && !e.chunk().permission().equals(permission)) {
                continue;
            }
            scored.add(new Hit(e.chunk(), dot(q, e.vector())));
        }

        scored.sort(Comparator.comparingDouble(Hit::score).reversed());
        return scored.subList(0, Math.min(topK, scored.size()));
    }

    int size() {
        return entries.size();
    }

    List<Chunk> chunks() {
        return entries.stream().map(Entry::chunk).toList();
    }

    double avgChunkChars() {
        return entries.stream().mapToInt(e -> e.chunk().text().length()).average().orElse(0);
    }

    /** 两个向量都已归一化，点积就是余弦相似度。 */
    private static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}