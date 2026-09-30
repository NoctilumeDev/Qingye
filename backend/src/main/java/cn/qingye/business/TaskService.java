package cn.qingye.business;
import cn.qingye.db.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import static cn.qingye.db.Rows.*;

@Service
public class TaskService {
    private final MessageStore messages;
    private final Clock clock;
    public TaskService(MessageStore messages,Clock clock) {
        this.messages=messages;
        this.clock=clock;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void complete(long id) {
        var task=messages.lock(id);
        var now=LocalDateTime.now(clock);
        if (task==null || !"PENDING".equals(text(task,"status")) || time(task,"availableAt").isAfter(now)) return;
        boolean applicable=task.get("sourceId")==null || messages.applicable(text(task,"sourceKind"),id(task,"sourceId"));
        messages.finish(id,id(task,"notificationId"),now,applicable);
    }
}
