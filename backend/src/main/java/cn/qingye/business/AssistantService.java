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
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final ObjectMapper JSON=new ObjectMapper();
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
        question=normalize(question);
        if(question.isEmpty()||question.length()>300)return stopped("SYSTEM_ERROR","INVALID_REQUEST","LOCAL");
        return interpret(actor,session,requestId,List.of(question),0);
    }
    private static String normalize(String text){return text==null?"":Normalizer.normalize(text,Normalizer.Form.NFKC).replaceAll("\\p{Cf}","").strip();}
    public Map<String,Object> clarify(Actor actor,String token,String answer,String session) {
        answer=normalize(answer);
        if(answer.isEmpty()||answer.length()>300)return stopped("SYSTEM_ERROR","INVALID_REQUEST","LOCAL");
        var dialogue=confirmations.reply(actor.id(),session,token,false);
        if(dialogue.isEmpty())return stopped("CLARIFY","CONFIRMATION_EXPIRED","LOCAL");
        var d=dialogue.get();var turns=new ArrayList<>(d.turns());turns.add(answer);
        return interpret(actor,session,d.requestId(),turns,d.replies()+1);
    }
    public Map<String,Object> retry(Actor actor,String token,String session) {
        var dialogue=confirmations.reply(actor.id(),session,token,true);
        if(dialogue.isEmpty())return stopped("CLARIFY","CONFIRMATION_EXPIRED","LOCAL");
        var d=dialogue.get();return interpret(actor,session,d.requestId(),d.turns(),d.replies());
    }
    private Map<String,Object> interpret(Actor actor,String session,String requestId,List<String> turns,int replies) {
        String input=turns.get(0);
        if(turns.size()>1)try {input=JSON.writeValueAsString(Map.of("originalRequest",turns.get(0),"clarificationAnswers",turns.subList(1,turns.size())));}
        catch(java.io.IOException impossible){throw new IllegalStateException(impossible);}
        var remote=plan(actor.id(),input);
        if(remote.isEmpty()) {
            var result=new LinkedHashMap<>(stopped("SYSTEM_ERROR","MODEL_UNAVAILABLE","LOCAL"));
            confirmations.clarify(actor.id(),session,requestId,turns,replies,true).ifPresent(i->result.put("retryToken",i.token()));return result;
        }
        var proposal=remote.get();
        if(!valid(proposal))return stopped("SYSTEM_ERROR","PLAN_INVALID","MODEL_PLAN");
        if(proposal.action().equals("REJECT")) {
            if("INVALID_PROTOCOL".equals(proposal.reason()))return stopped("SYSTEM_ERROR","INVALID_PROTOCOL","MODEL_PLAN");
            if("UNSUPPORTED_CAPABILITY".equals(proposal.reason()))return stopped("UNSUPPORTED",proposal.reason(),"MODEL_PLAN");
            return stopped("REJECT",proposal.reason(),"MODEL_PLAN");
        }
        if(proposal.action().equals("CLARIFY")) {
            if("UNSUPPORTED_FILTER".equals(proposal.reason()))return stopped("UNSUPPORTED","UNSUPPORTED_FILTER","MODEL_PLAN");
            if(replies>=AssistantContract.MAX_CLARIFICATIONS)return stopped("NOT_UNDERSTOOD","NOT_UNDERSTOOD","MODEL_PLAN");
            var issued=confirmations.clarify(actor.id(),session,requestId,turns,replies,false);
            if(issued.isEmpty())return stopped("CLARIFY","CONFIRMATION_UNAVAILABLE","LOCAL");
            var result=new LinkedHashMap<>(stopped("CLARIFY",proposal.reason(),"MODEL_PLAN"));
            result.put("clarificationToken",issued.get().token());result.put("clarificationExpiresAt",issued.get().expiresAt().toString());
            result.put("draft",proposal);result.put("clarificationRound",replies+1);result.put("question",followupQuestion(proposal));return result;
        }
        if(proposal.batch()) {
            for(var q:proposal.queries())if(!timeSupported(q))return stopped("UNSUPPORTED","UNSUPPORTED_FILTER","MODEL_PLAN");
            var sections=new ArrayList<Map<String,Object>>();
            for(var q:proposal.queries())sections.add(preview(actor,session,requestId,q,"MODEL_PLAN"));
            return amendable(actor,session,requestId,turns,replies,Map.of("status","CONFIRM_SCOPE","intent","BATCH","items",List.of(),"sections",sections,"answer","共 "+sections.size()+" 项独立查询，请逐项核对范围并确认。","plan",proposal,"contractVersion",AssistantContract.VERSION));
        }
        return amendable(actor,session,requestId,turns,replies,preview(actor,session,requestId,proposal,"MODEL_PLAN"));
    }
    private Map<String,Object> amendable(Actor actor,String session,String requestId,List<String> turns,int replies,Map<String,Object> preview) {
        var result=new LinkedHashMap<>(preview);
        if("CONFIRM_SCOPE".equals(preview.get("status"))&&replies<AssistantContract.MAX_CLARIFICATIONS)
            confirmations.clarify(actor.id(),session,requestId,turns,replies,false).ifPresent(i->result.put("amendmentToken",i.token()));
        return result;
    }
    private String followupQuestion(QueryPlan p) {
        if(p.reason().equals("MISSING_TIME"))return timeQuestion(p);
        if(p.reason().equals("DATE_MEANING"))return "这里的日期指活动／计划借用时段，还是实际报名、领取或归还日期？后者目前不支持按日期筛选，可改选支持的条件。";
        if(p.reason().equals("UNKNOWN_ENTITY"))return "你指哪场活动或哪种器材？可以补充原名、社团，或明确选择全部。";
        if(p.reason().equals("MULTIPLE_REQUESTS"))return "这些请求之间需要什么关系？当前支持最多四项独立查询，不支持跨对象关联比较。";
        if(p.intent()!=null)return "已保留前面的查询方向。请补充仍不明确的名称、时间或状态；明确要全部也可以直接说全部。";
        return "你想查公开活动、器材库存、自己的报名还是自己的借用？请补充必要信息，也可以直接选择分区。";
    }
    private String timeQuestion(QueryPlan p) {
        if(p.queries()!=null) {
            var questions=new ArrayList<String>();
            for(int i=0;i<p.queries().size();i++)if(p.queries().get(i).timeOption()==null)questions.add("第 "+(i+1)+" 项："+timeQuestion(p.queries().get(i)));
            if(!questions.isEmpty())return String.join("\n",questions);
        }
        if(!AssistantContract.INTENTS.contains(Objects.toString(p.intent(),"")))return "请先说明查询公开活动、器材库存、自己的报名还是自己的借用，再补充相应时间。";
        return "查询"+AssistantContract.intentLabel(p.intent())+"的时间可选："+String.join("、",AssistantContract.timeLabels(p.intent()))+"。请补充所需时间，已有名称和状态保留。";
    }
    /** Explicit local facets remain usable when the model is unavailable or uncertain. */
    public Map<String,Object> query(Actor actor,QueryPlan plan,String session) {
        String requestId=confirmations.begin(actor.id(),session);
        if (!valid(plan) || !"QUERY".equals(plan.action())) return stopped("CLARIFY","PLAN_INVALID","FACETS");
        if(plan.batch()) {
            for(var q:plan.queries())if(!timeSupported(q))return stopped("UNSUPPORTED","UNSUPPORTED_FILTER","FACETS");
            return Map.of("status","CONFIRM_SCOPE","intent","BATCH","items",List.of(),"sections",plan.queries().stream().map(q->preview(actor,session,requestId,q,"FACETS")).toList(),"answer","请逐项核对并确认这些独立查询。","plan",plan,"contractVersion",AssistantContract.VERSION);
        }
        return preview(actor,session,requestId,plan,"FACETS");
    }
    private Map<String,Object> preview(Actor actor,String session,String requestId,QueryPlan plan,String mode) {
        var period=AssistantTime.resolve(plan,clock);
        if(!timeSupported(plan))return stopped("UNSUPPORTED","UNSUPPORTED_FILTER",mode);
        var issued=confirmations.issue(actor.id(),session,requestId,plan,period,mode,0);
        if (issued.isEmpty()) return stopped("CLARIFY","CONFIRMATION_UNAVAILABLE",mode);
        return Map.ofEntries(Map.entry("status","CONFIRM_SCOPE"),Map.entry("intent",plan.intent()),Map.entry("items",List.of()),Map.entry("mode",mode),
            Map.entry("answer",scope(plan,period)+"\n每页10条，可继续加载。未查业务记录；请核对条件后确认，不对就修改。"),
            Map.entry("interpretation","待确认的查询范围（不是对原意正确性的保证）"),Map.entry("plan",plan),
            Map.entry("confirmationToken",issued.get().token()),Map.entry("confirmationExpiresAt",issued.get().expiresAt().toString()),Map.entry("corrections",corrections()),Map.entry("contractVersion",AssistantContract.VERSION));
    }
    private boolean timeSupported(QueryPlan plan){var p=AssistantTime.resolve(plan,clock);return !plan.intent().equals("EQUIPMENT")||p.end()==null||p.end().isAfter(p.anchor())&&!p.end().isAfter(p.anchor().plusYears(1));}
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
        String answer=rows.isEmpty()?"当前已确认条件下没有匹配记录；不代表从未有过或将来不会有。":"本页展示第 "+(offset+1)+"–"+(offset+rows.size())+" 条。"+(hasMore?"还有记录，请继续加载。":"按本次读取的当前状态已到末页。");
        boolean candidates=plan.entity()!=null && !"ALL".equals(plan.entity()) && (hasMore || rows.size()>1 || offset>0);
        if(candidates) answer+="匹配到多个候选，请按编号、名称、日期和地点区分；未自动选择第一条。";
        if(plan.intent().equals("EQUIPMENT") && period.start()!=null) answer+="时段可用量按当前预约计算，不保证届时可借。";
        if(hasMore||offset>0)answer+="分页不固定业务快照，期间如有新增、取消或改期，结果可能变化；需要完整核对时请重新查询。";
        if(!rows.isEmpty()) answer+="\n"+String.join("\n",rows.stream().map(this::fact).toList());
        String next=hasMore?confirmations.issue(actor.id(),session,offer.requestId(),plan,period,mode,offset+10).map(AssistantConfirmations.Issued::token).orElse(""):"";
        if(hasMore && next.isEmpty()) answer+="下一页凭据已失效，请重新查询并确认范围。";
        var result=new LinkedHashMap<String,Object>();
        result.put("answer",answer);result.put("items",rows);result.put("status","QUERY");result.put("intent",plan.intent());result.put("mode",mode);
        result.put("interpretation",scope(plan,period));result.put("plan",plan);result.put("corrections",corrections());
        result.put("hasMore",hasMore);result.put("nextToken",next);result.put("offset",offset);result.put("selectionRequired",candidates);
        result.put("contractVersion",AssistantContract.VERSION);
        return result;
    }
    private Optional<QueryPlan> plan(long user,String question) {
        if (!modelSlots.tryAcquire()) return Optional.empty();
        if (!planning.add(user)) { modelSlots.release(); return Optional.empty(); }
        try { return planner.plan(question); }
        catch(RuntimeException unavailable){return Optional.empty();}
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
            case "MY_REGISTRATIONS" -> "我的报名（仅当前登录用户，含现已停用社团的本人记录）";
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
            case "MULTIPLE_REQUESTS" -> "请说明这些查询是否相互独立；最多支持四项独立查询，不支持关联推断。";
            case "UNSUPPORTED_FILTER" -> "当前能力不能完整表达这些条件或缺少相应事实字段，未查询，也不会忽略条件。可以修改条件，或明确选择支持的部分；这不等于原需求已完成。";
            case "CONFIRMATION_EXPIRED" -> "查询范围确认已过期、已使用或会话已变化，请重新提问并核对范围。";
            case "CONFIRMATION_UNAVAILABLE" -> "暂时无法建立范围确认，请重新提问，或使用明确的本人记录入口。";
            case "MODEL_UNAVAILABLE" -> "查询解析服务暂不可用，不是你没说清楚。可稍后重试，或直接手选分区查询。";
            case "PLAN_INVALID", "INVALID_REQUEST" -> "本次查询请求或解析结果无效，请重新发起或手选分区。";
            case "NOT_UNDERSTOOD" -> "经过澄清，我仍未能确定你的意思。本轮停止自动解析，请换种说法或手选分区。";
            case "MISSING_TIME" -> "请补充查询时间；前面的对象和条件会保留。";
            case "DATE_MEANING" -> "日期含义尚不明确，请先说明按哪种业务时间查询。";
            default -> "本次无法确定唯一查询，请换个说法，或选择下方明确的查询入口。";
        };
        String interpretation=switch(status){case "CLARIFY"->"需要补充或澄清";case "UNSUPPORTED"->"理解了需求，但当前能力不支持";case "NOT_UNDERSTOOD"->"本轮仍无法理解，已停止";case "SYSTEM_ERROR"->"系统暂时无法完成解析";default->"请求不允许执行";};
        return Map.of("answer",answer,"items",List.of(),"status",status,"intent","","reason",reason,"mode",mode,
            "interpretation",interpretation,"corrections",corrections(),"contractVersion",AssistantContract.VERSION);
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
