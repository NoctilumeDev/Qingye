package cn.qingye.db;
import cn.qingye.model.Actor;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.*;

@Repository
public class ActivityStore {
    private final Sql sql;
    public ActivityStore(Sql sql) {
        this.sql=sql;
    }
    private static final String VIEW="SELECT a.*,c.name AS club_name,c.color,(SELECT COUNT(*) FROM registration r WHERE r.activity_id=a.id AND r.status='REGISTERED') AS registered_count,(SELECT COUNT(*) FROM registration r WHERE r.activity_id=a.id AND r.status='WAITLISTED') AS waitlist_count FROM activity a JOIN club c ON c.id=a.club_id ";
    public List<Map<String,Object>> list(Actor actor,String scope,String category,String keyword,int page,LocalDateTime after,LocalDateTime before) {
        var args=new ArrayList<Object>();
        String where=" WHERE c.enabled=TRUE ";
        if ("work".equals(scope)) {
            if (!actor.admin()) {
                where+="AND EXISTS (SELECT 1 FROM club_member m WHERE m.club_id=a.club_id AND m.user_id=? AND m.role='MANAGER' AND m.status='ACTIVE') ";
                args.add(actor.id());
            }
        }
        else if ("mine".equals(scope)) {
            where+="AND EXISTS (SELECT 1 FROM registration r WHERE r.activity_id=a.id AND r.user_id=? AND r.status<>'CANCELLED') ";
            args.add(actor.id());
        }
        else where+="AND a.status='PUBLISHED' ";
        if (category!=null && !category.isBlank()) {
            where+="AND a.category=? ";
            args.add(category);
        }
        if (keyword!=null && !keyword.isBlank()) {
            where+="AND (a.title LIKE ? OR a.description LIKE ?) ";
            args.add("%"+keyword+"%");
            args.add("%"+keyword+"%");
        }
        if (after!=null) {
            where+="AND a.end_time>? ";
            args.add(after);
        }
        if (before!=null) {
            where+="AND a.start_time<? ";
            args.add(before);
        }
        args.add(20);
        args.add(Math.max(0,Math.min(page,500))*20);
        return sql.list(VIEW+where+"ORDER BY a.start_time,a.id LIMIT ? OFFSET ?",args.toArray());
    }
    public Map<String,Object> view(long id) {
        return sql.one(VIEW+"WHERE a.id=?",id);
    }
    public Map<String,Object> lock(long id) {
        return sql.one("SELECT * FROM activity WHERE id=? FOR UPDATE",id);
    }
    public long create(long club,long actor,String title,String description,String category,String location,String poster,LocalDateTime start,LocalDateTime end,LocalDateTime deadline,int capacity) {
        return sql.insert("INSERT INTO activity(club_id,created_by,title,description,category,location,poster,start_time,end_time,signup_deadline,capacity) VALUES (?,?,?,?,?,?,?,?,?,?,?)",club,actor,title,description,category,location,poster,start,end,deadline,capacity);
    }
    public void edit(long id,String title,String description,String category,String location,String poster,LocalDateTime start,LocalDateTime end,LocalDateTime deadline,int capacity) {
        sql.update("UPDATE activity SET title=?,description=?,category=?,location=?,poster=?,start_time=?,end_time=?,signup_deadline=?,capacity=?,status='PENDING',reviewed_at=NULL,reviewed_by=NULL,review_note='' WHERE id=?",title,description,category,location,poster,start,end,deadline,capacity,id);
    }
    public void decision(long id,String status,long reviewer,String note,LocalDateTime now) {
        sql.update("UPDATE activity SET status=?,reviewed_by=?,review_note=?,reviewed_at=? WHERE id=?",status,reviewer,note,now,id);
    }
    public void cancel(long id) {
        sql.update("UPDATE activity SET status='CANCELLED' WHERE id=?",id);
    }
    public Map<String,Object> registration(long activity,long user) {
        return sql.optional("SELECT * FROM registration WHERE activity_id=? AND user_id=?",activity,user);
    }
    public long registered(long activity) {
        return sql.count("SELECT COUNT(*) FROM registration WHERE activity_id=? AND status='REGISTERED'",activity);
    }
    public long register(long activity,long user,String status,LocalDateTime now) {
        var existing=registration(activity,user);
        if (existing==null) return sql.insert("INSERT INTO registration(activity_id,user_id,status,joined_at) VALUES (?,?,?,?)",activity,user,status,now);
        long id=Rows.id(existing,"id");
        sql.update("UPDATE registration SET status=?,joined_at=? WHERE id=?",status,now,id);
        return id;
    }
    public void cancelRegistration(long id) {
        sql.update("UPDATE registration SET status='CANCELLED' WHERE id=?",id);
    }
    public Map<String,Object> firstWaiting(long activity) {
        return sql.optional("SELECT * FROM registration WHERE activity_id=? AND status='WAITLISTED' ORDER BY joined_at,id LIMIT 1 FOR UPDATE",activity);
    }
    public void promote(long id) {
        sql.update("UPDATE registration SET status='REGISTERED' WHERE id=? AND status='WAITLISTED'",id);
    }
    public List<Map<String,Object>> participants(long activity) {
        return sql.list("SELECT r.*,u.name FROM registration r JOIN app_user u ON u.id=r.user_id WHERE activity_id=? AND r.status<>'CANCELLED' ORDER BY r.joined_at,r.id",activity);
    }
    public List<Map<String,Object>> interests(long user) {
        return sql.list("SELECT DISTINCT a.category FROM registration r JOIN activity a ON a.id=r.activity_id WHERE r.user_id=? AND r.status<>'CANCELLED' LIMIT 10",user);
    }
}
