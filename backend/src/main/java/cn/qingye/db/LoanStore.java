package cn.qingye.db;
import cn.qingye.model.Actor;
import cn.qingye.model.Reservation;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.*;
import static cn.qingye.db.Rows.*;

@Repository
public class LoanStore {
    private final Sql sql;
    public LoanStore(Sql sql) {
        this.sql=sql;
    }
    public List<Map<String,Object>> equipment() {
        return sql.list("SELECT e.*,(SELECT COALESCE(SUM(l.quantity),0) FROM loan l WHERE l.equipment_id=e.id AND l.status='CHECKED_OUT') AS borrowed_quantity FROM equipment e ORDER BY e.id LIMIT 100");
    }
    public Map<String,Object> equipment(long id,boolean lock) {
        return sql.one("SELECT * FROM equipment WHERE id=?"+(lock?" FOR UPDATE":""),id);
    }
    public long createEquipment(String name,String category,String description,String image,int quantity,boolean enabled) {
        return sql.insert("INSERT INTO equipment(name,category,description,image,total_quantity,enabled) VALUES (?,?,?,?,?,?)",name,category,description,image,quantity,enabled);
    }
    public void editEquipment(long id,String name,String category,String description,String image,int quantity,boolean enabled) {
        sql.update("UPDATE equipment SET name=?,category=?,description=?,image=?,total_quantity=?,enabled=? WHERE id=?",name,category,description,image,quantity,enabled,id);
    }
    public List<Reservation> reservations(long equipment,LocalDateTime start,LocalDateTime end) {
        return sql.list("SELECT planned_start,planned_end,quantity FROM loan WHERE equipment_id=? AND status IN ('APPROVED','CHECKED_OUT') AND planned_start<? AND planned_end>? FOR UPDATE",equipment,end,start).stream().map(r->new Reservation(time(r,"plannedStart"),time(r,"plannedEnd"),integer(r,"quantity"))).toList();
    }
    public List<Reservation> allReservations(long equipment,LocalDateTime now) {
        return sql.list("SELECT planned_start,planned_end,quantity FROM loan WHERE equipment_id=? AND status IN ('APPROVED','CHECKED_OUT') AND planned_end>? FOR UPDATE",equipment,now).stream().map(r->new Reservation(time(r,"plannedStart"),time(r,"plannedEnd"),integer(r,"quantity"))).toList();
    }
    public long borrowed(long equipment) {
        return sql.count("SELECT COALESCE(SUM(quantity),0) FROM loan WHERE equipment_id=? AND status='CHECKED_OUT'",equipment);
    }
    public long approvedFuture(long equipment,LocalDateTime now) {
        return sql.count("SELECT COUNT(*) FROM loan WHERE equipment_id=? AND status='APPROVED' AND planned_end>?",equipment,now);
    }
    public Map<String,Object> loan(long id,boolean lock) {
        return sql.one("SELECT * FROM loan WHERE id=?"+(lock?" FOR UPDATE":""),id);
    }
    public Map<String,Object> byKey(String key) {
        return sql.optional("SELECT * FROM loan WHERE request_key=?",key);
    }
    public long create(long activity,long actor,long equipment,int quantity,LocalDateTime start,LocalDateTime end,String reason,String key) {
        return sql.insert("INSERT INTO loan(activity_id,applicant_id,equipment_id,quantity,planned_start,planned_end,reason,request_key) VALUES (?,?,?,?,?,?,?,?)",activity,actor,equipment,quantity,start,end,reason,key);
    }
    public void decision(long id,String status,long actor,String note,LocalDateTime now) {
        sql.update("UPDATE loan SET status=?,reviewed_by=?,review_note=?,reviewed_at=? WHERE id=?",status,actor,note,now,id);
    }
    public void checkout(long id,LocalDateTime now) {
        sql.update("UPDATE loan SET status='CHECKED_OUT',checked_out_at=? WHERE id=?",now,id);
    }
    public void returned(long id,LocalDateTime now) {
        sql.update("UPDATE loan SET status='RETURNED',returned_at=? WHERE id=?",now,id);
    }
    public void cancel(long id) {
        sql.update("UPDATE loan SET status='CANCELLED' WHERE id=?",id);
    }
    public List<Map<String,Object>> list(Actor actor) {
        String where=actor.admin()?"":" WHERE (l.applicant_id=? OR EXISTS (SELECT 1 FROM club_member m WHERE m.club_id=a.club_id AND m.user_id=? AND m.role='MANAGER' AND m.status='ACTIVE'))";
        String query="SELECT l.*,e.name AS equipment_name,a.title AS activity_title,c.name AS club_name,u.name AS applicant_name FROM loan l JOIN equipment e ON e.id=l.equipment_id JOIN activity a ON a.id=l.activity_id JOIN club c ON c.id=a.club_id JOIN app_user u ON u.id=l.applicant_id"+where+" ORDER BY l.created_at DESC,l.id DESC LIMIT 200";
        return actor.admin()?sql.list(query):sql.list(query,actor.id(),actor.id());
    }
    public List<Map<String,Object>> forActivity(long id) {
        return sql.list("SELECT * FROM loan WHERE activity_id=? AND status IN ('PENDING','APPROVED') ORDER BY equipment_id,id",id);
    }
}
