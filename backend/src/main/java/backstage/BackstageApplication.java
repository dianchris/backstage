package backstage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class BackstageApplication {
    public static void main(String[] args) {
        SpringApplication.run(BackstageApplication.class, args);
    }
}
