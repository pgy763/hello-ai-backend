package helloai.obs;

/** 厂商把我们限流了。带上 Retry-After，调用方照着这个时间重试。 */
class RateLimited extends RuntimeException {

    private final int retryAfterSeconds;

    RateLimited(int retryAfterSeconds) {
        super("429 限流");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}