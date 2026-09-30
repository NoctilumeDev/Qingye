package cn.qingye.business;
import cn.qingye.db.Sql;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;

@Component
public class DemoData implements ApplicationRunner {
    private final Sql sql;
    private final Clock clock;
    private final boolean demo;
    public DemoData(Sql sql,Clock clock,@Value("${qingye.demo-enabled}") boolean demo) {
        this.sql=sql;
        this.clock=clock;
        this.demo=demo;
    }
    @Override @Transactional public void run(ApplicationArguments args) {
        if (!demo || sql.count("SELECT COUNT(*) FROM app_user")>0) return;
        long admin=user("admin","林老师 · 管理员",true),photo=user("photo","许晴 · 摄影社负责人",false),student=user("student","周野 · 同学",false),sport=user("sport","顾燃 · 篮球社负责人",false),other=user("other","苏禾 · 同学",false);
        long photography=club("追光摄影社","记录校园里每一束值得停留的光。带上好奇心，一起出发。","blue",admin,photo);
        long basketball=club("绿茵运动社","球场见，草坪见。把课后的时间留给运动和朋友。","green",admin,sport);
        long coding=club("星火创作社","代码、设计和奇妙想法，在这里碰面。","orange",admin,photo);
        sql.insert("INSERT INTO club_member(club_id,user_id,role,status) VALUES (?,?,'MEMBER','ACTIVE')",basketball,photo);
        sql.insert("INSERT INTO club_member(club_id,user_id,role,status) VALUES (?,?,'MEMBER','ACTIVE')",photography,student);
        var now=LocalDateTime.now(clock).withNano(0);
        activity(photography,photo,admin,"把黄昏拍成一张明信片","带上相机，一起走过校园的草坪、长廊和最后一束夕阳。零基础也欢迎。","ART","东操场草坪",now.plusDays(2).withHour(17).withMinute(0),24);
        activity(basketball,sport,admin,"周末三人篮球，来组队！","轻松友好的校园篮球局。无需固定队伍，现场一起认识新朋友。","SPORT","南区篮球场",now.plusDays(3).withHour(15).withMinute(0),18);
        activity(coding,photo,admin,"把你的奇思妙想做出来","一起做一个小网页、一张海报，或者分享最近的灵感。欢迎带着半成品来。","TECH","创客教室 203",now.plusDays(4).withHour(19).withMinute(0),16);
        activity(basketball,sport,admin,"草坪上的校园志愿日","给校园公共空间做一次小整理，用一个下午换一个更舒服的角落。","VOLUNTEER","图书馆前草坪",now.plusDays(5).withHour(14).withMinute(0),30);
        long demoActivity=activity(photography,photo,admin,"今天的追光准备会","用于演示器材申请、审批、领取与归还的活动。","ART","社团器材室",now.plusHours(2),10);
        long camera=equipment("单反相机","摄影","用于活动记录和校园外拍，配套电池与存储卡。",5);
        equipment("轻便三脚架","摄影","让相机稳稳站住，也让灵感自由发生。",8);
        equipment("便携投影仪","展示","社团分享会与活动展示的好搭档。",3);
        equipment("开发板套件","科技","从点亮一颗灯开始，把想法变成作品。",20);
        sql.insert("INSERT INTO loan(activity_id,applicant_id,equipment_id,quantity,planned_start,planned_end,reason,request_key,status,reviewed_by,reviewed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",demoActivity,photo,camera,2,now,now.plusHours(4),"准备活动拍摄","demo-ready-to-pickup","APPROVED",admin,now);
    }
    private long user(String key,String name,boolean admin) {
        return sql.insert("INSERT INTO app_user(openid,name,admin) VALUES (?,?,?)","demo:"+key,name,admin);
    }
    private long club(String name,String description,String color,long creator,long manager) {
        long id=sql.insert("INSERT INTO club(name,description,color,created_by) VALUES (?,?,?,?)",name,description,color,creator);
        sql.insert("INSERT INTO club_member(club_id,user_id,role,status) VALUES (?,?,'MANAGER','ACTIVE')",id,manager);
        return id;
    }
    private long equipment(String name,String category,String description,int quantity) {
        return sql.insert("INSERT INTO equipment(name,category,description,total_quantity) VALUES (?,?,?,?)",name,category,description,quantity);
    }
    private long activity(long club,long creator,long admin,String title,String description,String category,String location,LocalDateTime start,int capacity) {
        return sql.insert("INSERT INTO activity(club_id,created_by,title,description,category,location,start_time,end_time,signup_deadline,capacity,status,reviewed_by,reviewed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",club,creator,title,description,category,location,start,start.plusHours(2),start.minusHours(1),capacity,"PUBLISHED",admin,LocalDateTime.now(clock));
    }
}
