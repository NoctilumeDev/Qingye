package cn.qingye.db;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.*;

@Repository
public class MessageStore {
    public static final int TRASH_DAYS=7;
    private final Sql sql;
    public MessageStore(Sql sql) {
        this.sql=sql;
    }
    public void notice(long user,String title,String body,LocalDateTime when,String source,Long sourceId) {
        long notification=sql.insert("INSERT INTO notification(user_id,title,body) VALUES (?,?,?)",user,title,body);
        sql.insert("INSERT INTO message_task(notification_id,available_at,source_kind,source_id) VALUES (?,?,?,?)",notification,when,source,sourceId);
    }
    public List<Map<String,Object>> list(long user) {
        return sql.list("SELECT id,title,body,read_at,created_at FROM notification WHERE user_id=? AND delivered=TRUE AND deleted_at IS NULL ORDER BY id DESC LIMIT 100",user);
    }
    public void read(long id,long user,LocalDateTime now) {
        sql.update("UPDATE notification SET read_at=? WHERE id=? AND user_id=? AND delivered=TRUE AND deleted_at IS NULL AND read_at IS NULL",now,id,user);
    }
    public void readAll(long user,LocalDateTime now) {
        sql.update("UPDATE notification SET read_at=? WHERE user_id=? AND delivered=TRUE AND deleted_at IS NULL AND read_at IS NULL",now,user);
    }
    public void clear(long user,List<Long> ids,LocalDateTime now) {
        if(ids.isEmpty()) return;
        var selected=new LinkedHashSet<>(ids);
        var args=new ArrayList<Object>();
        args.add(now);args.add(user);args.addAll(selected);
        String placeholders=String.join(",",Collections.nCopies(selected.size(),"?"));
        sql.update("UPDATE notification SET deleted_at=? WHERE user_id=? AND delivered=TRUE AND deleted_at IS NULL AND id IN ("+placeholders+")",args.toArray());
    }
    public List<Map<String,Object>> trash(long user,LocalDateTime now) {
        var rows=sql.list("SELECT id,title,body,read_at,created_at,deleted_at FROM notification WHERE user_id=? AND delivered=TRUE AND deleted_at>? ORDER BY deleted_at DESC,id DESC LIMIT 100",user,now.minusDays(TRASH_DAYS));
        rows.forEach(row->row.put("expiresAt",Rows.time(row,"deletedAt").plusDays(TRASH_DAYS).toString()));
        return rows;
    }
    public int restore(long user,List<Long> ids,LocalDateTime now) {
        if(ids.isEmpty()) return 0;
        var selected=new LinkedHashSet<>(ids);
        var args=new ArrayList<Object>();
        args.add(user);args.add(now.minusDays(TRASH_DAYS));args.addAll(selected);
        String placeholders=String.join(",",Collections.nCopies(selected.size(),"?"));
        return sql.update("UPDATE notification SET deleted_at=NULL WHERE user_id=? AND delivered=TRUE AND deleted_at>? AND id IN ("+placeholders+")",args.toArray());
    }
    // Called inside TaskService's transaction: lock before deleting dependent delivery tasks.
    public int purgeExpired(LocalDateTime now) {
        var expired=sql.list("SELECT id FROM notification WHERE delivered=TRUE AND deleted_at<=? ORDER BY deleted_at,id LIMIT 100 FOR UPDATE",now.minusDays(TRASH_DAYS));
        if(expired.isEmpty()) return 0;
        Object[] ids=expired.stream().map(row->row.get("id")).toArray();
        String placeholders=String.join(",",Collections.nCopies(ids.length,"?"));
        sql.update("DELETE FROM message_task WHERE notification_id IN ("+placeholders+")",ids);
        return sql.update("DELETE FROM notification WHERE id IN ("+placeholders+")",ids);
    }
    public void cancelReminders(String kind,long id) {
        sql.update("UPDATE message_task SET status='CANCELLED' WHERE source_kind=? AND source_id=? AND status='PENDING'",kind,id);
    }
    public List<Map<String,Object>> pending(LocalDateTime now) {
        return sql.list("SELECT id FROM message_task WHERE status='PENDING' AND available_at<=? ORDER BY available_at,id LIMIT 30",now);
    }
    public Map<String,Object> lock(long id) {
        return sql.optional("SELECT * FROM message_task WHERE id=? FOR UPDATE",id);
    }
    public boolean applicable(String kind,long id) {
        if ("ACTIVITY".equals(kind)) return sql.count("SELECT COUNT(*) FROM registration r JOIN activity a ON a.id=r.activity_id WHERE r.id=? AND r.status='REGISTERED' AND a.status='PUBLISHED'",id)>0;
        if ("LOAN".equals(kind)) return sql.count("SELECT COUNT(*) FROM loan WHERE id=? AND status IN ('APPROVED','CHECKED_OUT')",id)>0;
        return true;
    }
    public void finish(long id,long notification,LocalDateTime now,boolean applicable) {
        if (applicable) sql.update("UPDATE notification SET delivered=TRUE WHERE id=?",notification);
        sql.update("UPDATE message_task SET status=?,completed_at=?,last_error='' WHERE id=?",applicable?"DONE":"CANCELLED",now,id);
    }
    public void failed(long id,String message,LocalDateTime retry) {
        sql.update("UPDATE message_task SET attempts=attempts+1,last_error=?,available_at=? WHERE id=? AND status='PENDING'",message,retry,id);
    }
    public Map<String,Object> stats() {
        return Map.of("pending",sql.count("SELECT COUNT(*) FROM message_task WHERE status='PENDING'"),"done",sql.count("SELECT COUNT(*) FROM message_task WHERE status='DONE'"));
    }
}
