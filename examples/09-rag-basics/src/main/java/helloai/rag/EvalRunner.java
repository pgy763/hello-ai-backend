package helloai.rag;

import helloai.rag.Chunker.Chunk;
import helloai.rag.RagCorpus.Query;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 评测：把「切分策略好不好」变成一个能比的数字，而不是靠感觉。 */
final class EvalRunner {

    private EvalRunner() {}

    record Row(String query, String goldDoc, boolean goldIntact, boolean hitAt1, boolean hitAt3,
               int rank, String topChunkDoc) {}

    record Report(String strategy, int size, int overlap, int chunks, double avgChars,
                  int goldIntact, double recallAt1, double recallAt3, double mrr,
                  int avgPromptCharsAt3, List<Row> rows) {}

    static Report run(RagIndex index, String strategy, int size, int overlap) {
        List<Row> rows = new ArrayList<>();
        double hit1 = 0;
        double hit3 = 0;
        double mrr = 0;
        int promptChars = 0;
        int intact = 0;

        for (Query q : RagCorpus.QUERIES) {
            List<RagIndex.Hit> hits = index.search(q.text(), 3, null);

            int rank = 0;
            int chars = 0;
            for (int i = 0; i < hits.size(); i++) {
                Chunk c = hits.get(i).chunk();
                chars += c.text().length();
                if (rank == 0 && c.docId().equals(q.goldDoc()) && c.text().contains(q.goldSnippet())) {
                    rank = i + 1;
                }
            }
            promptChars += chars;

            // 与 embedding 无关的硬指标：这篇文档里到底有没有一个块，完整包含答案那句话
            boolean goldIntact = index.chunks().stream()
                .anyMatch(c -> c.docId().equals(q.goldDoc()) && c.text().contains(q.goldSnippet()));
            intact += goldIntact ? 1 : 0;

            boolean h1 = rank == 1;
            boolean h3 = rank >= 1 && rank <= 3;
            hit1 += h1 ? 1 : 0;
            hit3 += h3 ? 1 : 0;
            mrr += rank > 0 ? 1.0 / rank : 0;
            rows.add(new Row(q.text(), q.goldDoc(), goldIntact, h1, h3, rank,
                hits.isEmpty() ? "-" : hits.get(0).chunk().docId()));
        }

        int n = RagCorpus.QUERIES.size();
        return new Report(strategy, size, overlap, index.size(), round(index.avgChunkChars()),
            intact, round(hit1 / n), round(hit3 / n), round(mrr / n), promptChars / n, rows);
    }

    /** 三种策略一次跑完，用一张表对比。 */
    static Map<String, Object> compare(Embedder embedder, int paragraphSize, int smallSize, int largeSize) {
        Map<String, Object> out = new LinkedHashMap<>();

        out.put("paragraph(max=" + paragraphSize + ")", evaluate(embedder, "paragraph", paragraphSize, 0));
        out.put("fixed(size=" + smallSize + ",overlap=15)", evaluate(embedder, "fixed", smallSize, 15));
        out.put("fixed(size=" + largeSize + ",overlap=100)", evaluate(embedder, "fixed", largeSize, 100));
        out.put("whole", evaluate(embedder, "whole", 0, 0));
        return out;
    }

    static Report evaluate(Embedder embedder, String strategy, int size, int overlap) {
        RagIndex index = new RagIndex(embedder);
        List<Chunk> chunks = new ArrayList<>();
        for (RagCorpus.Doc doc : RagCorpus.DOCS) {
            chunks.addAll(Chunker.chunk(doc, strategy, size, overlap));
        }
        index.build(chunks);
        return run(index, strategy, size, overlap);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}