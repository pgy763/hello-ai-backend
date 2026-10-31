package helloai.virtualkey;

/** 额度不够。这是业务分支，不是故障 —— 不要包成 RuntimeException 后当异常告警。 */
public class QuotaExceededException extends RuntimeException {

    private final long keyId;

    public QuotaExceededException(long keyId) {
        super("quota_exceeded keyId=" + keyId);
        this.keyId = keyId;
    }

    public long keyId() {
        return keyId;
    }
}