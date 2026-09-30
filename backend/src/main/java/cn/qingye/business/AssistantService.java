package cn.qingye.business;
import cn.qingye.db.*;
import cn.qingye.integration.LlmPlanner;
import cn.qingye.model.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.Pattern;
import static cn.qingye.db.Rows.*;

@Service
public class AssistantService {
    private final ActivityStore activities;
    private final LoanStore loans;
    private final LoanService loanService;
    private final LlmPlanner planner;
    private final Clock clock;
    private static final Set<String> INTENTS=Set.of("ACTIVITIES","EQUIPMENT","MY_REGISTRATIONS","MY_LOANS");
    private static final Set<String> CATEGORIES=Set.of("SPORT","ART","TECH","VOLUNTEER","OTHER");
    public AssistantService(ActivityStore activities,LoanStore loans,LoanService loanService,LlmPlanner planner,Clock clock) {
        this.activities=activities;
        this.loans=loans;
        this.loanService=loanService;
        this.planner=planner;
        this.clock=clock;
    }
    public Map<String,Object> ask(Actor actor,String question) {
        // Gate 1: local scope, before any network call.
        if (!Pattern.compile("活动|社团|报名|候补|器材|相机|三脚架|投影|篮球|音乐|摄影|编程|志愿|借用|归还").matcher(question).find()) return Map.of("answer","我可以帮你查校园活动、报名和器材借用。试试：这周末有什么活动？","items",List.of(),"mode","LOCAL","intent","OUT_OF_SCOPE");
        if (Pattern.compile("忽略.{0,8}(规则|指令)|密码|密钥|token|执行.{0,8}SQL|DROP\\s+TABLE",Pattern.CASE_INSENSITIVE).matcher(question).find()) throw Problem.bad("这里只查询校园记录，不执行指令或提供凭据");
        var local=localPlan(question);
        var remote=planner.plan(question);
        var plan=remote.filter(this::valid).orElse(local);
        // Gate 2: a closed intent/parameter set. The model cannot supply SQL or identity.
        if (!valid(plan)) throw Problem.bad("查询参数无效");
        List<Map<String,Object>> rows;
        // Gate 3: fixed parameterized queries; identity comes exclusively from the session.
        switch(plan.intent()) {
            case "ACTIVITIES" -> rows=activities.list(actor,"public",plan.category(),plan.keyword(),0,plan.start(),plan.end());
            case "MY_REGISTRATIONS" -> rows=activities.list(actor,"mine",null,null,0,null,null);
            case "MY_LOANS" -> rows=loans.list(actor).stream().filter(r->id(r,"applicantId")==actor.id()).limit(10).toList();
            case "EQUIPMENT" -> {
                rows=loans.equipment().stream().filter(r->plan.keyword()==null || text(r,"name").contains(plan.keyword())).limit(10).toList();
                if (plan.start()!=null && plan.end()!=null && Duration.between(plan.start(),plan.end()).compareTo(Duration.ofDays(7))<=0)
                for(var row:rows) row.put("availability",loanService.availability(id(row,"id"),plan.start(),plan.end()));
            }
            default -> throw Problem.bad("不支持的查询意图");
        }
        // Gate 4: render DB facts locally, never let the model invent an answer.
        String answer=rows.isEmpty()?"当前数据库里没有符合条件的记录。":"查到 "+rows.size()+" 条记录：\n"+String.join("\n",rows.stream().limit(10).map(this::fact).toList());
        return Map.of("answer",answer,"items",rows,"intent",plan.intent(),"mode",remote.isPresent() && valid(remote.get())?"MODEL_PLAN":"LOCAL");
    }
    public boolean valid(QueryPlan p) {
        if (p==null || p.intent()==null || !INTENTS.contains(p.intent()) || (p.category()!=null && !CATEGORIES.contains(p.category())) || (p.keyword()!=null && p.keyword().length()>30)) return false;
        if ((p.start()==null)!=(p.end()==null)) return false;
        var now=LocalDateTime.now(clock);
        return p.start()==null || (p.start().isBefore(p.end()) && !p.start().isBefore(now.minusDays(1)) && !p.end().isAfter(now.plusYears(1)) && Duration.between(p.start(),p.end()).compareTo(Duration.ofDays(31))<=0);
    }
    private QueryPlan localPlan(String q) {
        String intent=q.contains("我的") && (q.contains("报名") || q.contains("候补"))?"MY_REGISTRATIONS":q.contains("我的") && (q.contains("借") || q.contains("归还"))?"MY_LOANS":Pattern.compile("器材|相机|三脚架|投影|借用|篮球数量").matcher(q).find()?"EQUIPMENT":"ACTIVITIES";
        String category=Pattern.compile("运动|篮球|足球|跑步").matcher(q).find()?"SPORT":Pattern.compile("摄影|音乐|艺术").matcher(q).find()?"ART":Pattern.compile("编程|科技|开发").matcher(q).find()?"TECH":q.contains("志愿")?"VOLUNTEER":null;
        String keyword=null;
        for(String word:List.of("相机","三脚架","投影仪","开发板")) if(q.contains(word)) keyword=word;
        LocalDateTime start=null,end=null;
        var today=LocalDate.now(clock);
        if(q.contains("周末")) {
            var saturday=today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(5);
            start=saturday.atStartOfDay();
            end=saturday.plusDays(2).atStartOfDay();
            if(start.isBefore(LocalDateTime.now(clock))) start=LocalDateTime.now(clock);
        }
        else if(q.contains("明天")) {
            start=today.plusDays(1).atStartOfDay();
            end=start.plusDays(1);
        }
        else if(q.contains("今天")) {
            start=today.atStartOfDay();
            end=start.plusDays(1);
        }
        else if(q.contains("这周") || q.contains("本周")) {
            start=LocalDateTime.now(clock);
            end=today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay();
        }
        return new QueryPlan(intent,category,keyword,start,end);
    }
    private String fact(Map<String,Object> row) {
        if(row.containsKey("equipmentName")) return text(row,"equipmentName")+" ×"+integer(row,"quantity")+"，状态 "+text(row,"status");
        if(row.containsKey("title")) return text(row,"title")+"，"+text(row,"startTime")+"，地点："+text(row,"location");
        return text(row,"name")+"，总量 "+integer(row,"totalQuantity")+"，当前实物可用 "+Math.max(0,integer(row,"totalQuantity")-integer(row,"borrowedQuantity"));
    }
}
