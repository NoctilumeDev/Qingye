package cn.qingye.business;
import cn.qingye.db.UserStore;
import cn.qingye.model.Actor;
import org.springframework.stereotype.Component;

@Component
public class Access {
    private final UserStore users;
    public Access(UserStore users) {
        this.users=users;
    }
    public void admin(Actor actor) {
        if (!actor.admin()) throw Problem.forbidden();
    }
    public void manager(Actor actor,long club) {
        if (!actor.admin() && !users.manager(actor.id(),club)) throw Problem.forbidden();
    }
    public boolean manages(Actor actor,long club) {
        return actor.admin() || users.manager(actor.id(),club);
    }
}
