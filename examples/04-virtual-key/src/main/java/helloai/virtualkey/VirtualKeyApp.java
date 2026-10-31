package helloai.virtualkey;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VirtualKeyApp {

    public static void main(String[] args) {
        SpringApplication.run(VirtualKeyApp.class, args);
    }
}