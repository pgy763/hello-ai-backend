package helloai.multiprovider;

/**
 * 统一异常。业务层只需要看 {@link ErrorAction}，不需要知道是哪家厂商、什么错误码。
 *
 * <p>{@code raw} 一定要留着 —— 归一化之前先别丢原文，你丢掉的那段正是排查时唯一有信息量的东西。
 */
public class LlmException extends RuntimeException {

    private final String provider;
    private final int httpStatus;
    private final String errorCode;
    private final ErrorAction action;
    private final String rawBody;

    public LlmException(
        String provider,
        int httpStatus,
        String errorCode,
        ErrorAction action,
        String rawBody
    ) {
        super(
            "provider=" + provider + " status=" + httpStatus + " code=" + errorCode + " action=" + action
        );
        this.provider = provider;
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.action = action;
        this.rawBody = rawBody;
    }

    public String provider() {
        return provider;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String errorCode() {
        return errorCode;
    }

    public ErrorAction action() {
        return action;
    }

    public String rawBody() {
        return rawBody;
    }
}