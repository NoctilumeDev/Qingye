package cn.qingye.integration;
import cn.qingye.business.TaskService;
import cn.qingye.db.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.time.*;
import static cn.qingye.db.Rows.*;

@Component
@ConditionalOnProperty(name="qingye.worker-enabled",havingValue="true",matchIfMissing=true)
public class TaskWorker {
    private final MessageStore messages;
    private final RabbitBridge rabbit;
    private final TaskService tasks;
    private final Clock clock;
    public TaskWorker(MessageStore messages,RabbitBridge rabbit,TaskService tasks,Clock clock) {
        this.messages=messages;
        this.rabbit=rabbit;
        this.tasks=tasks;
        this.clock=clock;
    }
    @Scheduled(fixedDelay=60000,initialDelay=30000)
    public void purgeExpiredMessages() {
        tasks.purgeExpiredMessages();
    }
    @Scheduled(fixedDelay=2000,initialDelay=3000)
    public void tick() {
        try {
            for (var row:messages.pending(LocalDateTime.now(clock))) {
                long id=id(row,"id");
                try {
                    if (!rabbit.publish(id)) tasks.complete(id);
                }
                catch(Exception e) {
                    messages.failed(id,"处理暂不可用，将自动重试",LocalDateTime.now(clock).plusSeconds(10));
                }
            }
        }
        catch(Exception ignored) {
            /* DB keeps tasks; the next scheduled pass retries. */
        }
    }
}
