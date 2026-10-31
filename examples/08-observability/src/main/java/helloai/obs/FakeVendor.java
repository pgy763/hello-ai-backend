package helloai.obs;

import org.springframework.stereotype.Component;

/**
 * 假厂商。它比真厂商多给了一样东西：真实的 token 数 —— 这样才能验证「估算差多少」。
 */
@Component
class FakeVendor {

    /** 一次回复的正文，长度固定，方便算估算误差。 */
    static final String REPLY_TEXT = """
        您好，您的订单目前停留在分拣中心，预计明天下午发出。物流信息显示包裹已打包完成，
        正在等待干线运输车辆。因为近期是促销高峰期，分拣中心处理量较大，可能会出现一天的延迟。
        如果明天下午仍未更新，可以联系人工客服核实，我们会为您优先处理。
        另外提醒您，包裹发出后物流信息通常在 2 小时内更新，请留意短信通知。
        您也可以在订单详情页开启物流提醒，包裹每经过一个站点都会给您推送一次，
        这样就不用反复来问了。如果三天内还没有更新，可以直接回复本消息，
        我们会升级为异常件处理，由专员跟进并给您一个明确的处理时间。
        给您带来不便非常抱歉，感谢您的耐心等待和理解。""";

    static final int TRUE_CACHED_TOKENS_DEFAULT = 0;

    /** 真实 tokenizer 的「字符 / token」比，示例里取 1.75；估算按 2 算，于是系统性偏低。 */
    static final double TRUE_CHARS_PER_TOKEN = 1.75;

    record Usage(int promptTokens, int cachedTokens, int completionTokens) {}

    record Reply(String text, Usage usage, long ttftMs, long totalMs) {}

    /**
     * 模拟一次调用。
     *
     * @param includeUsage false 时流式调用不返回 usage —— 这就是真实情况
     * @param attempt      第几次尝试，{@code failFirst} 为 true 时第一次会抛 429
     */
    Reply reply(String prompt, boolean stream, boolean includeUsage, int cachedTokens,
                int attempt, boolean failFirst) {
        if (failFirst && attempt == 1) {
            throw new RateLimited(1);          // 第一次就被限流，调用方重试
        }

        long t0 = System.nanoTime();
        sleep(250);                                  // 首字之前：等模型开始吐
        long ttft = (System.nanoTime() - t0) / 1_000_000L;
        sleep(550);                                  // 吐完剩下的

        Usage usage = (stream && !includeUsage)
            ? null
            : new Usage(trueTokens(prompt), cachedTokens, trueTokens(REPLY_TEXT));

        return new Reply(REPLY_TEXT, usage, ttft, (System.nanoTime() - t0) / 1_000_000L);
    }

    /** 假厂商「内部知道」的真值。真厂商不给你这个数，所以你只能估。 */
    static int trueTokens(String text) {
        return Math.max(1, (int) Math.ceil(text.length() / TRUE_CHARS_PER_TOKEN));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 估算：中文大约 2 字符 / token，英文大约 4 字符 / token。这里统一按 2 算。 */
    static int estimateTokens(String text) {
        return Math.max(1, (int) Math.ceil(text.length() / 2.0));
    }
}