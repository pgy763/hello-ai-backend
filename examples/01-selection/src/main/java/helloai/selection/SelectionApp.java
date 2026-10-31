package helloai.selection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第 1 章的示例：同一个需求，三种写法。
 *
 * <p>三个接口对应三种看法：
 * <ul>
 *   <li>{@code /api/compare} —— 跑一遍，看「上游挂了谁能活」</li>
 *   <li>{@code /api/loc} —— 数行数，看代价到底是多少</li>
 *   <li>{@code /api/ask?impl=} —— 单独调某一种，对比返回结构</li>
 * </ul>
 */
@SpringBootApplication
@RestController
public class SelectionApp {

    private final FakeUpstream upstream = new FakeUpstream();
    private final Map<String, Chat> impls = Map.of(
        "raw", new RawChat(upstream),
        "layered", new LayeredChat(upstream),
        "gateway", new GatewayChat(upstream)
    );

    public static void main(String[] args) {
        SpringApplication.run(SelectionApp.class, args);
    }

    /** 三种写法各跑一遍：先正常，再把上游切成故障，看谁还能出结果。 */
    @GetMapping("/api/compare")
    public Map<String, Object> compare() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("说明", "同一份假上游、同一个问题，只换写法");
        out.putAll(upstream.compare());
        return out;
    }

    /** 每种写法的真实行数。直接从源码文件数 —— 不是估的。 */
    @GetMapping("/api/loc")
    public Map<String, Object> loc() throws IOException {
        Path dir = Path.of("src", "main", "java", "helloai", "selection");
        Map<String, Object> out = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) {
            out.put("提示", "找不到源码目录，请从模块根目录启动（mvn spring-boot:run）");
            return out;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (String name : List.of("RawChat.java", "LayeredChat.java", "GatewayChat.java",
                                   "Chat.java", "FakeUpstream.java")) {
            Path file = dir.resolve(name);
            int n = Files.exists(file) ? countCodeLines(file) : 0;
            counts.put(name, n);
            total += n;
        }
        out.put("每个文件的有效行数", counts);
        out.put("合计", total);
        out.put("说明", "有效行 = 去掉空行、注释行、import 之后的行数");
        return out;
    }

    /** 单独调一种写法，看它返回的结构差在哪。 */
    @GetMapping("/api/ask")
    public Object ask(@RequestParam String q,
                      @RequestParam(defaultValue = "layered") String impl) {
        Chat chat = impls.get(impl);
        if (chat == null) {
            return Map.of("错误", "impl 只能是 raw / layered / gateway");
        }
        long t0 = System.nanoTime();
        try {
            Reply r = chat.ask(q);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("impl", impl);
            out.put("耗时ms", (System.nanoTime() - t0) / 1_000_000L);
            out.put("文本", r.text());
            out.put("promptTokens", r.promptTokens());
            out.put("completionTokens", r.completionTokens());
            out.put("provider", r.provider());
            return out;
        } catch (RuntimeException e) {
            return Map.of("impl", impl, "存活", false, "错误", e.getMessage());
        }
    }

    private static int countCodeLines(Path file) throws IOException {
        int n = 0;
        for (String line : Files.readAllLines(file)) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")
                || t.startsWith("import ") || t.startsWith("package ")) {
                continue;
            }
            n++;
        }
        return n;
    }
}