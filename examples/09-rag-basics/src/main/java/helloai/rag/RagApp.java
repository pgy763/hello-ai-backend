package helloai.rag;

import helloai.rag.Chunker.Chunk;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class RagApp {

    public static void main(String[] args) {
        SpringApplication.run(RagApp.class, args);
    }
}

@RestController
class RagController {

    private final Embedder embedder;
    private volatile RagIndex index;
    private volatile String current = "paragraph(size=200)";

    RagController(Embedder embedder) {
        this.embedder = embedder;
        this.index = build("paragraph", 200, 0);
    }

    /** 重建索引。策略和尺寸都从参数来，方便对比。 */
    @PostMapping("/api/index")
    public Map<String, Object> reindex(
        @RequestParam(defaultValue = "paragraph") String strategy,
        @RequestParam(defaultValue = "200") int size,
        @RequestParam(defaultValue = "0") int overlap
    ) {
        index = build(strategy, size, overlap);
        current = strategy + "(size=" + size + ")";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("strategy", current);
        m.put("chunks", index.size());
        m.put("avgChunkChars", Math.round(index.avgChunkChars()));
        m.put("dim", embedder.dim());
        return m;
    }

    @GetMapping("/api/search")
    public Map<String, Object> search(
        @RequestParam String q,
        @RequestParam(defaultValue = "3") int k,
        @RequestParam(required = false) String permission
    ) {
        List<Map<String, Object>> hits = new ArrayList<>();
        for (RagIndex.Hit hit : index.search(q, k, permission)) {
            Chunk c = hit.chunk();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("docId", c.docId());
            m.put("title", c.title());
            m.put("permission", c.permission());
            m.put("score", Math.round(hit.score() * 1000) / 1000.0);
            m.put("text", c.text().length() > 80 ? c.text().substring(0, 80) + "…" : c.text());
            hits.add(m);
        }
        return Map.of("query", q, "index", current, "hits", hits);
    }

    /** 一次跑完 4 种切分策略，输出对比表。 */
    @GetMapping("/api/eval")
    public Map<String, Object> eval() {
        return EvalRunner.compare(embedder, 200, 60, 600);
    }

    @GetMapping("/api/chunks")
    public List<Map<String, Object>> chunks() {
        return index.chunks().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("docId", c.docId());
            m.put("seq", c.seq());
            m.put("chars", c.text().length());
            m.put("text", c.text());
            return m;
        }).toList();
    }

    private RagIndex build(String strategy, int size, int overlap) {
        List<Chunk> chunks = new ArrayList<>();
        for (RagCorpus.Doc doc : RagCorpus.DOCS) {
            chunks.addAll(Chunker.chunk(doc, strategy, size, overlap));
        }
        RagIndex idx = new RagIndex(embedder);
        idx.build(chunks);
        return idx;
    }
}