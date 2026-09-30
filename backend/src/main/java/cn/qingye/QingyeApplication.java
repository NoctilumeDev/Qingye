package cn.qingye;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Clock;
import java.time.ZoneId;

@SpringBootApplication
@EnableScheduling
public class QingyeApplication {
    public static void main(String[] args) {
        SpringApplication.run(QingyeApplication.class, args);
    }
    @Bean Clock clock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }
}
