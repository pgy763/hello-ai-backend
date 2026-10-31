package helloai.state;

import helloai.state.StateMachineApp.TaskContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 四个步骤，按 {@code @Order} 排。步骤边界划在「花钱的调用」外围，
 * 这样续跑时才能只重跑没完成的那几步。
 */
final class Steps {

    private Steps() {}

    @Component
    @Order(1)
    static class Extract implements Step {

        private final FakeLlm llm;

        Extract(FakeLlm llm) {
            this.llm = llm;
        }

        @Override
        public String name() {
            return "extract";
        }

        @Override
        public String run(TaskContext ctx) {
            return llm.complete("抽取要素：" + ctx.input());
        }
    }

    @Component
    @Order(2)
    static class Classify implements Step {

        private final FakeLlm llm;

        Classify(FakeLlm llm) {
            this.llm = llm;
        }

        @Override
        public String name() {
            return "classify";
        }

        @Override
        public String run(TaskContext ctx) {
            return llm.complete("给工单分类：" + ctx.of("extract"));
        }
    }

    @Component
    @Order(3)
    static class Summarize implements Step {

        private final FakeLlm llm;

        Summarize(FakeLlm llm) {
            this.llm = llm;
        }

        @Override
        public String name() {
            return "summarize";
        }

        @Override
        public String run(TaskContext ctx) {
            return llm.complete("写摘要：" + ctx.of("classify"));
        }
    }

    /** 最后一步不花钱，只落库 —— 用它说明「步骤不一定是 LLM 调用」。 */
    @Component
    @Order(4)
    static class Persist implements Step {

        @Override
        public String name() {
            return "persist";
        }

        @Override
        public String run(TaskContext ctx) {
            return "saved:" + ctx.taskId();
        }
    }
}