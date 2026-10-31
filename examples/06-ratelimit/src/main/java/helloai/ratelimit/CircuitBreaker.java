package helloai.ratelimit;

import org.springframework.stereotype.Component;

/**
 * 熔断器：闭 → 开 → 半开。
 *
 * <p>和降级的分工：降级负责"换谁"，熔断负责"别再打它"。
 * 主路挂了之后，每秒两百个请求还是会打在它身上，每个都等 900ms 超时 —— 熔断就是让这件事停下来。
 */
@Component
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long openMillis;
    private final Timeline timeline;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private long openedAtMillis;
    private boolean probeInFlight;

    public CircuitBreaker(LimiterProperties props, Timeline timeline) {
        this.failureThreshold = props.failureThreshold();
        this.openMillis = props.openMillis();
        this.timeline = timeline;
    }

    /** 放不放行。注意这个方法必须够快 —— 熔断的意义就是省掉那 900ms。 */
    public synchronized boolean tryAcquire() {
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - openedAtMillis < openMillis) {
                return false;   // 还在冷却期，直接拒绝，不打上游
            }
            state = State.HALF_OPEN;
            probeInFlight = true;
            timeline.add("OPEN -> HALF_OPEN：冷却结束，放一个探测请求");
            return true;
        }
        if (state == State.HALF_OPEN) {
            // 半开状态只放一个探测，其他的继续拒绝 —— 否则刚恢复就被打垮
            if (probeInFlight) {
                return false;
            }
            probeInFlight = true;
            return true;
        }
        return true;
    }

    public synchronized void onSuccess() {
        if (state == State.HALF_OPEN) {
            state = State.CLOSED;
            consecutiveFailures = 0;
            probeInFlight = false;
            timeline.add("HALF_OPEN -> CLOSED：探测成功，恢复正常");
            return;
        }
        consecutiveFailures = 0;
    }

    public synchronized void onFailure() {
        consecutiveFailures++;

        if (state == State.HALF_OPEN) {
            state = State.OPEN;
            openedAtMillis = System.currentTimeMillis();
            probeInFlight = false;
            timeline.add("HALF_OPEN -> OPEN：探测失败，再等一轮");
            return;
        }

        if (consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openedAtMillis = System.currentTimeMillis();
            timeline.add("CLOSED -> OPEN：连续失败 " + consecutiveFailures + " 次，熔断 "
                + openMillis + "ms");
        }
    }

    public synchronized State state() {
        return state;
    }

    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    public synchronized void reset() {
        state = State.CLOSED;
        consecutiveFailures = 0;
        probeInFlight = false;
        openedAtMillis = 0;
    }

    public long openMillis() {
        return openMillis;
    }

    public int failureThreshold() {
        return failureThreshold;
    }
}