package helloai.capacity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * 压测器。压的是「你自己的接入层 + 一个假上游」，所以数字可以复现、也不花钱。
 *
 * <p>四个指标：吞吐、P95 延迟、失败率、平均在飞请求数。
 */
@Component
class LoadTester {

    record Report(int concurrency, int total, int ok, int failed, int rateLimited,
                  double rps, long p50, long p95, long p99, long max, double avgMs,
                  double avgInFlight, int upstreamInFlightPeak) {}

    Report run(int concurrency, int perWorker) {
        int total = concurrency * perWorker;
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>(total));
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger rateLimited = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        // 注意：不要把压测线程池交给 HttpClient。并发为 1 时，压测线程阻塞在
        // client.send()，而 client 自身要线程干活，于是自锁 —— 用默认执行器。
        HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);

        for (int w = 0; w < concurrency; w++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perWorker; i++) {
                        HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:8080/api/chat"))
                            .timeout(Duration.ofSeconds(30))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build();
                        long t0 = System.nanoTime();
                        try {
                            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                            long ms = (System.nanoTime() - t0) / 1_000_000L;
                            latencies.add(ms);
                            if (resp.statusCode() == 200) {
                                ok.incrementAndGet();
                            } else if (resp.statusCode() == 429) {
                                rateLimited.incrementAndGet();
                            } else {
                                failed.incrementAndGet();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            failed.incrementAndGet();
                        } catch (java.io.IOException | RuntimeException e) {
                            failed.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        long t0 = System.nanoTime();
        start.countDown();
        try {
            done.await(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        double elapsedSec = (System.nanoTime() - t0) / 1_000_000_000.0;
        pool.shutdownNow();

        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);

        double rps = elapsedSec > 0 ? (ok.get() + failed.get() + rateLimited.get()) / elapsedSec : 0;
        double avgMs = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        // 利特尔法则的自检：平均在飞请求数应该 ≈ 吞吐 × 平均延迟
        double avgInFlight = rps * (avgMs / 1000.0);

        return new Report(concurrency, total, ok.get(), failed.get(), rateLimited.get(),
            round(rps), pct(sorted, 50), pct(sorted, 95), pct(sorted, 99),
            sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1), round(avgMs),
            round(avgInFlight), 0);
    }

    private static long pct(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(sorted.size() * p / 100.0) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}