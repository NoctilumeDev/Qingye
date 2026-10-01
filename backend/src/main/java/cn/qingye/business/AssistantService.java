package cn.qingye.business;
import cn.qingye.db.*;
import cn.qingye.integration.LlmPlanner;
import cn.qingye.model.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.format.DateTimeFormatter;
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
    private static final Set<String> INTENTS=Set.of("ACTIVITIES","EQUIPMENT","MY_REGISTRATIONS","MY_LOANS","OUT_OF_SCOPE");
    private static final Set<String> CATEGORIES=Set.of("SPORT","ART","TECH","VOLUNTEER","OTHER");
    private static final DateTimeFormatter DISPLAY_TIME=DateTimeFormatter.ofPattern("MM-dd HH:mm");
    public AssistantService(ActivityStore activities,LoanStore loans,LoanService loanService,LlmPlanner planner,Clock clock) {
        this.activities=activities;
        this.loans=loans;
        this.loanService=loanService;
        this.planner=planner;
        this.clock=clock;
    }
    public Map<String,Object> ask(Actor actor,String question) {
        // Gate 1: local scope, before any network call.
        if (!Pattern.compile("活动|社团|报名|候补|器材|相机|三脚架|投影|篮球|音乐|摄影|编程|志愿|借用|归还").matcher(question).find()) return Map.of("answer","目前支持中文查询校园活动、报名和器材借用。试试：这周末有什么活动？","items",List.of(),"mode","LOCAL","intent","OUT_OF_SCOPE");
        if (Pattern.compile("忽略.{0,8}(规则|指令)|密码|密钥|token|执行.{0,8}SQL|DROP\\s+TABLE",Pattern.CASE_INSENSITIVE).matcher(question).find()) throw Problem.bad("这里只查询校园记录，不执行指令或提供凭据");
        if (!hasQueryIntent(question)) return clarification("LOCAL");
        var local=localPlan(question);
        var remote=planner.plan(question);
        var plan=remote.filter(this::valid).orElse(local);
        // Gate 2: a closed intent/parameter set. The model cannot supply SQL or identity.
        if (!valid(plan)) throw Problem.bad("查询参数无效");
        if (plan.intent().equals("OUT_OF_SCOPE")) return clarification("MODEL_PLAN");
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
        String answer=rows.isEmpty()?emptyAnswer(plan.intent()):"查到 "+rows.size()+" 条记录：\n"+String.join("\n",rows.stream().limit(10).map(this::fact).toList());
        return Map.of("answer",answer,"items",rows,"intent",plan.intent(),"mode",remote.isPresent() && valid(remote.get())?"MODEL_PLAN":"LOCAL");
    }
    public boolean valid(QueryPlan p) {
        if (p==null || p.intent()==null || !INTENTS.contains(p.intent()) || (p.category()!=null && !CATEGORIES.contains(p.category())) || (p.keyword()!=null && p.keyword().length()>30)) return false;
        if (p.intent().equals("OUT_OF_SCOPE")) return p.category()==null && p.keyword()==null && p.start()==null && p.end()==null;
        if ((p.start()==null)!=(p.end()==null)) return false;
        var now=LocalDateTime.now(clock);
        return p.start()==null || (p.start().isBefore(p.end()) && !p.start().isBefore(now.minusDays(1)) && !p.end().isAfter(now.plusYears(1)) && Duration.between(p.start(),p.end()).compareTo(Duration.ofDays(31))<=0);
    }
    private boolean hasQueryIntent(String question) {
        if (Pattern.compile("查|找|看|搜|什么|有啥|哪些|哪[里个]|多少|几[台个件]|剩余|可用|有没有|是否|能否|可以|还有|我的|想参加|想借|能借|借用|归还|报名|候补|时间|地点|有.{0,6}(器材|相机|三脚架|投影仪|开发板)").matcher(question).find()) return true;
        // Topic-only shortcuts remain usable, but a name buried in unrelated text is insufficient.
        return question.replaceAll("[\\s，。！？,.!?]","").matches("(?:(?:今天|明天|这周末|本周末|这周|本周|周末)?(?:校园|社团|运动|篮球|摄影|音乐|编程|科技|志愿)?活动)|器材|相机|三脚架|投影仪|开发板|社团");
    }
    private Map<String,Object> clarification(String mode) {
        return Map.of("answer","这句话还没有明确的校园查询，请换个说法。比如：相机现在还有多少？或：这周末有什么活动？","items",List.of(),"mode",mode,"intent","OUT_OF_SCOPE");
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
        if(row.containsKey("equipmentName")) return text(row,"equipmentName")+" ×"+integer(row,"quantity")+"，"+loanStatus(text(row,"status"));
        if(row.containsKey("title")) return text(row,"title")+"，"+time(row,"startTime").format(DISPLAY_TIME)+"，地点："+text(row,"location");
        return text(row,"name")+"，总量 "+integer(row,"totalQuantity")+"，当前实物可用 "+Math.max(0,integer(row,"totalQuantity")-integer(row,"borrowedQuantity"));
    }
    private String emptyAnswer(String intent) {
        return switch(intent) {
            case "MY_REGISTRATIONS" -> "你还没有报名活动，去发现页挑一场喜欢的吧。";
            case "MY_LOANS" -> "你还没有器材借用记录。";
            case "EQUIPMENT" -> "暂时没有找到符合条件的器材，换个关键词试试。";
            default -> "暂时没有找到符合条件的活动，换个关键词再试试。";
        };
    }
    private String loanStatus(String status) {
        return switch(status) {
            case "PENDING" -> "待审核";
            case "APPROVED" -> "已批准";
            case "REJECTED" -> "未通过";
            case "CHECKED_OUT" -> "已领取";
            case "RETURNED" -> "已归还";
            case "CANCELLED" -> "已取消";
            default -> "状态待确认";
        };
    }
}
