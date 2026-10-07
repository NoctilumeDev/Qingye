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
    // Mutations must recheck persisted authority after their contended resource reads.
    // Actor remains the request identity snapshot; these guards add no account-row locks.
    public void adminMutation(Actor actor) {
        if (!currentAdministrator(actor)) throw Problem.forbidden();
    }
    public void managerMutation(Actor actor,long club) {
        if (!users.currentManager(actor.id(),club)) throw Problem.forbidden();
    }
    public boolean currentAdministrator(Actor actor) {
        return users.currentAdministrator(actor.id());
    }
}
