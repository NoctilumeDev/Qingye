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
public class LoanService {
    private final LoanStore loans;
    private final ActivityStore activities;
    private final Access access;
    private final MessageStore messages;
    private final Clock clock;
    public LoanService(LoanStore loans,ActivityStore activities,Access access,MessageStore messages,Clock clock) {
        this.loans=loans;
        this.activities=activities;
        this.access=access;
        this.messages=messages;
        this.clock=clock;
    }
    public List<Map<String,Object>> equipment() {
        return loans.equipment();
    }
    private void valid(LocalDateTime start,LocalDateTime end) {
        if (start==null || end==null || !start.isBefore(end) || Duration.between(start,end).compareTo(Duration.ofDays(7))>0 || end.isAfter(LocalDateTime.now(clock).plusYears(1))) throw Problem.bad("预约时间无效，每次借用最多七天，安排在未来一年内");
    }
    public Map<String,Object> availability(long id,LocalDateTime start,LocalDateTime end) {
        valid(start,end);
        var e=loans.equipment(id,true);
        int peak=CapacityScan.peak(start,end,loans.reservations(id,start,end));
        return Map.of("totalQuantity",integer(e,"totalQuantity"),"peakOccupied",peak,"availableQuantity",Math.max(0,integer(e,"totalQuantity")-peak),"physicalAvailable",Math.max(0,integer(e,"totalQuantity")-loans.borrowed(id)),"enabled",flag(e,"enabled"));
    }
    public long createEquipment(Actor actor,Forms.Equipment input) {
        access.admin(actor);
        return loans.createEquipment(input.name(),input.category(),input.description(),input.image(),input.totalQuantity(),input.enabled());
    }
    public void editEquipment(Actor actor,long id,Forms.Equipment input) {
        access.admin(actor);
        loans.equipment(id,true);
        var now=LocalDateTime.now(clock);
        var reservations=loans.allReservations(id,now);
        var end=reservations.stream().map(r->r.end()).max(Comparator.naturalOrder()).orElse(now.plusSeconds(1));
        int peak=CapacityScan.peak(now,end,reservations);
        if (input.totalQuantity()<Math.max(peak,loans.borrowed(id))) throw Problem.conflict("新数量低于已有预约峰值或当前借出数量");
        if (!input.enabled() && (loans.borrowed(id)>0 || loans.approvedFuture(id,now)>0)) throw Problem.conflict("请先处理有效预约和未归还器材，再停用");
        loans.editEquipment(id,input.name(),input.category(),input.description(),input.image(),input.totalQuantity(),input.enabled());
    }
    public long apply(Actor actor,Forms.Loan input) {
        valid(input.plannedStart(),input.plannedEnd());
        var a=activities.lock(input.activityId());
        access.manager(actor,id(a,"clubId"));
        if (!"PUBLISHED".equals(text(a,"status")) || !time(a,"endTime").isAfter(LocalDateTime.now(clock))) throw Problem.conflict("请为尚未结束的已发布活动申请器材");
        var e=loans.equipment(input.equipmentId(),true);
        var existing=loans.byKey(input.requestKey());
        if (existing!=null) {
            if (id(existing,"applicantId")!=actor.id() || id(existing,"activityId")!=input.activityId() || id(existing,"equipmentId")!=input.equipmentId() || integer(existing,"quantity")!=input.quantity() || !time(existing,"plannedStart").equals(input.plannedStart()) || !time(existing,"plannedEnd").equals(input.plannedEnd())) throw Problem.conflict("申请标识已用于其他申请");
            return id(existing,"id");
        }
        if (input.plannedStart().isBefore(LocalDateTime.now(clock))) throw Problem.bad("预约开始时间不能早于现在");
        if (!flag(e,"enabled") || input.quantity()>integer(e,"totalQuantity")) throw Problem.conflict("器材已停用或申请数量超过总量");
        return loans.create(input.activityId(),actor.id(),input.equipmentId(),input.quantity(),input.plannedStart(),input.plannedEnd(),input.reason(),input.requestKey());
    }
    // Every loan mutation acquires activity -> equipment -> loan, in that order.
    private Map<String,Object> lock(long id) {
        var l=loans.loan(id,false);
        activities.lock(id(l,"activityId"));
        loans.equipment(id(l,"equipmentId"),true);
        return loans.loan(id,true);
    }
    public void decide(Actor actor,long id,Forms.Decision input) {
        access.admin(actor);
        var l=lock(id);
        var a=activities.view(id(l,"activityId"));
        var e=loans.equipment(id(l,"equipmentId"),false);
        var now=LocalDateTime.now(clock);
        if (!"PENDING".equals(text(l,"status"))) throw Problem.conflict("申请已处理");
        if (input.approve()) {
            if (!"PUBLISHED".equals(text(a,"status")) || !flag(e,"enabled") || !time(l,"plannedEnd").isAfter(now)) throw Problem.conflict("活动、器材或预约时间已失效");
            int peak=CapacityScan.peak(time(l,"plannedStart"),time(l,"plannedEnd"),loans.reservations(id(l,"equipmentId"),time(l,"plannedStart"),time(l,"plannedEnd")));
            if (peak+integer(l,"quantity")>integer(e,"totalQuantity")) throw Problem.conflict("该时间段最多还能预约 "+Math.max(0,integer(e,"totalQuantity")-peak)+" 件，不能批准");
        }
        loans.decision(id,input.approve()?"APPROVED":"REJECTED",actor.id(),input.note(),now);
        messages.notice(id(l,"applicantId"),"器材申请审核结果",text(e,"name")+(input.approve()?"预约已批准，请在预约时间内到工作台确认领取。":"预约未通过："+input.note()),now,"NOTICE",null);
        var reminder=time(l,"plannedEnd").minusHours(1);
        if (input.approve() && reminder.isAfter(now)) messages.notice(id(l,"applicantId"),"器材归还提醒",text(e,"name")+"将在一小时后到期，请及时归还。",reminder,"LOAN",id);
    }
    public void checkout(Actor actor,long id) {
        access.admin(actor);
        var l=lock(id);
        var now=LocalDateTime.now(clock);
        if ("CHECKED_OUT".equals(text(l,"status"))) return;
        if (!"APPROVED".equals(text(l,"status"))) throw Problem.conflict("只有已批准的预约可以领取");
        if (now.isBefore(time(l,"plannedStart")) || !now.isBefore(time(l,"plannedEnd"))) throw Problem.conflict("请在预约时间内领取");
        var e=loans.equipment(id(l,"equipmentId"),false);
        long available=integer(e,"totalQuantity")-loans.borrowed(id(l,"equipmentId"));
        if (available<integer(l,"quantity")) throw Problem.conflict("当前实物仅剩 "+Math.max(0,available)+" 件；前序借用可能尚未归还，本次未完成领取，预约仍保留");
        loans.checkout(id,now);
        messages.notice(id(l,"applicantId"),"领取已确认",text(e,"name")+"已领取，请按预约时间归还。",now,"NOTICE",null);
    }
    public void returned(Actor actor,long id) {
        access.admin(actor);
        var l=lock(id);
        if ("RETURNED".equals(text(l,"status"))) return;
        if (!"CHECKED_OUT".equals(text(l,"status"))) throw Problem.conflict("只有已领取的器材可以确认归还");
        loans.returned(id,LocalDateTime.now(clock));
        messages.notice(id(l,"applicantId"),"归还已确认","这次器材借用已完成，感谢及时归还。",LocalDateTime.now(clock),"NOTICE",null);
    }
    public void cancel(Actor actor,long id) {
        var l=lock(id);
        var a=activities.view(id(l,"activityId"));
        access.manager(actor,id(a,"clubId"));
        if ("CANCELLED".equals(text(l,"status"))) return;
        if (!Set.of("PENDING","APPROVED").contains(text(l,"status"))) throw Problem.conflict("领取前才能取消，已领取器材请归还");
        loans.cancel(id);
    }
    public List<Map<String,Object>> list(Actor actor) {
        return loans.list(actor);
    }
}
