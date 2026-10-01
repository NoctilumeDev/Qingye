package cn.qingye.business;
import cn.qingye.db.*;
import cn.qingye.model.Actor;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static cn.qingye.db.Rows.*;

@Service
public class Recommendation {
    private final ActivityStore activities;
    private final ClubStore clubs;
    private final Clock clock;
    public Recommendation(ActivityStore activities,ClubStore clubs,Clock clock) {
        this.activities=activities;
        this.clubs=clubs;
        this.clock=clock;
    }
    public List<Map<String,Object>> forUser(Actor actor) {
        var now=LocalDateTime.now(clock);
        var interests=activities.interests(actor.id()).stream().map(r->text(r,"category")).toList();
        var memberships=clubs.list(actor.id()).stream().filter(r->"ACTIVE".equals(r.get("myStatus"))).map(r->id(r,"id")).toList();
        var rows=activities.list(actor,"public",null,null,0,now,null).stream().filter(r->time(r,"signupDeadline").isAfter(now)).toList();
        for(var row:rows) {
            int score=0;
            var reasons=new ArrayList<String>();
            if(interests.contains(text(row,"category"))) {
                score+=15;
                reasons.add("你报名过的活动分类");
            }
            if(memberships.contains(id(row,"clubId"))) {
                score+=10;
                reasons.add("你加入的社团");
            }
            if(time(row,"startTime").isBefore(now.plusDays(7))) {
                score+=5;
                reasons.add("近期就能参加");
            }
            if(integer(row,"registeredCount")<integer(row,"capacity")) score+=2;
            row.put("score",score);
            row.put("recommendationReason",reasons.isEmpty()?"发现新的校园体验":String.join(" · ",reasons));
        }
        return rows.stream().sorted(Comparator.<Map<String,Object>>comparingInt(r->integer(r,"score")).reversed().thenComparing(r->time(r,"startTime")).thenComparingLong(r->id(r,"id"))).limit(6).toList();
    }
}
