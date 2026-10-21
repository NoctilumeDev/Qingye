package cn.qingye.business;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import cn.qingye.model.QueryPlan;

/** Server-owned interpretation of a finite time option, not of natural language. */
record AssistantTime(LocalDateTime start,LocalDateTime end,LocalDateTime anchor,String label) {
    static AssistantTime resolve(QueryPlan plan,Clock clock) {
        String option=plan.timeOption();
        var now=LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
        var today=now.toLocalDate();
        return switch(option) {
            case "ANY" -> new AssistantTime(null,null,now,"全部时间");
            case "PAST" -> new AssistantTime(null,null,now,"过去（结束时间不晚于 "+now+"）");
            case "CURRENT" -> new AssistantTime(null,null,now,plan.intent().equals("EQUIPMENT")?"当前库存":"进行中（开始 ≤ "+now+" < 结束）");
            case "FUTURE" -> new AssistantTime(null,null,now,"未来（开始时间晚于 "+now+"；仅已有排期）");
            case "TODAY" -> day(today,now,"今天");
            case "TOMORROW" -> day(today.plusDays(1),now,"明天");
            case "DAY_AFTER_TOMORROW" -> day(today.plusDays(2),now,"后天");
            case "DATE" -> day(LocalDate.parse(plan.date()),now,"指定日期");
            case "THIS_WEEK" -> new AssistantTime(now,today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay(),now,"本周剩余时间");
            case "NEXT_WEEK" -> { var monday=today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)); yield new AssistantTime(monday.atStartOfDay(),monday.plusDays(7).atStartOfDay(),now,"下周"); }
            case "WEEKEND" -> {
                var saturday=today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(5);
                var start=saturday.atStartOfDay();
                yield new AssistantTime(start.isBefore(now)?now:start,saturday.plusDays(2).atStartOfDay(),now,"本周末剩余时间");
            }
            default -> throw new IllegalArgumentException("Unsupported time option");
        };
    }
    private static AssistantTime day(LocalDate day,LocalDateTime now,String label) {
        return new AssistantTime(day.atStartOfDay(),day.plusDays(1).atStartOfDay(),now,label+"（"+day+"）");
    }
}
