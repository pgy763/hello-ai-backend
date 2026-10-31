package helloai.state;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StateMachineApp {

    public static void main(String[] args) {
        SpringApplication.run(StateMachineApp.class, args);
    }

    /** 一步能拿到的东西：任务输入 + 前面所有步骤的产物。 */
    public record TaskContext(String taskId, String input, Map<String, String> previous) {

        String of(String stepName) {
            return previous.getOrDefault(stepName, "");
        }
    }
}