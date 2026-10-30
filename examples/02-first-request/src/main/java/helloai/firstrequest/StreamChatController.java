package helloai.firstrequest;

import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
public class StreamChatController {

    private static final Logger log = LoggerFactory.getLogger(StreamChatController.class);

    /** 流式连接的最长存活时间，必须显式设置 —— 不传就落到容器默认值（Tomcat 是 30 秒） */
    private static final long SSE_TIMEOUT_MS = 180_000L;

    private final AiStreamClient aiStreamClient;

    public StreamChatController(AiStreamClient aiStreamClient) {
        this.aiStreamClient = aiStreamClient;
    }

    /** 冒烟用：不碰模型，确认服务活着 */
    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("ok", "true");
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("q") String q) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // 连接异常终止时记录下来，否则排查时两眼一抹黑
        emitter.onTimeout(() -> log.warn("SSE 超时: q={}", q));
        emitter.onError(e -> log.warn("SSE 出错: q={}", q, e));

        aiStreamClient.stream(
            q,
            new StreamHandler() {
                @Override
                public void onNext(String token) {
                    try {
                        emitter.send(SseEmitter.event().data(token));
                    } catch (IOException | IllegalStateException e) {
                        // 客户端断了（关页面、切网络），或者 emitter 已经被容器标记完成。
                        // 先把连接安静收掉再抛：complete() 自己也可能抛异常，
                        // 不包住的话它会顶掉下面这个 StreamAbortedException，
                        // 表现就是「断了，但日志里什么都没有」
                        abortQuietly(emitter);
                        throw new StreamAbortedException(e);
                    }
                }

                @Override
                public void onComplete() {
                    abortQuietly(emitter);
                }

                @Override
                public void onError(Throwable t) {
                    // 这条是给「上游真的出错了」用的，要能看见
                    log.warn("流式调用失败: {}", t.toString());
                    // 必须通知前端。不调用的话，前端会一直等一个永远不会来的结束标志
                    try {
                        emitter.completeWithError(t);
                    } catch (Exception ignore) {
                        // 连接已经没了
                    }
                }
            }
        );

        return emitter;
    }

    /**
     * 收尾时调用。已经关掉的连接上再调 complete() 会抛 IllegalStateException，
     * 这属于预期内的情况 —— 吞掉，别让它变成一个假的「故障」。
     */
    private static void abortQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignore) {
            // 连接已经没了，没什么可做的
        }
    }
}