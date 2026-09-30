package cn.qingye.model;
import java.time.LocalDateTime;
public record QueryPlan(String intent,String category,String keyword,LocalDateTime start,LocalDateTime end) {
}
