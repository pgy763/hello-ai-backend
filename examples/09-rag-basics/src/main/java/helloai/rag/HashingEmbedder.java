package helloai.rag;

import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 不用外部 key 的替身 embedding：字符 bigram 哈希到固定维度，再归一化。
 *
 * <p>它不是语义向量 —— 它是「字面重合度」的向量化版本，所以：
 * <ul>
 *   <li>能真实反映「切分粒度怎么影响召回」这件事（这正是第 9 章要讲清楚的）</li>
 *   <li>换个 Embedder 实现就能接真模型，检索代码一行不用改</li>
 * </ul>
 *
 * <p>真实语义向量的好处是「同意不同词」也能召回（"退款"↔"退钱"），
 * 这一点替身做不到 —— 正文里专门说明了这个差别。
 */
@Component
class HashingEmbedder implements Embedder {

    private static final int DIM = 1024;

    @Override
    public float[] embed(String text) {
        Map<Integer, Float> sparse = new HashMap<>();
        String normalized = text.replaceAll("[\\s，。、；：？！（）\"'《》,.;:?!()]", "");

        // 单字（权重 1）+ 双字（权重 2）：短查询只有两三个字时，双字太少，单字兜底
        for (int i = 0; i < normalized.length(); i++) {
            add(sparse, String.valueOf(normalized.charAt(i)), 1.0f);
            if (i + 1 < normalized.length()) {
                add(sparse, normalized.substring(i, i + 2), 2.0f);
            }
        }

        float[] vector = new float[DIM];
        double norm = 0;
        for (Map.Entry<Integer, Float> e : sparse.entrySet()) {
            vector[e.getKey()] = e.getValue();
            norm += e.getValue() * e.getValue();
        }
        if (norm > 0) {
            float scale = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < DIM; i++) {
                vector[i] *= scale;
            }
        }
        return vector;
    }

    private static void add(Map<Integer, Float> sparse, String token, float weight) {
        int slot = Math.floorMod(token.hashCode(), DIM);
        sparse.merge(slot, weight, Float::sum);
    }

    @Override
    public int dim() {
        return DIM;
    }
}