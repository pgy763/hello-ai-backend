package helloai.capstone;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 知识库：切分 + 向量化 + 检索。
 *
 * <p>第 9、10 章的东西压缩到这里。embedding 仍是字面重合度的替身，
 * 换成真模型只需要改 {@link #embed(String)} 一个方法。
 */
@Component
class Kb {

    record Chunk(String docId, String title, int seq, String text) {}

    record Hit(Chunk chunk, double score) {}

    private static final int DIM = 1024;
    private static final int MAX_CHARS = 200;

    private static final List<Doc> DOCS = List.of(
        new Doc("d1", "退款政策", """
            支持 7 天无理由退款，从签收次日 0 点开始计算，商品需保持完好、配件齐全。

            定制类商品、已拆封的食品、贴身用品不参与无理由退款，只能走质量问题通道。

            退款到账时间取决于支付方式：银行卡 1-3 个工作日，支付宝和微信 24 小时内到账。"""),
        new Doc("d2", "发票开具", """
            电子普通发票在下单后 48 小时内开出，发送到订单预留邮箱。

            增值税专用发票需要提供公司名称、税号、开户行、账号、地址、电话，审核约 3 个工作日。

            发票金额为实付金额，不含运费和优惠券抵扣部分。"""),
        new Doc("d3", "物流时效", """
            普通快递默认 3-5 天送达，偏远地区需要再加 2-3 天。

            物流信息一般每 2 小时更新一次，超过 48 小时没有轨迹可以提交查件申请。

            包裹显示签收但实际未收到，请在 24 小时内联系我们调取签收底单。"""),
        new Doc("d4", "会员积分", """
            消费 1 元累计 1 积分，100 积分抵 1 元，积分有效期为 12 个月。

            每年 12 月 31 日 24 点清零，清零后不可恢复，提前 30 天会站内信提醒。

            使用积分抵扣的订单发生退货时，优先扣回已使用的积分。"""));

    private record Doc(String id, String title, String text) {}

    private final List<Chunk> chunks = new ArrayList<>();
    private final List<float[]> vectors = new ArrayList<>();

    @PostConstruct
    void init() {
        for (Doc doc : DOCS) {
            int seq = 0;
            for (String para : doc.text().split("\\n\\s*\\n")) {
                String text = para.replaceAll("\\s+", " ").strip();
                if (text.length() < 8) {
                    continue;
                }
                for (String piece : split(text)) {
                    chunks.add(new Chunk(doc.id(), doc.title(), seq++, piece));
                    vectors.add(embed(doc.title() + " " + piece));
                }
            }
        }
    }

    private List<String> split(String text) {
        List<String> out = new ArrayList<>();
        if (text.length() <= MAX_CHARS) {
            out.add(text);
            return out;
        }
        StringBuilder sb = new StringBuilder();
        for (String sentence : text.split("(?<=。)")) {
            if (sb.length() + sentence.length() > MAX_CHARS && sb.length() > 0) {
                out.add(sb.toString());
                sb.setLength(0);
            }
            sb.append(sentence);
        }
        if (sb.length() > 0) {
            out.add(sb.toString());
        }
        return out;
    }

    List<Hit> search(String query, int topK) {
        float[] q = embed(query);
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            hits.add(new Hit(chunks.get(i), dot(q, vectors.get(i))));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.subList(0, Math.min(topK, hits.size()));
    }

    int size() {
        return chunks.size();
    }

    private static float[] embed(String text) {
        float[] v = new float[DIM];
        String norm = text.replaceAll("[\\s，。、；：？！（）\"'《》,.;:?!()]", "");
        for (int i = 0; i < norm.length(); i++) {
            v[Math.floorMod(String.valueOf(norm.charAt(i)).hashCode(), DIM)] += 1.0f;
            if (i + 1 < norm.length()) {
                v[Math.floorMod(norm.substring(i, i + 2).hashCode(), DIM)] += 2.0f;
            }
        }
        double sq = 0;
        for (float x : v) {
            sq += x * x;
        }
        if (sq > 0) {
            float scale = (float) (1.0 / Math.sqrt(sq));
            for (int i = 0; i < DIM; i++) {
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
}