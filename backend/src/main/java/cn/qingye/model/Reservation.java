package cn.qingye.model;
import java.time.LocalDateTime;
public record Reservation(LocalDateTime start, LocalDateTime end, int quantity) {
    public Reservation {
        if (start == null || end == null || !start.isBefore(end) || quantity <= 0)
        throw new IllegalArgumentException("预约时间或数量无效");
    }
}
