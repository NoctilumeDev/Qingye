package cn.qingye.db;

import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class AnnouncementStore {
    private final Sql sql;
    public AnnouncementStore(Sql sql) { this.sql=sql; }
    public List<Map<String,Object>> list(long user,boolean archived,boolean management,long before) {
        return sql.list("SELECT a.id,a.title,a.category,a.builtin_key,a.version,a.created_at,a.updated_at,COALESCE(s.archived,FALSE) archived FROM announcement a LEFT JOIN announcement_state s ON s.announcement_id=a.id AND s.user_id=? WHERE a.deleted_at IS NULL AND (?=TRUE OR COALESCE(s.archived,FALSE)=?) AND (?=0 OR a.id<?) ORDER BY a.id DESC LIMIT 21",user,management,archived,before,before).stream().map(this::display).toList();
    }
    public Map<String,Object> detail(long user,long id) {
        return display(sql.one("SELECT a.id,a.title,a.body,a.category,a.builtin_key,a.version,a.created_at,a.updated_at,COALESCE(s.archived,FALSE) archived FROM announcement a LEFT JOIN announcement_state s ON s.announcement_id=a.id AND s.user_id=? WHERE a.id=? AND a.deleted_at IS NULL",user,id));
    }
    private Map<String,Object> display(Map<String,Object> row) { row.put("archived",Rows.flag(row,"archived")); return row; }
    public Map<String,Object> lock(long id) { return sql.one("SELECT * FROM announcement WHERE id=? AND deleted_at IS NULL FOR UPDATE",id); }
    public long create(long actor,String title,String body,String category) {
        return sql.insert("INSERT INTO announcement(title,body,category,created_by) VALUES (?,?,?,?)",title,body,category,actor);
    }
    public void edit(long id,String title,String body,String category) {
        sql.update("UPDATE announcement SET title=?,body=?,category=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",title,body,category,id);
    }
    public void delete(long id) { sql.update("UPDATE announcement SET deleted_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=?",id); }
    public void archive(long user,long id,boolean archived) {
        sql.update("INSERT INTO announcement_state(user_id,announcement_id,archived) VALUES (?,?,?) ON DUPLICATE KEY UPDATE archived=VALUES(archived)",user,id,archived);
    }
    public void audit(long actor,long id,String action,Map<String,Object> previous) {
        sql.insert("INSERT INTO announcement_audit(actor_id,announcement_id,action,previous_title,previous_body,previous_version) VALUES (?,?,?,?,?,?)",actor,id,action,previous.get("title"),previous.get("body"),previous.get("version"));
    }
}
