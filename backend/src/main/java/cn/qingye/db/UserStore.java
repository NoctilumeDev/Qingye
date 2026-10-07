package cn.qingye.db;
import cn.qingye.model.Actor;
import org.springframework.stereotype.Repository;
import java.util.*;
import static cn.qingye.db.Rows.*;

@Repository
public class UserStore {
    private final Sql sql;
    public UserStore(Sql sql) {
        this.sql=sql;
    }
    public Actor actor(long id) {
        var row=sql.one("SELECT id,name,admin FROM app_user WHERE id=? AND enabled=TRUE",id);
        return new Actor(id,text(row,"name"),flag(row,"admin"));
    }
    public Map<String,Object> byOpenid(String openid) {
        return sql.optional("SELECT id,name,admin,enabled FROM app_user WHERE openid=?",openid);
    }
    public long create(String openid,String name) {
        return sql.insert("INSERT INTO app_user(openid,name) VALUES (?,?)",openid,name);
    }
    public Map<String,Object> profile(long id) {
        var row=sql.one("SELECT id,name,avatar,admin FROM app_user WHERE id=?",id);
        row.put("workbench",flag(row,"admin") || sql.count("SELECT COUNT(*) FROM club_member WHERE user_id=? AND role='MANAGER' AND status='ACTIVE'",id)>0);
        return row;
    }
    public void profile(long id,String name,String avatar) {
        sql.update("UPDATE app_user SET name=?,avatar=? WHERE id=?",name,avatar,id);
    }
    public List<Map<String,Object>> users() {
        return sql.list("SELECT id,name,admin,enabled FROM app_user ORDER BY id LIMIT 100");
    }
    public boolean manager(long userId,long clubId) {
        return sql.count("SELECT COUNT(*) FROM club_member m JOIN club c ON c.id=m.club_id WHERE m.user_id=? AND m.club_id=? AND m.role='MANAGER' AND m.status='ACTIVE' AND c.enabled=TRUE",userId,clubId)>0;
    }
    public boolean currentAdministrator(long userId) {
        return sql.count("SELECT COUNT(*) FROM app_user WHERE id=? AND enabled=TRUE AND admin=TRUE",userId)>0;
    }
    public boolean currentManager(long userId,long clubId) {
        return sql.count("SELECT COUNT(*) FROM app_user u WHERE u.id=? AND u.enabled=TRUE AND (u.admin=TRUE OR EXISTS (SELECT 1 FROM club_member m JOIN club c ON c.id=m.club_id WHERE m.user_id=u.id AND m.club_id=? AND m.role='MANAGER' AND m.status='ACTIVE' AND c.enabled=TRUE))",userId,clubId)>0;
    }
}
