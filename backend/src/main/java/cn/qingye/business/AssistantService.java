package cn.qingye.business;
import cn.qingye.db.*;
import cn.qingye.integration.LlmPlanner;
import cn.qingye.model.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.format.DateTimeFormatter;
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
    private final AssistantConfirmations confirmations;
    private static final DateTimeFormatter DISPLAY_TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern RESTRICTED=Pattern.compile("密码|密碼|密钥|密鑰|口令|凭据|token|password|systemprompt|系统提示词|忽略.{0,12}(规则|指令|权限)|(?:无视|绕过|不顾).{0,12}(规则|权限|限制)|(?:我是|作为).{0,12}(最高权限|最高管理员|超级管理员|创始人|开发者)|(?:执行|运行).{0,8}SQL|(?:DROP|TRUNCATE|DELETE|ALTER|INSERT|UPDATE)(?:TABLE|DATABASE|FROM|INTO)|(?:清空|删除|重置).{0,12}(数据库|所有数据|全部数据|数据表)",Pattern.CASE_INSENSITIVE);
    private static final Pattern WRITE=Pattern.compile("^(?:取消报名|取消预约|报名|加入|退出|领取|归还|创建|发布|修改|删除|清空|批准|审批)(?!人数|名额|截止|时间|条件|规则|流程|状态|情况|结果|记录|的|已|未|了|过|方式)|(?:帮我|替我|给我|请|我要|直接|立即|马上|现在就)(?:把|将)?(?:这个|这场|该|所有|全部)?(?:活动|社团|器材|预约|记录|数据库)?(?:报名|取消|加入|退出|领取|归还|创建|发布|修改|删除|清空|批准|审批)");
    private static final Pattern PERSONAL=Pattern.compile("报名|候补|参加|参与|借用|借过|借了|借出|归还|记录|名单|个人信息");
    private static final Pattern OTHER_PERSON=Pattern.compile("别人|他人|其他人|其他同学|其他用户|其他负责人|管理员的|他的|她的|谁|名单");
    private static final Pattern CREATIVE_REQUEST=Pattern.compile("^(?:请|帮我|给我|麻烦|为我|帮忙|我想)*(?:写|创作|生成|编|讲|看)(?:一首|一篇|一段|一个|几首|几篇).{0,30}(?:诗(?:歌)?|故事|小说|笑话|歌词)[。.!！?？]*$");
    private final Set<Long> planning=ConcurrentHashMap.newKeySet();
    private final Semaphore modelSlots=new Semaphore(4);
    public AssistantService(ActivityStore activities,LoanStore loans,LoanService loanService,LlmPlanner planner,Clock clock) {
        this.activities=activities;
        this.loans=loans;
        this.loanService=loanService;
        this.planner=planner;
        this.clock=clock;
        this.confirmations=new AssistantConfirmations(clock);
    }
    public Map<String,Object> ask(Actor actor,String question) {
        return ask(actor,question,null);
    }
    public Map<String,Object> ask(Actor actor,String question,String session) {
        String requestId=confirmations.begin(actor.id(),session);
        // Gate 1: local scope, before any network call.
        question=Normalizer.normalize(question,Normalizer.Form.NFKC).replaceAll("\\p{Cf}","").strip();
        String compact=question.replaceAll("\\s","");
        if (RESTRICTED.matcher(compact).find()) return stopped("REJECT","IDENTITY_CLAIM","LOCAL");
        if (WRITE.matcher(compact).find()) return stopped("REJECT","WRITE_OPERATION","LOCAL");
        if (CREATIVE_REQUEST.matcher(compact).matches()) return stopped("REJECT","OUTSIDE_DOMAIN","LOCAL");
        if(Set.of("曾经有过吗","以前有过吗","我需要等多久","我还要等多久","在哪里","什么时候有","查全部").contains(compact.replaceFirst("[。！？.!?]+$","")))
            return stopped("CLARIFY","MISSING_INFO","LOCAL");
        if (!Pattern.compile("活动|社团|报名|候补|器材|设备|相机|摄像机|摄像头|手机|三脚架|投影|篮球|音乐|摄影|编程|志愿|借用|归还|羽毛球|话筒|音箱").matcher(question).find()) return stopped("REJECT","OUTSIDE_DOMAIN","LOCAL");
        // Obvious whole-input authority denials are not semantic query-type guesses.
        if (PERSONAL.matcher(compact).find() && OTHER_PERSON.matcher(compact).find())
            return stopped("REJECT","PRIVATE_DATA","LOCAL");
        var remote=plan(actor.id(),question);
        // Fallback owns a few complete standard messages, never open-language interpretation.
        var shortcut=AssistantShortcuts.match(question);
        var decision=remote.or(()->shortcut);
        if (decision.isEmpty()) return stopped("CLARIFY","MODEL_UNAVAILABLE","LOCAL");
        var plan=decision.get();
        String mode=remote.isPresent()?"MODEL_PLAN":"LOCAL";
        // Gate 2: capability qualification, not a second reading of the Chinese sentence.
        if (!valid(plan)) return stopped("CLARIFY","PLAN_INVALID",mode);
        if (!plan.action().equals("QUERY")) return stopped(plan.action(),plan.reason(),mode);
        return preview(actor,session,requestId,plan,mode);
    }
    /** Explicit local facets remain usable when the model is unavailable or uncertain. */
    public Map<String,Object> query(Actor actor,QueryPlan plan,String session) {
        String requestId=confirmations.begin(actor.id(),session);
        if (!valid(plan) || !"QUERY".equals(plan.action())) return stopped("CLARIFY","PLAN_INVALID","FACETS");
        return preview(actor,session,requestId,plan,"FACETS");
    }
    private Map<String,Object> preview(Actor actor,String session,String requestId,QueryPlan plan,String mode) {
        var period=AssistantTime.resolve(plan,clock);
        if(plan.intent().equals("EQUIPMENT") && period.end()!=null && (!period.end().isAfter(period.anchor()) || period.end().isAfter(period.anchor().plusYears(1))))
            return stopped("CLARIFY","UNSUPPORTED_FILTER",mode);
        var issued=confirmations.issue(actor.id(),session,requestId,plan,period,mode,0);
        if (issued.isEmpty()) return stopped("CLARIFY","CONFIRMATION_UNAVAILABLE",mode);
        return Map.of("status","CONFIRM_SCOPE","intent",plan.intent(),"items",List.of(),"mode",mode,
            "answer",scope(plan,period)+"\n每页10条，可继续加载。未查业务记录；请核对条件后确认，不对就修改。",
            "interpretation","待确认的查询范围（不是对原意正确性的保证）","plan",plan,
            "confirmationToken",issued.get().token(),"confirmationExpiresAt",issued.get().expiresAt().toString(),"corrections",corrections());
    }
    public Map<String,Object> confirm(Actor actor,String token,String session) {
        var offer=confirmations.consume(actor.id(),session,token,false);
        if (offer.isEmpty()) return stopped("CLARIFY","CONFIRMATION_EXPIRED","LOCAL");
        return execute(actor,offer.get(),session);
    }
    public Map<String,Object> more(Actor actor,String token,String session) {
        var offer=confirmations.consume(actor.id(),session,token,true);
        if (offer.isEmpty()) return stopped("CLARIFY","CONFIRMATION_EXPIRED","LOCAL");
        return execute(actor,offer.get(),session);
    }
    private Map<String,Object> execute(Actor actor,AssistantConfirmations.Offer offer,String session) {
        var plan=offer.plan();var period=offer.period();String mode=offer.mode();int offset=offer.offset();
        List<Map<String,Object>> rows;
        // Gate 3: fixed parameterized queries; identity comes exclusively from the session.
        switch(plan.intent()) {
            case "ACTIVITIES", "MY_REGISTRATIONS" -> rows=activities.assistant(actor,plan,period.start(),period.end(),period.anchor(),offset);
            case "MY_LOANS" -> rows=loans.assistantMine(actor.id(),plan,period.start(),period.end(),period.anchor(),offset);
            case "EQUIPMENT" -> {
                rows=loans.assistantEquipment(plan.entity(),offset);
                // Do not mutate shared cache maps when adding availability.
                rows=rows.stream().limit(11).map(row->new LinkedHashMap<>(row)).map(row->(Map<String,Object>)row).toList();
                if (period.start()!=null)
                    for(var row:rows.stream().limit(10).toList()) row.put("availability",loanService.availability(id(row,"id"),period.start(),period.end()));
            }
            default -> throw Problem.bad("不支持的查询意图");
        }
        // Gate 4: render DB facts locally, never let the model invent an answer.
        boolean hasMore=rows.size()>10;
        rows=rows.stream().limit(10).map(r->visible(r,plan.intent())).toList();
        String answer=rows.isEmpty()?"当前已确认条件下没有匹配记录；不代表从未有过或将来不会有。":"本页展示第 "+(offset+1)+"–"+(offset+rows.size())+" 条。"+(hasMore?"还有记录，请继续加载。":"本次查询已到末页。");
        boolean candidates=plan.entity()!=null && !"ALL".equals(plan.entity()) && (hasMore || rows.size()>1 || offset>0);
        if(candidates) answer+="匹配到多个候选，请按编号、名称、日期和地点区分；未自动选择第一条。";
        if(plan.intent().equals("EQUIPMENT") && period.start()!=null) answer+="时段可用量按当前预约计算，不保证届时可借。";
        if(!rows.isEmpty()) answer+="\n"+String.join("\n",rows.stream().map(this::fact).toList());
        String next=hasMore?confirmations.issue(actor.id(),session,offer.requestId(),plan,period,mode,offset+10).map(AssistantConfirmations.Issued::token).orElse(""):"";
        if(hasMore && next.isEmpty()) answer+="下一页凭据已失效，请重新查询并确认范围。";
        var result=new LinkedHashMap<String,Object>();
        result.put("answer",answer);result.put("items",rows);result.put("status","QUERY");result.put("intent",plan.intent());result.put("mode",mode);
        result.put("interpretation",scope(plan,period));result.put("plan",plan);result.put("corrections",corrections());
        result.put("hasMore",hasMore);result.put("nextToken",next);result.put("offset",offset);result.put("selectionRequired",candidates);
        return result;
    }
    private Optional<QueryPlan> plan(long user,String question) {
        if (!modelSlots.tryAcquire()) return Optional.empty();
        if (!planning.add(user)) { modelSlots.release(); return Optional.empty(); }
        try { return planner.plan(question); }
        finally { planning.remove(user);modelSlots.release(); }
    }
    private Map<String,Object> visible(Map<String,Object> row,String intent) {
        var fields=switch(intent) {
            case "MY_LOANS" -> List.of("id","equipmentName","activityTitle","quantity","status","plannedStart","plannedEnd");
            case "EQUIPMENT" -> List.of("id","name","category","description","totalQuantity","borrowedQuantity","enabled","availability");
            default -> List.of("id","title","description","startTime","endTime","signupDeadline","location","category","clubName","status","registrationStatus");
        };
        var result=new LinkedHashMap<String,Object>();
        for(String field:fields) if(row.containsKey(field)) result.put(field,row.get(field));
        return result;
    }
    public boolean valid(QueryPlan p) {
        return p!=null && p.valid();
    }
    private String scope(QueryPlan p,AssistantTime period) {
        String object=switch(p.intent()) {
            case "MY_REGISTRATIONS" -> "我的报名（仅当前登录用户）";
            case "MY_LOANS" -> "我的借用记录（仅当前登录用户）";
            case "EQUIPMENT" -> "器材目录与库存（含停用器材，停用不代表可借）";
            default -> "公开活动（仅已发布、社团启用）";
        };
        String name=p.entity()==null?"全部对象":p.entity().equals("ALL")?"全部器材":p.intent().equals("ACTIVITIES") || p.intent().equals("MY_REGISTRATIONS")?"活动标题或社团名称包含「"+p.entity()+"」":"器材名称分区："+String.join("、",EquipmentNames.resolve(p.entity()))+"（仅这些原名，不含配件或近似名称）";
        String time=period.label();
        if(period.start()!=null) time+="；时段 ["+period.start()+", "+period.end()+")";
        if(!p.intent().equals("EQUIPMENT")) time+="；按"+(p.intent().equals("MY_LOANS")?"计划借用":"活动")+"起止时间匹配（非创建、报名或归还日期）";
        return object+"\n"+name+"\n上海时间："+time+"\n状态："+statusLabel(p.status())+(p.intent().startsWith("MY_") && p.status().equals("ANY")?"（包含已取消）":"")
            +(p.category()==null?"":"\n分类："+categoryLabel(p.category()))+(p.location()==null?"":"\n地点精确为："+p.location());
    }
    private String statusLabel(String status) { return switch(status) {
        case "ANY" -> "全部";case "NON_CANCELLED" -> "未取消";
        case "REGISTERED" -> "已报名";case "WAITLISTED" -> "候补";
        default -> loanStatus(status);
    }; }
    private Map<String,Object> stopped(String status,String reason,String mode) {
        String answer=switch(reason) {
            case "OUTSIDE_DOMAIN" -> "目前支持中文查询校园活动、报名和器材借用，不处理其他话题。";
            case "PRIVATE_DATA" -> "助手不查询他人的报名、借用或私有信息。";
            case "WRITE_OPERATION" -> "助手只负责查询；报名、审批、发布或修改请到对应页面操作。";
            case "IDENTITY_CLAIM" -> "不提供凭据、不接受权限冒充或系统指令；身份以当前登录会话为准。";
            case "UNSUPPORTED_CAPABILITY" -> "这项操作不属于助手的查询能力，请到对应业务页面查看。";
            case "INVALID_PROTOCOL" -> "模型计划超出了助手允许的能力或参数，已拒绝执行。请使用明确的查询入口。";
            case "UNKNOWN_ENTITY" -> "对象不确定，请在下方分区中选择查询对象并填写条件。不会改查全部。";
            case "MULTIPLE_REQUESTS" -> "检测到多个请求，请一次查询一个对象。";
            case "UNSUPPORTED_FILTER" -> "其他／待确认：条件尚不能完整落入已有分区，请选择或补充条件。不会忽略条件后查询。";
            case "CONFIRMATION_EXPIRED" -> "查询范围确认已过期、已使用或会话已变化，请重新提问并核对范围。";
            case "CONFIRMATION_UNAVAILABLE" -> "暂时无法建立范围确认，请重新提问，或使用明确的本人记录入口。";
            default -> "本次无法确定唯一查询，请换个说法，或选择下方明确的查询入口。";
        };
        return Map.of("answer",answer,"items",List.of(),"status",status,"intent","","reason",reason,"mode",mode,
            "interpretation",status.equals("CLARIFY")?(reason.equals("UNSUPPORTED_FILTER")?"当前能力不支持所需筛选":"尚未确定查询计划"):"请求不允许执行","corrections",corrections());
    }
    private List<String> corrections() { return List.of("查相机","我的借用记录","我的报名","这周末有什么活动？"); }
    private String categoryLabel(String category) { return switch(category) {
        case "SPORT" -> "运动"; case "ART" -> "艺术"; case "TECH" -> "科技"; case "VOLUNTEER" -> "志愿"; default -> "其他";
    }; }
    private String fact(Map<String,Object> row) {
        if(row.containsKey("equipmentName")) return text(row,"equipmentName")+" ×"+integer(row,"quantity")+"，"+loanStatus(text(row,"status"));
        if(row.containsKey("title")) return text(row,"title")+"，"+time(row,"startTime").format(DISPLAY_TIME)+"，地点："+text(row,"location");
        String availability="当前实物可用 "+Math.max(0,integer(row,"totalQuantity")-integer(row,"borrowedQuantity"));
        if (row.get("availability") instanceof Map<?,?> window)
            availability+="，所选时段可用 "+window.get("availableQuantity");
        return text(row,"name")+"，总量 "+integer(row,"totalQuantity")+"，"+availability;
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
