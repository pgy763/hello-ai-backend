package helloai.multiprovider;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MultiProviderApp {

    public static void main(String[] args) {
        SpringApplication.run(MultiProviderApp.class, args);
    }

    /** 全局一个就够：HttpClient 是线程安全的，每次请求 new 一个是常见的性能自残 */
    @Bean
    HttpClient httpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }
}