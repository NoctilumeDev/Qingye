package cn.qingye.business;

import java.time.*;
import java.time.temporal.TemporalAdjusters;

/** Server-owned interpretation of a finite time option, not of natural language. */
record AssistantTime(LocalDateTime start,LocalDateTime end,String label) {
    static AssistantTime resolve(String option,Clock clock) {
        var now=LocalDateTime.now(clock);
        var today=now.toLocalDate();
        return switch(option) {
            case "ANY" -> new AssistantTime(null,null,"不限时间");
            case "CURRENT" -> new AssistantTime(null,null,"当前");
            case "TODAY" -> new AssistantTime(today.atStartOfDay(),today.plusDays(1).atStartOfDay(),"今天");
            case "TOMORROW" -> new AssistantTime(today.plusDays(1).atStartOfDay(),today.plusDays(2).atStartOfDay(),"明天");
            case "THIS_WEEK" -> new AssistantTime(now,today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay(),"本周剩余时间");
            case "WEEKEND" -> {
                var saturday=today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(5);
                var start=saturday.atStartOfDay();
                yield new AssistantTime(start.isBefore(now)?now:start,saturday.plusDays(2).atStartOfDay(),"本周末");
            }
            default -> throw new IllegalArgumentException("Unsupported time option");
        };
    }
}
