package cn.qingye;
import cn.qingye.business.CapacityScan;
import cn.qingye.model.Reservation;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class CapacityScanTest {
    private final LocalDateTime origin=LocalDateTime.of(2026,10,1,14,0);
    @Test void adjacentReservationsAreNotSummedAsIfSimultaneous() {
        var existing=List.of(new Reservation(origin,origin.plusHours(1),3),new Reservation(origin.plusHours(1),origin.plusHours(2),3));
        assertThat(CapacityScan.peak(origin,origin.plusHours(2),existing)).isEqualTo(3);
        assertThat(CapacityScan.peak(origin,origin.plusHours(2),existing)+2).isEqualTo(5);
    }
    @Test void clipsIntervalsAndExcludesTouchingBoundaries() {
        var existing=List.of(new Reservation(origin.minusHours(1),origin,9),new Reservation(origin.minusHours(1),origin.plusMinutes(30),2),new Reservation(origin.plusMinutes(15),origin.plusHours(2),3),new Reservation(origin.plusHours(1),origin.plusHours(2),8));
        assertThat(CapacityScan.peak(origin,origin.plusHours(1),existing)).isEqualTo(5);
    }
    @Test void randomizedScanMatchesIndependentMinuteSampling() {
        var random=new Random(20261001);
        for(int trial=0;trial<500;trial++) {
            var reservations=new ArrayList<Reservation>();
            for(int i=0;i<30;i++) {
                int start=random.nextInt(50)-10;
                reservations.add(new Reservation(origin.plusMinutes(start),origin.plusMinutes(start+1+random.nextInt(20)),1+random.nextInt(5)));
            }
            int expected=0;
            for(int minute=0;minute<30;minute++) {
                var point=origin.plusMinutes(minute).plusSeconds(30);
                int occupied=0;
                for(var r:reservations) if(!point.isBefore(r.start()) && point.isBefore(r.end())) occupied+=r.quantity();
                expected=Math.max(expected,occupied);
            }
            assertThat(CapacityScan.peak(origin,origin.plusMinutes(30),reservations)).isEqualTo(expected);
        }
    }
}
