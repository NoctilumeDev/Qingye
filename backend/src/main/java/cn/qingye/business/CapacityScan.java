package cn.qingye.business;
import cn.qingye.model.Reservation;
import java.time.LocalDateTime;
import java.util.List;
import java.util.TreeMap;
/** Half-open intervals: adjacent reservations share no capacity. O(n log n). */
public final class CapacityScan {
    private CapacityScan() {
    }
    public static int peak(LocalDateTime start, LocalDateTime end, List<Reservation> reservations) {
        if (!start.isBefore(end)) throw Problem.bad("结束时间必须晚于开始时间");
        var deltas = new TreeMap<LocalDateTime, Integer>();
        for (var r : reservations) {
            var left = r.start().isAfter(start) ? r.start() : start;
            var right = r.end().isBefore(end) ? r.end() : end;
            if (!left.isBefore(right)) continue;
            deltas.merge(left, r.quantity(), Math::addExact);
            deltas.merge(right, -r.quantity(), Math::addExact);
        }
        int occupied = 0, peak = 0;
        for (int delta : deltas.values()) {
            occupied = Math.addExact(occupied, delta);
            peak = Math.max(peak, occupied);
        }
        return peak;
    }
}
