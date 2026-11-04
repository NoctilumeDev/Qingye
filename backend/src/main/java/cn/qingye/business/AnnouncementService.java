package cn.qingye.business;

import cn.qingye.db.AnnouncementStore;
import cn.qingye.db.UserStore;
import cn.qingye.model.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static cn.qingye.db.Rows.id;

@Service
public class AnnouncementService {
    private final AnnouncementStore store;
    private final UserStore users;
    private final Access access;
    public AnnouncementService(AnnouncementStore store,UserStore users,Access access) { this.store=store; this.users=users; this.access=access; }
    public Map<String,Object> list(Actor actor,boolean archived,boolean management,long before) {
        if(before<0) throw Problem.bad("公告页码无效");
        if(management) access.adminMutation(actor);
        var rows=store.list(actor.id(),archived,management,before);
        boolean more=rows.size()>20;
        var items=List.copyOf(rows.subList(0,Math.min(20,rows.size())));
        return Map.of("items",items,"hasMore",more,"nextBefore",more?id(items.get(items.size()-1),"id"):0);
    }
    public Map<String,Object> detail(Actor actor,long id) { return store.detail(actor.id(),id); }
    private void lockAdministrator(Actor actor) {
        access.adminMutation(actor);
        users.lockAccount(actor.id());
        access.adminMutation(actor);
    }
    private void content(String title,String body,String category) {
        if(title==null || title.isBlank() || title.length()>80 || body==null || body.isBlank() || body.length()>5000 || !Set.of("UPDATE","MAINTENANCE","GUIDE").contains(category==null?"":category)) throw Problem.bad("请填写公告标题、正文和类型");
    }
    private Map<String,Object> current(long id,long version,boolean editing) {
        var row=store.lock(id);
        if(editing && row.get("builtinKey")!=null) throw new Problem(403,"这条使用说明由青野维护，不能修改或删除");
        if(id(row,"version")!=version) throw Problem.conflict("这条公告已更新，请重新打开后再操作");
        return row;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public long create(Actor actor,String title,String body,String category) {
        lockAdministrator(actor); content(title,body,category);
        long created=store.create(actor.id(),title.trim(),body.trim(),category);
        store.audit(actor.id(),created,"CREATE",Map.of());
        return created;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void edit(Actor actor,long id,long version,String title,String body,String category) {
        lockAdministrator(actor); content(title,body,category);
        var previous=current(id,version,true);
        store.edit(id,title.trim(),body.trim(),category); store.audit(actor.id(),id,"EDIT",previous);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void delete(Actor actor,long id,long version) {
        lockAdministrator(actor);
        var previous=current(id,version,true);
        store.delete(id); store.audit(actor.id(),id,"DELETE",previous);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void archive(Actor actor,long id,long version,boolean archived) {
        current(id,version,false);
        store.archive(actor.id(),id,archived);
    }
}
