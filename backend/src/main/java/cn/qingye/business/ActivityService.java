package cn.qingye.business;
import cn.qingye.api.Forms;
import cn.qingye.db.*;
import cn.qingye.model.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import static cn.qingye.db.Rows.*;

@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
public class ActivityService {
    private final ActivityStore activities;
    private final LoanStore loans;
    private final ClubStore clubs;
    private final Access access;
    private final MessageStore messages;
    private final Clock clock;
    public ActivityService(ActivityStore activities,LoanStore loans,ClubStore clubs,Access access,MessageStore messages,Clock clock) {
        this.activities=activities;
        this.loans=loans;
        this.clubs=clubs;
        this.access=access;
        this.messages=messages;
        this.clock=clock;
    }
    public List<Map<String,Object>> list(Actor actor,String scope,String category,String keyword,int page,boolean upcoming) {
        return activities.list(actor,scope,category,keyword,page,upcoming?LocalDateTime.now(clock):null,null);
    }
    public Map<String,Object> detail(Actor actor,long id) {
        var a=activities.view(id);
        if (!"PUBLISHED".equals(text(a,"status")) && !access.manages(actor,id(a,"clubId"))) {
            var r=activities.registration(id,actor.id());
            if (r==null) throw Problem.forbidden();
        }
        a.put("myRegistration",activities.registration(id,actor.id()));
        a.put("canManage",access.manages(actor,id(a,"clubId")));
        return a;
    }
    private void valid(Forms.Activity a) {
        var now=LocalDateTime.now(clock);
        if (!a.startTime().isBefore(a.endTime()) || !a.signupDeadline().isBefore(a.startTime()) || !a.signupDeadline().isAfter(now)) throw Problem.bad("报名截止、活动开始和结束时间顺序不正确");
        if (a.endTime().isAfter(now.plusYears(1))) throw Problem.bad("活动请安排在未来一年内");
    }
    public long create(Actor actor,Forms.Activity input) {
        valid(input);
        var club=clubs.lock(input.clubId());
        access.manager(actor,input.clubId());
        if (!flag(club,"enabled")) throw Problem.conflict("社团已停用");
        return activities.create(input.clubId(),actor.id(),input.title(),input.description(),input.category(),input.location(),input.poster(),input.startTime(),input.endTime(),input.signupDeadline(),input.capacity());
    }
    public void edit(Actor actor,long id,Forms.Activity input) {
        valid(input);
        var a=activities.lock(id);
        access.manager(actor,id(a,"clubId"));
        if (input.clubId()!=id(a,"clubId")) throw Problem.bad("活动所属社团不能更改");
        if (!Set.of("PENDING","REJECTED").contains(text(a,"status"))) throw Problem.conflict("已发布活动请取消后重新创建，避免影响现有报名和预约");
        activities.edit(id,input.title(),input.description(),input.category(),input.location(),input.poster(),input.startTime(),input.endTime(),input.signupDeadline(),input.capacity());
    }
    public void decide(Actor actor,long id,Forms.Decision input) {
        access.admin(actor);
        var a=activities.lock(id);
        if (!"PENDING".equals(text(a,"status"))) throw Problem.conflict("活动已审核");
        if (input.approve() && !time(a,"signupDeadline").isAfter(LocalDateTime.now(clock))) throw Problem.conflict("报名截止时间已过，请负责人调整后重新提交");
        activities.decision(id,input.approve()?"PUBLISHED":"REJECTED",actor.id(),input.note(),LocalDateTime.now(clock));
        messages.notice(id(a,"createdBy"),"活动审核结果",text(a,"title")+(input.approve()?"已发布，可以开始邀请同学报名了。":"未通过审核："+input.note()),LocalDateTime.now(clock),"NOTICE",null);
    }
    public Map<String,Object> register(Actor actor,long id) {
        var a=activities.lock(id);
        var now=LocalDateTime.now(clock);
        if (!"PUBLISHED".equals(text(a,"status")) || !now.isBefore(time(a,"signupDeadline"))) throw Problem.conflict("活动当前不能报名");
        var existing=activities.registration(id,actor.id());
        if (existing!=null && !"CANCELLED".equals(text(existing,"status"))) return existing;
        String state=activities.registered(id)<integer(a,"capacity")?"REGISTERED":"WAITLISTED";
        long registration=activities.register(id,actor.id(),state,now);
        messages.notice(actor.id(),"REGISTERED".equals(state)?"报名成功":"已加入候补",text(a,"title")+("REGISTERED".equals(state)?"，到时见！":"，有名额时会按报名顺序递补。"),now,"NOTICE",null);
        if ("REGISTERED".equals(state)) reminder(actor.id(),registration,a,now);
        return activities.registration(id,actor.id());
    }
    private void reminder(long user,long registration,Map<String,Object> a,LocalDateTime now) {
        var when=time(a,"startTime").minusHours(1);
        if (when.isAfter(now)) messages.notice(user,"活动即将开始",text(a,"title")+"将在一小时后开始，地点："+text(a,"location"),when,"ACTIVITY",registration);
    }
    public void cancelRegistration(Actor actor,long id) {
        var a=activities.lock(id);
        var r=activities.registration(id,actor.id());
        if (r==null || "CANCELLED".equals(text(r,"status"))) return;
        if (!LocalDateTime.now(clock).isBefore(time(a,"startTime"))) throw Problem.conflict("活动已开始，不能取消报名");
        boolean occupied="REGISTERED".equals(text(r,"status"));
        activities.cancelRegistration(id(r,"id"));
        messages.cancelReminders("ACTIVITY",id(r,"id"));
        if (occupied && "PUBLISHED".equals(text(a,"status"))) {
            var waiting=activities.firstWaiting(id);
            if (waiting!=null) {
                activities.promote(id(waiting,"id"));
                messages.notice(id(waiting,"userId"),"候补递补成功",text(a,"title")+"有了一个名额，已经为你保留。",LocalDateTime.now(clock),"NOTICE",null);
                reminder(id(waiting,"userId"),id(waiting,"id"),a,LocalDateTime.now(clock));
            }
        }
    }
    public void cancel(Actor actor,long id) {
        var a=activities.lock(id);
        access.manager(actor,id(a,"clubId"));
        if ("CANCELLED".equals(text(a,"status"))) return;
        activities.cancel(id);
        for (var loan:loans.forActivity(id)) {
            loans.equipment(id(loan,"equipmentId"),true);
            loans.cancel(id(loan,"id"));
        }
        for (var r:activities.participants(id)) {
            activities.cancelRegistration(id(r,"id"));
            messages.notice(id(r,"userId"),"活动已取消",text(a,"title")+"已取消，请留意社团后续安排。",LocalDateTime.now(clock),"NOTICE",null);
        }
    }
    public List<Map<String,Object>> participants(Actor actor,long id) {
        var a=activities.view(id);
        access.manager(actor,id(a,"clubId"));
        return activities.participants(id);
    }
}
