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
public class ClubService {
    private final ClubStore clubs;
    private final UserStore users;
    private final Access access;
    private final MessageStore messages;
    private final Clock clock;
    public ClubService(ClubStore clubs,UserStore users,Access access,MessageStore messages,Clock clock) {
        this.clubs=clubs;
        this.users=users;
        this.access=access;
        this.messages=messages;
        this.clock=clock;
    }
    public List<Map<String,Object>> list(Actor actor) {
        return clubs.list(actor.id());
    }
    public long create(Actor actor,Forms.Club input) {
        access.admin(actor);
        users.actor(input.managerId());
        long id=clubs.create(input.name(),input.description(),input.color(),actor.id());
        clubs.member(id,input.managerId(),"MANAGER","ACTIVE");
        return id;
    }
    public void edit(Actor actor,long id,Forms.Club input) {
        clubs.lock(id);
        access.managerMutation(actor,id);
        clubs.edit(id,input.name(),input.description(),input.color());
    }
    public void join(Actor actor,long id) {
        var club=clubs.lock(id);
        if (!flag(club,"enabled")) throw Problem.conflict("社团已停用");
        var member=clubs.membership(id,actor.id());
        if (member==null) clubs.member(id,actor.id(),"MEMBER","PENDING");
        else if (Set.of("REJECTED","LEFT").contains(text(member,"status"))) clubs.membership(id,actor.id(),"MEMBER","PENDING");
    }
    public void leave(Actor actor,long id) {
        clubs.lock(id);
        var member=clubs.membership(id,actor.id());
        if (member==null) return;
        if ("MANAGER".equals(text(member,"role"))) throw Problem.conflict("负责人请先由管理员交接身份");
        clubs.membership(id,actor.id(),"MEMBER","LEFT");
    }
    public List<Map<String,Object>> members(Actor actor,long id) {
        access.manager(actor,id);
        return clubs.members(id);
    }
    public void decide(Actor actor,long club,long user,Forms.Member input) {
        clubs.lock(club);
        access.managerMutation(actor,club);
        var member=clubs.membership(club,user);
        if (member==null) throw Problem.missing();
        String role=input.role()==null?"MEMBER":input.role();
        if ("MANAGER".equals(role)) access.adminMutation(actor);
        if (!"PENDING".equals(text(member,"status"))) {
            if (access.currentAdministrator(actor) && input.approve() && "ACTIVE".equals(text(member,"status"))) {
                if ("MANAGER".equals(text(member,"role")) && "MEMBER".equals(role) && clubs.managerCount(club)<=1) throw Problem.conflict("请先指定新的负责人，再交接当前负责人");
                clubs.membership(club,user,role,"ACTIVE");
                return;
            }
            throw Problem.conflict("这条入社申请已处理");
        }
        clubs.membership(club,user,role,input.approve()?"ACTIVE":"REJECTED");
        messages.notice(user,"入社申请有结果了",input.approve()?"欢迎加入社团，一起把校园生活过得精彩。":"本次入社申请未通过，可以联系社团负责人。",LocalDateTime.now(clock),"NOTICE",null);
    }
}
