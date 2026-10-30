package cn.qingye.business;

import cn.qingye.db.UserStore;
import cn.qingye.model.Actor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import static cn.qingye.db.Rows.flag;
import static cn.qingye.db.Rows.text;

@Service
public class AccountService {
    private final UserStore users;
    private final Access access;
    private final boolean demo;
    public AccountService(UserStore users,Access access,@Value("${qingye.demo-enabled}") boolean demo) { this.users=users; this.access=access; this.demo=demo; }
    public List<Map<String,Object>> search(Actor actor,String keyword,int page) {
        access.adminMutation(actor);
        if(keyword.length()>60 || page<0 || page>5000) throw Problem.bad("搜索内容或页码无效");
        return users.search(keyword.trim(),page,demo);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void administrator(Actor actor,long target,boolean grant) {
        access.adminMutation(actor);
        // Every role mutation locks the current administrators in the same order.
        // Recheck persisted authority after waiting; keep at least one enabled administrator.
        users.lockAdministrators();
        access.adminMutation(actor);
        var user=users.lockAccount(target);
        if(!demo && text(user,"openid").startsWith("demo:")) throw Problem.bad("正式登录模式不能授权演示账号");
        if(!flag(user,"enabled")) throw Problem.bad("账号已停用，不能调整管理员权限");
        if(flag(user,"admin")==grant) return;
        if(!grant && users.administratorCount(demo)<=1) throw Problem.conflict("至少保留一位管理员，请先授权其他账号");
        users.administrator(target,grant);
        users.recordRoleChange(actor.id(),target,!grant,grant);
    }
}
