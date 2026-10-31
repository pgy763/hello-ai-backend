package helloai.selection;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 假上游。三种写法都打它，所以差异只来自「写法」，不来自「模型」。
 *
 * <p>它可以被切成故障状态，用来测一件真正重要的事：**上游挂了，你那套写法能不能活下来。**
 */
@Component
class FakeUpstream {

    record Raw(String content, int promptTokens, int completionTokens) {}

    private volatile boolean down = false;

    Raw call(String prompt) {
        if (down) {
            throw new IllegalStateException("上游 503：模型服务暂时不可用");
        }
        sleep(60);
        String content = "（模拟回复）关于「" + brief(prompt) + "」，建议先查看退款政策第 2 段。";
        return new Raw(content, Math.max(1, prompt.length() / 2), 24);
    }

    void setDown(boolean down) {
        this.down = down;
    }

    boolean isDown() {
        return down;
    }

    /** 三种写法的对照表：一次跑完，正常一轮 + 故障一轮。 */
    Map<String, Object> compare() {
        Map<String, Object> out = new LinkedHashMap<>();
        down = false;
        out.put("正常", runAll());
        down = true;
        out.put("上游故障", runAll());
        down = false;
        return out;
    }

    private Map<String, Object> runAll() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("raw", probe(new RawChat(this)));
        row.put("layered", probe(new LayeredChat(this)));
        row.put("gateway", probe(new GatewayChat(this)));
        return row;
    }

    private Map<String, Object> probe(Chat chat) {
        Map<String, Object> m = new LinkedHashMap<>();
        long t0 = System.nanoTime();
        try {
            Reply r = chat.ask("退款多久到账");
            m.put("存活", true);
            m.put("耗时ms", (System.nanoTime() - t0) / 1_000_000L);
            m.put("文本", r.text());
            m.put("拿得到token", r.promptTokens() != null);
            m.put("能看出用了哪个模型", r.provider() != null);
        } catch (RuntimeException e) {
            m.put("存活", false);
            m.put("耗时ms", (System.nanoTime() - t0) / 1_000_000L);
            m.put("错误", e.getMessage());
        }
        return m;
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String brief(String s) {
        return s.length() > 20 ? s.substring(0, 20) + "…" : s;
    }
}