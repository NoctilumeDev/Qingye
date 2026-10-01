package cn.qingye.business;
import cn.qingye.db.*;
import cn.qingye.integration.LlmPlanner;
import cn.qingye.model.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.text.Normalizer;
import java.util.concurrent.*;
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
    private static final Pattern SELF=Pattern.compile("我的|本人|我(?:已经|曾经|之前|有没有|是否|已|都)?(?:报名|候补|参加|借用|借过|借了|归还)");
    private static final Pattern RESTRICTED=Pattern.compile("密码|密碼|密钥|密鑰|口令|凭据|token|password|systemprompt|系统提示词|忽略.{0,12}(规则|指令|权限)|(?:无视|绕过|不顾).{0,12}(规则|权限|限制)|(?:我是|作为).{0,12}(最高权限|最高管理员|超级管理员|创始人|开发者)|(?:执行|运行).{0,8}SQL|(?:DROP|TRUNCATE|DELETE|ALTER|INSERT|UPDATE)(?:TABLE|DATABASE|FROM|INTO)|(?:清空|删除|重置).{0,12}(数据库|所有数据|全部数据|数据表)",Pattern.CASE_INSENSITIVE);
    private static final Pattern WRITE=Pattern.compile("^(?:取消报名|取消预约|报名|加入|退出|领取|归还|创建|发布|修改|删除|清空|批准|审批)(?!人数|名额|截止|时间|条件|规则|流程|状态|情况|结果|记录|了|过|方式)|(?:帮我|替我|给我|请|我要|直接|立即|马上|现在就)(?:把|将)?(?:这个|这场|该|所有|全部)?(?:活动|社团|器材|预约|记录|数据库)?(?:报名|取消|加入|退出|领取|归还|创建|发布|修改|删除|清空|批准|审批)");
    private static final Pattern PERSONAL=Pattern.compile("报名|候补|参加|参与|借用|借过|借了|借出|归还|记录|名单|个人信息");
    private static final Pattern OTHER_PERSON=Pattern.compile("别人|他人|其他人|其他同学|其他用户|其他负责人|管理员的|他的|她的|谁|名单");
    private static final Pattern MEMBERSHIP=Pattern.compile("入社|加入|参加|参与|成员|会员|所属|属于|参没参加");
    private static final Pattern PUBLIC_QUERY=Pattern.compile("报名(?:人数|名额|截止|条件|规则|流程)|活动.{0,8}(时间|地点|名额|哪些|什么)|开放|可用|库存|可借|剩余|(?:有哪些|有什么|可以参加|能参加|推荐).{0,12}活动");
    private final Set<Long> planning=ConcurrentHashMap.newKeySet();
    private final Semaphore modelSlots=new Semaphore(4);
    public AssistantService(ActivityStore activities,LoanStore loans,LoanService loanService,LlmPlanner planner,Clock clock) {
        this.activities=activities;
        this.loans=loans;
        this.loanService=loanService;
        this.planner=planner;
        this.clock=clock;
    }
    public Map<String,Object> ask(Actor actor,String question) {
        // Gate 1: local scope, before any network call.
        question=Normalizer.normalize(question,Normalizer.Form.NFKC).replaceAll("\\p{Cf}","").strip();
        String compact=question.replaceAll("\\s","");
        if (RESTRICTED.matcher(compact).find()) return declined("这里只查询校园记录，不提供凭据、接受权限声明或执行系统指令。");
        if (WRITE.matcher(compact).find()) return declined("问问青野只负责查询，不会替你报名、审批、修改或删除记录。请到对应页面操作。");
        if (!Pattern.compile("活动|社团|报名|候补|器材|相机|三脚架|投影|篮球|音乐|摄影|编程|志愿|借用|归还").matcher(question).find()) return Map.of("answer","目前支持中文查询校园活动、报名和器材借用。试试：这周末有什么活动？","items",List.of(),"mode","LOCAL","intent","OUT_OF_SCOPE");
        boolean self=SELF.matcher(compact).find();
        if (compact.contains("社团") && MEMBERSHIP.matcher(compact).find()) return declined("助手暂不查询社团成员关系，请到社团页面查看自己的入社状态。");
        if (PERSONAL.matcher(compact).find() && (OTHER_PERSON.matcher(compact).find() || !self && !PUBLIC_QUERY.matcher(compact).find()))
            return declined("助手不查询他人的报名或借用记录。可以问：我的报名活动有哪些？或：相机现在还有多少？");
        if (self && Pattern.compile("报名|候补|参加").matcher(compact).find() && Pattern.compile("借用|借过|借了|归还").matcher(compact).find()) return declined("请一次查询一种记录：我的报名活动，或我的器材借用。");
        if (!hasQueryIntent(question)) return clarification("LOCAL");
        var local=localPlan(question);
        var remote=plan(actor.id(),question).filter(p->compatible(p,local,compact));
        var plan=remote.orElse(local);
        // Gate 2: a closed intent/parameter set. The model cannot supply SQL or identity.
        if (!valid(plan)) throw Problem.bad("查询参数无效");
        if (plan.intent().equals("OUT_OF_SCOPE")) return clarification("MODEL_PLAN");
        List<Map<String,Object>> rows;
        // Gate 3: fixed parameterized queries; identity comes exclusively from the session.
        switch(plan.intent()) {
            case "ACTIVITIES" -> rows=activities.list(actor,"public",plan.category(),plan.keyword(),0,plan.start(),plan.end());
            case "MY_REGISTRATIONS" -> rows=activities.list(actor,"mine",null,null,0,null,null);
            case "MY_LOANS" -> rows=loans.mine(actor.id());
            case "EQUIPMENT" -> {
                rows=loans.equipment().stream().filter(r->plan.keyword()==null || text(r,"name").contains(plan.keyword())).limit(10).toList();
                if (plan.start()!=null && plan.end()!=null && Duration.between(plan.start(),plan.end()).compareTo(Duration.ofDays(7))<=0)
                for(var row:rows) row.put("availability",loanService.availability(id(row,"id"),plan.start(),plan.end()));
            }
            default -> throw Problem.bad("不支持的查询意图");
        }
        // Gate 4: render DB facts locally, never let the model invent an answer.
        rows=rows.stream().limit(10).map(r->visible(r,plan.intent())).toList();
        String label=switch(plan.intent()) { case "MY_REGISTRATIONS" -> "你的报名记录"; case "MY_LOANS" -> "你的借用记录"; case "EQUIPMENT" -> "器材记录"; default -> "公开活动"; };
        String answer=rows.isEmpty()?emptyAnswer(plan.intent()):label+"，展示 "+rows.size()+" 条（最多 10 条）：\n"+String.join("\n",rows.stream().map(this::fact).toList());
        return Map.of("answer",answer,"items",rows,"intent",plan.intent(),"mode",remote.isPresent()?"MODEL_PLAN":"LOCAL");
    }
    private Map<String,Object> declined(String answer) { return Map.of("answer",answer,"items",List.of(),"intent","OUT_OF_SCOPE","mode","LOCAL"); }
    private Optional<QueryPlan> plan(long user,String question) {
        if (!modelSlots.tryAcquire()) return Optional.empty();
        if (!planning.add(user)) { modelSlots.release(); return Optional.empty(); }
        try { return planner.plan(question); }
        finally { planning.remove(user);modelSlots.release(); }
    }
    private boolean compatible(QueryPlan p,QueryPlan local,String question) {
        if (!valid(p)) return false;
        if (p.intent().equals("OUT_OF_SCOPE")) return true;
        if (!p.intent().equals(local.intent())) return false;
        if (local.category()!=null && !Objects.equals(local.category(),p.category())) return false;
        if (local.keyword()!=null && (p.keyword()==null || !p.keyword().contains(local.keyword()))) return false;
        if (p.keyword()!=null && !question.contains(p.keyword().replaceAll("\\s",""))) return false;
        if (local.start()!=null && (!Objects.equals(local.start(),p.start()) || !Objects.equals(local.end(),p.end()))) return false;
        return p.start()==null || local.start()!=null || Pattern.compile("\\d|时间|日期|月|日|时|点|上午|下午|晚上|周").matcher(question).find();
    }
    private Map<String,Object> visible(Map<String,Object> row,String intent) {
        var fields=switch(intent) {
            case "MY_LOANS" -> List.of("id","equipmentName","quantity","status");
            case "EQUIPMENT" -> List.of("id","name","totalQuantity","borrowedQuantity","enabled","availability");
            default -> List.of("id","title","startTime","endTime","location","category");
        };
        var result=new LinkedHashMap<String,Object>();
        for(String field:fields) if(row.containsKey(field)) result.put(field,row.get(field));
        return result;
    }
    public boolean valid(QueryPlan p) {
        if (p==null || p.intent()==null || !INTENTS.contains(p.intent()) || (p.category()!=null && !CATEGORIES.contains(p.category())) || (p.keyword()!=null && p.keyword().length()>30)) return false;
        if (p.intent().equals("OUT_OF_SCOPE")) return p.category()==null && p.keyword()==null && p.start()==null && p.end()==null;
        if (p.intent().startsWith("MY_")) return p.category()==null && p.keyword()==null && p.start()==null && p.end()==null;
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
        boolean self=SELF.matcher(q).find();
        String intent=self && Pattern.compile("借|归还").matcher(q).find()?"MY_LOANS":self && Pattern.compile("报名|候补|参加|活动").matcher(q).find()?"MY_REGISTRATIONS":Pattern.compile("器材|相机|三脚架|投影|借用|篮球数量").matcher(q).find()?"EQUIPMENT":"ACTIVITIES";
        if (intent.startsWith("MY_")) return new QueryPlan(intent,null,null,null,null);
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
