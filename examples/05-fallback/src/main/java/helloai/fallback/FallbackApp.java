package helloai.fallback;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FallbackApp {

    public static void main(String[] args) {
        SpringApplication.run(FallbackApp.class, args);
    }
}