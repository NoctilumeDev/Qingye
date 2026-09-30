package cn.qingye.db;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class ClubStore {
    private final Sql sql;
    public ClubStore(Sql sql) {
        this.sql=sql;
    }
    public List<Map<String,Object>> list(long userId) {
        return sql.list("SELECT c.*,m.role AS my_role,m.status AS my_status,(SELECT COUNT(*) FROM club_member z WHERE z.club_id=c.id AND z.status='ACTIVE') AS member_count FROM club c LEFT JOIN club_member m ON m.club_id=c.id AND m.user_id=? WHERE c.enabled=TRUE ORDER BY c.id LIMIT 100",userId);
    }
    public Map<String,Object> lock(long id) {
        return sql.one("SELECT * FROM club WHERE id=? FOR UPDATE",id);
    }
    public long create(String name,String description,String color,long actor) {
        return sql.insert("INSERT INTO club(name,description,color,created_by) VALUES (?,?,?,?)",name,description,color,actor);
    }
    public void edit(long id,String name,String description,String color) {
        sql.update("UPDATE club SET name=?,description=?,color=? WHERE id=?",name,description,color,id);
    }
    public Map<String,Object> membership(long club,long user) {
        return sql.optional("SELECT * FROM club_member WHERE club_id=? AND user_id=?",club,user);
    }
    public long managerCount(long club) {
        return sql.count("SELECT COUNT(*) FROM club_member WHERE club_id=? AND role='MANAGER' AND status='ACTIVE'",club);
    }
    public long member(long club,long user,String role,String status) {
        return sql.insert("INSERT INTO club_member(club_id,user_id,role,status) VALUES (?,?,?,?)",club,user,role,status);
    }
    public void membership(long club,long user,String role,String status) {
        sql.update("UPDATE club_member SET role=?,status=?,joined_at=CURRENT_TIMESTAMP(6) WHERE club_id=? AND user_id=?",role,status,club,user);
    }
    public List<Map<String,Object>> members(long club) {
        return sql.list("SELECT m.*,u.name,u.avatar FROM club_member m JOIN app_user u ON u.id=m.user_id WHERE club_id=? ORDER BY m.status,m.id LIMIT 200",club);
    }
}
