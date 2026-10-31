package helloai.state;

import helloai.state.StateMachineApp.TaskContext;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 假的 LLM。会记两件事：被调了几次、花了多少 token —— 这两件事就是这一章要省的东西。
 */
@Component
class FakeLlm {

    private static final Logger log = LoggerFactory.getLogger(FakeLlm.class);

    private final AtomicInteger calls = new AtomicInteger();
    private final long stepDelayMs;

    FakeLlm(@Value("${demo.step-delay-ms:0}") long stepDelayMs) {
        this.stepDelayMs = stepDelayMs;
    }

    String complete(String prompt) {
        int n = calls.incrementAndGet();
        log.info("LLM 调用 #{}, prompt={}", n, prompt.length() > 40 ? prompt.substring(0, 40) + "…" : prompt);
        if (stepDelayMs > 0) {
            try {
                Thread.sleep(stepDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return "结果(" + n + ")：" + prompt.hashCode();
    }

    int calls() {
        return calls.get();
    }

    void reset() {
        calls.set(0);
    }

    /** 一次调用按固定 120 输入 + 80 输出算，方便算钱。 */
    int tokens(int callCount) {
        return callCount * 200;
    }
}

/** 一个步骤。名字进数据库，run 里只写这一步该干的事。 */
interface Step {

    String name();

    String run(TaskContext ctx);
}