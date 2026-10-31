package helloai.capacity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 容量估算。核心只有一条公式（利特尔法则）：
 *
 * <pre>并发数 = QPS × 单请求耗时</pre>
 *
 * <p>普通 Web 接口耗时 50ms，100 QPS 只要 5 个并发；LLM 接口耗时 2500ms，
 * 同样 100 QPS 需要 250 个并发 —— 这就是为什么「压测方法失效」。
 */
final class CapacityModel {

    private CapacityModel() {}

    record Input(int dau, double callsPerUserPerDay, double peakFactor,
                 double avgLatencyMs, double ttftMs,
                 int promptTokens, int completionTokens,
                 double inPricePerM, double outPricePerM,
                 double threadMargin) {}

    static Map<String, Object> compute(Input in) {
        // 1. 日均调用量与峰值 QPS
        double callsPerDay = (double) in.dau() * in.callsPerUserPerDay();
        double avgQps = callsPerDay / 86_400.0;
        double peakQps = avgQps * in.peakFactor();

        // 2. 峰值需要的并发数（利特尔法则）
        double peakConcurrency = peakQps * (in.avgLatencyMs() / 1000.0);
        int tomcatThreads = (int) Math.ceil(peakConcurrency * in.threadMargin());

        // 3. 上游配额：按峰值 QPS 折算 RPM，再留 30% 余量
        double peakRpm = peakQps * 60 * 1.3;

        // 4. token 与成本
        double tokensPerDay = callsPerDay * (in.promptTokens() + in.completionTokens());
        double dailyCost = callsPerDay
            * (in.promptTokens() * in.inPricePerM() + in.completionTokens() * in.outPricePerM()) / 1_000_000.0;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("日均调用次数", Math.round(callsPerDay));
        m.put("平均 QPS", round(avgQps, 2));
        m.put("峰值 QPS（×" + in.peakFactor() + "）", round(peakQps, 2));
        m.put("单请求耗时(ms)", in.avgLatencyMs());
        m.put("峰值并发数 = QPS × 耗时", round(peakConcurrency, 1));
        m.put("Tomcat 线程数（留 " + Math.round((in.threadMargin() - 1) * 100) + "% 余量）", tomcatThreads);
        m.put("需要的上游 RPM（含 30% 余量）", Math.round(peakRpm));
        m.put("日 token 量", Math.round(tokensPerDay));
        m.put("日成本(元)", round(dailyCost, 2));
        m.put("月成本(元)", round(dailyCost * 30, 2));
        m.put("每用户每月成本(元)", round(dailyCost * 30 / in.dau(), 4));
        m.put("TTFT 占耗时比例", round(in.ttftMs() / in.avgLatencyMs() * 100, 1) + "%");
        return m;
    }

    /**
     * 反推：给定上游并发上限，能扛多少 QPS。
     * 这个方向更常用 —— 套餐是定死的，你要算的是「够不够」。
     */
    static Map<String, Object> fromUpstream(int upstreamConcurrency, double avgLatencyMs,
                                            double targetQps, int promptTokens, int completionTokens,
                                            double inPrice, double outPrice) {
        double maxQps = upstreamConcurrency / (avgLatencyMs / 1000.0);
        boolean enough = maxQps >= targetQps;
        double dailyCalls = targetQps * 86_400 * 0.35;   // 峰值只出现在约 35% 的时间里，粗估日用

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("上游并发上限", upstreamConcurrency);
        m.put("单请求耗时(ms)", avgLatencyMs);
        m.put("该配置的理论上限 QPS", round(maxQps, 2));
        m.put("目标 QPS", targetQps);
        m.put("结论", enough ? "够，余量 " + round((maxQps / targetQps - 1) * 100, 0) + "%"
            : "不够，缺口 " + round(targetQps - maxQps, 2) + " QPS");
        m.put("对应 RPM", Math.round(maxQps * 60));
        m.put("估算日调用次数", Math.round(dailyCalls));
        m.put("估算日成本(元)", round(dailyCalls * (promptTokens * inPrice + completionTokens * outPrice) / 1_000_000.0, 2));
        return m;
    }

    private static double round(double v, int scale) {
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }
}