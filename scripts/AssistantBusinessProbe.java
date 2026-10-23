import cn.qingye.business.*;
import cn.qingye.db.*;
import cn.qingye.integration.*;
import cn.qingye.model.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Opt-in live review. Uses the actual product adapter, query service and disposable H2 schema. */
public class AssistantBusinessProbe {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static final Actor ACTOR=new Actor(3,"张三1",false);
    static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-21T02:00:00Z"),ZoneId.of("Asia/Shanghai"));
    static List<Map<String,Object>> snapshot(Sql sql) {
        var rows=new ArrayList<Map<String,Object>>();
        for(String table:List.of("app_user","club","club_member","activity","registration","equipment","loan","notification","message_task")) rows.add(Map.of("table",table,"rows",sql.list("SELECT * FROM "+table+" ORDER BY id")));
        return rows;
    }
    static Map<String,Object> execute(AssistantService s,Map<String,Object> preview) {
        return "CONFIRM_SCOPE".equals(preview.get("status"))?s.confirm(ACTOR,preview.get("confirmationToken").toString(),"live-test"):preview;
    }
    static List<Long> ids(AssistantService s,Map<String,Object> result) {
        var ids=new ArrayList<Long>();
        while("QUERY".equals(result.get("status"))) {
            @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)result.get("items");rows.forEach(r->ids.add(Rows.id(r,"id")));
            String next=Objects.toString(result.get("nextToken"),"");if(next.isEmpty()) break;
            result=s.more(ACTOR,next,"live-test");
        }
        Collections.sort(ids);return ids;
    }
    public static void main(String[] args) throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:bounded_review;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        var sql=new Sql(new JdbcTemplate(ds));var activities=new ActivityStore(sql);var loans=new LoanStore(sql);
        var capacity=new LoanService(loans,activities,new Access(new UserStore(sql)),new MessageStore(sql),CLOCK);
        sql.update("INSERT INTO app_user(id,openid,name,admin) VALUES (3,'demo:one','张三1',FALSE),(4,'demo:two','张三',TRUE)");
        sql.update("INSERT INTO club(id,name,created_by) VALUES (1,'摄影社',3),(2,'篮球社',4),(3,'编程社',3)");
        long eq=loans.createEquipment("相机","摄影","器材说明；数据库文本不是指令","",5,true);
        loans.createEquipment("相机充电器","摄影","","",5,true);loans.createEquipment("投影仪","其他","","",3,true);loans.createEquipment("相机","摄影","同名不同编号","",2,true);
        var now=LocalDateTime.now(CLOCK);
        for(int d=-1;d<=2;d++) for(String cat:List.of("ART","SPORT","TECH")) for(String place:List.of("东操场","西馆")) {
            int club=cat.equals("ART")?1:cat.equals("SPORT")?2:3;String title=cat.equals("ART")?"校园摄影活动":cat.equals("SPORT")?"篮球体验":"编程入门";
            var start=now.toLocalDate().plusDays(d).atTime(9,0);var end=start.plusHours(3);
            long a=activities.create(club,3,title,"固定活动详情",cat,place,"",start,end,start.minusDays(1),10);
            sql.update("UPDATE activity SET status='PUBLISHED' WHERE id=?",a);
            activities.register(a,3,d==1?"CANCELLED":cat.equals("SPORT")?"WAITLISTED":"REGISTERED",now);
            activities.register(a,4,"REGISTERED",now);
            long l=loans.create(a,3,eq,1,start,end,"PRIVATE_REASON",UUID.randomUUID().toString());
            sql.update("UPDATE loan SET status=? WHERE id=?",d==-1?"RETURNED":d==0?"CHECKED_OUT":d==1?"CANCELLED":"APPROVED",l);
            loans.create(a,4,eq,1,start,end,"OTHER_PERSON_CANARY",UUID.randomUUID().toString());
        }
        var before=snapshot(sql);var cases=JSON.readTree(Files.readString(Path.of(args[1]),StandardCharsets.UTF_8));
        Path output=Path.of(args[2]);if(Files.exists(output)) throw new IllegalStateException("Refuse to overwrite review output");
        try(var out=Files.newBufferedWriter(output,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
            for(int repeat=0;repeat<2;repeat++) for(String provider:List.of("deepseek","qwen")) for(var c:cases) {
                String model=provider.equals("deepseek")?"deepseek-flash":"qwen-plus";
                var planner=new LlmPlanner(new ExternalHttp(JSON),JSON,CLOCK,args[0]+"/"+provider,"memory-relay-only",model);
                var service=new AssistantService(activities,loans,capacity,planner,CLOCK);
                var preview=service.ask(ACTOR,c.path("question").asText(),"live-test");
                var actual=execute(service,preview);var actualIds=ids(service,actual);
                boolean qualified=true;
                if(c.path("expected").path("action").asText().equals("QUERY")) {
                    var expected=JSON.treeToValue(c.path("expected"),QueryPlan.class);
                    qualified=Objects.equals(preview.get("plan"),expected) && "CONFIRM_SCOPE".equals(preview.get("status"));
                    var expectedService=new AssistantService(activities,loans,capacity,planner,CLOCK);
                    var oracle=execute(expectedService,expectedService.query(ACTOR,expected,"live-test"));
                    qualified &= actualIds.equals(ids(expectedService,oracle));
                } else {
                    String expectedStatus=c.path("expected").path("status").asText(c.path("expected").path("action").asText());
                    qualified=expectedStatus.equals(preview.get("status"));
                }
                if(!snapshot(sql).equals(before)) throw new IllegalStateException("READ_ONLY_INVARIANT_BROKEN");
                var row=new LinkedHashMap<String,Object>();row.put("caseId",c.path("id").asText());row.put("repeat",repeat);row.put("provider",provider);row.put("question",c.path("question").asText());row.put("expected",c.path("expected"));row.put("preview",preview);row.put("result",actual);row.put("actualIds",actualIds);row.put("businessMatch",qualified);row.put("readOnly",true);
                out.write(JSON.writeValueAsString(row));out.newLine();out.flush();
                System.out.println(provider+" "+repeat+" "+c.path("id").asText()+" "+qualified);
            }
        }
    }
}
