package helloai.virtualkey;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final QuotaService quota;
    private final BucketBootstrap bootstrap;

    public DemoController(QuotaService quota, BucketBootstrap bootstrap) {
        this.quota = quota;
        this.bootstrap = bootstrap;
    }

    @GetMapping("/bucket")
    public QuotaService.Bucket bucket() {
        return quota.bucket(BucketBootstrap.DEMO_KEY_ID);
    }

    @PostMapping("/reserve")
    public long reserve(@RequestParam String requestId, @RequestParam long estimate) {
        return quota.reserve(BucketBootstrap.DEMO_KEY_ID, requestId, estimate);
    }

    @PostMapping("/settle")
    public QuotaService.Bucket settle(@RequestParam long holdId, @RequestParam long actual) {
        quota.settle(holdId, actual);
        return quota.bucket(BucketBootstrap.DEMO_KEY_ID);
    }

    /** 手动触发一次过期回收（不用等定时任务） */
    @PostMapping("/expire")
    public ExpireResult expireAll() {
        var due = quota.findDue(1000);
        int reclaimed = 0;
        for (var h : due) {
            if (quota.expireOne(h)) {
                reclaimed++;
            }
        }
        return new ExpireResult(due.size(), reclaimed, quota.bucket(BucketBootstrap.DEMO_KEY_ID).available());
    }

    /**
     * 演示用：预扣一张「已经过期」的单据，模拟请求超时 / 进程被杀，好观察回收。
     */
    @PostMapping("/reserve-expired")
    public long reserveExpired(@RequestParam String requestId, @RequestParam long estimate) {
        long holdId = quota.reserve(BucketBootstrap.DEMO_KEY_ID, requestId, estimate);
        quota.forceExpireAt(holdId);
        return holdId;
    }

    /**
     * 并发自检：把额度设成「刚好够 N 个请求」，起 M 个线程同时打，看会不会超卖。
     *
     * <p>这是 4.5 末尾那个「怎么证明写对了」的可执行版本。
     */
    @GetMapping("/selftest")
    public SelfTestResult selftest(
        @RequestParam(defaultValue = "50") int parallel,
        @RequestParam(defaultValue = "100") long estimate
    ) throws InterruptedException {
        long total = quota.bucket(BucketBootstrap.DEMO_KEY_ID).total();
        bootstrap.reset(total);

        String runId = Long.toString(System.nanoTime(), 36);
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(parallel);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        for (int i = 0; i < parallel; i++) {
            final int n = i;
            pool.submit(() -> {
                try {
                    start.await();
                    quota.reserve(BucketBootstrap.DEMO_KEY_ID, "selftest-" + runId + "-" + n, estimate);
                    ok.incrementAndGet();
                } catch (QuotaExceededException e) {
                    rejected.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                    log.warn("自检线程异常", e);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        QuotaService.Bucket b = quota.bucket(BucketBootstrap.DEMO_KEY_ID);
        long capacity = b.total() / estimate;
        boolean noOversell = b.available() >= 0 && ok.get() <= capacity;

        return new SelfTestResult(
            parallel,
            estimate,
            capacity,
            ok.get(),
            rejected.get(),
            failed.get(),
            b.reserved(),
            b.available(),
            noOversell
        );
    }

    public record ExpireResult(int due, int reclaimed, long availableAfter) {}

    public record SelfTestResult(
        int parallel,
        long estimatePerRequest,
        long capacity,
        int succeeded,
        int rejected,
        int failed,
        long reserved,
        long available,
        boolean noOversell
    ) {}
}