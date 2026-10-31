package helloai.fallback;

/** 一跳的记录：谁、结果如何、花了多久、为什么。降级链的可观测性全靠它。 */
public record Attempt(String target, Outcome outcome, String answer, long ms, String detail) {

    static Attempt ok(String target, String answer, long ms) {
        return new Attempt(target, Outcome.OK, answer, ms, null);
    }

    static Attempt failed(String target, Outcome outcome, long ms, String detail) {
        return new Attempt(target, outcome, null, ms, detail);
    }
}