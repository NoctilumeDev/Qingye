package cn.qingye.business;
import cn.qingye.db.*;
import cn.qingye.integration.LlmPlanner;
import cn.qingye.integration.AssistantHintDecoder.ProtocolViolation;
import cn.qingye.model.*;
import org.springframework.stereotype.Service;
import java.time.*;
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
        var command=AssistantContract.entryCommand(question);
        if(command.isPresent())return preview(actor,session,requestId,command.get(),"BUSINESS_ENTRY");
        return interpret(actor,session,requestId,List.of(question),List.of(),List.of(),0);
    }
    private static String normalize(String text){return text==null?"":Normalizer.normalize(text,Normalizer.Form.NFKC).replaceAll("\\p{Cf}","").strip();}
    public Map<String,Object> clarify(Actor actor,String token,String answer,String session) {
        answer=normalize(answer);
        if(answer.isEmpty()||answer.length()>300)return stopped("SYSTEM_ERROR","INVALID_REQUEST","LOCAL");
        var dialogue=confirmations.reply(actor.id(),session,token,false);
        if(dialogue.isEmpty())return stopped("CLARIFY","CONFIRMATION_EXPIRED","LOCAL");
        var d=dialogue.get();var turns=new ArrayList<>(d.turns());turns.add(answer);
        return interpret(actor,session,d.requestId(),turns,d.questions(),d.candidates(),d.replies()+1);
    }
    public Map<String,Object> retry(Actor actor,String token,String session) {
        var dialogue=confirmations.reply(actor.id(),session,token,true);
        if(dialogue.isEmpty())return stopped("CLARIFY","CONFIRMATION_EXPIRED","LOCAL");
        var d=dialogue.get();return interpret(actor,session,d.requestId(),d.turns(),d.questions(),d.candidates(),d.replies());
    }
    private Map<String,Object> interpret(Actor actor,String session,String requestId,List<String> turns,List<String> questions,List<String> candidates,int replies) {
        String input=turns.get(0);
        if(turns.size()>1)try {input=JSON.writeValueAsString(Map.of("originalRequest",turns.get(0),"clarificationAnswers",turns.subList(1,turns.size()),"clarificationQuestions",questions));}
        catch(java.io.IOException impossible){throw new IllegalStateException(impossible);}
        final Optional<AssistantHint> remote;
        try{remote=locate(actor.id(),input);}
        catch(ProtocolViolation failure){return parsingFailed(actor,session,requestId,turns,questions,candidates,replies,"INVALID_PROTOCOL",failure.category().name());}
        if(remote.isEmpty())return parsingFailed(actor,session,requestId,turns,questions,candidates,replies,"MODEL_UNAVAILABLE",null);
        var hint=remote.get();
        if(!hint.valid())return parsingFailed(actor,session,requestId,turns,questions,candidates,replies,"INVALID_PROTOCOL","INVALID_HINT_VALUE");
        if(hint.action().equals("CLARIFY")&&"UNSUPPORTED_FILTER".equals(hint.reason()))return stopped("UNSUPPORTED",hint.reason(),"MODEL_LOCATOR");
        if(hint.action().equals("REJECT"))return stopped("UNSUPPORTED_CAPABILITY".equals(hint.reason())?"UNSUPPORTED":"REJECT",hint.reason(),"MODEL_LOCATOR");
        boolean located=hint.action().equals("LOCATE");
        if(located){
            var compiled=AssistantContract.directoryQuery(hint,String.join("\n",turns));
            if(compiled.accepted())return preview(actor,session,requestId,compiled.query(),"BUSINESS_DIRECTORY");
            return parsingFailed(actor,session,requestId,turns,questions,candidates,replies,"INVALID_PROTOCOL","BUSINESS_CONTRACT_"+compiled.failure().name());
        }
        String questionKey=hint.question()!=null?hint.question():located?"HARD_CONDITION":"MULTIPLE_REQUESTS".equals(hint.reason())?"REQUEST_CHOICE":"BUSINESS_OBJECT";
        String question=AssistantContract.clarificationQuestions().get(questionKey);
        String candidate;
        try{candidate=JSON.writeValueAsString(Arrays.asList(hint.action(),hint.intent(),hint.search(),hint.reason(),hint.selections()));}
        catch(java.io.IOException impossible){throw new IllegalStateException(impossible);}
        if(replies>=AssistantContract.MAX_CLARIFICATIONS)return paused("CLARIFICATION_LIMIT",turns,replies,question);
        boolean repeated=false;
        for(int i=0;i<questions.size();i++)if(questions.get(i).equals(question)&&candidates.get(i).equals(candidate)){repeated=true;break;}
        if(replies>0&&("NOT_UNDERSTOOD".equals(hint.reason())||repeated))return paused("NO_CLARIFICATION_PROGRESS",turns,replies,question);
        var result=new LinkedHashMap<>(stopped("CLARIFY",located?"MISSING_INFO":hint.reason(),"MODEL_LOCATOR"));
        result.put("originalRequest",turns.get(0));result.put("userTurns",List.copyOf(turns));
        result.put("question",question);
        if(replies<AssistantContract.MAX_CLARIFICATIONS) {
            var nextQuestions=new ArrayList<>(questions);nextQuestions.add(question);
            var nextCandidates=new ArrayList<>(candidates);nextCandidates.add(candidate);
            var issued=confirmations.clarify(actor.id(),session,requestId,turns,nextQuestions,nextCandidates,replies,false);
            if(issued.isEmpty())return stopped("CLARIFY","CONFIRMATION_UNAVAILABLE","LOCAL");
            result.put("clarificationToken",issued.get().token());
        }
        result.put("clarificationRound",replies+1);
        result.put("clarificationsAnswered",replies);result.put("maxClarifications",AssistantContract.MAX_CLARIFICATIONS);
        // Unresolved suggestions cannot issue execution consent or expose business rows.
        return result;
    }
    private Map<String,Object> parsingFailed(Actor actor,String session,String requestId,List<String> turns,List<String> questions,List<String> candidates,int replies,String reason,String category){
        var result=new LinkedHashMap<>(stopped("SYSTEM_ERROR",reason,category==null?"LOCAL":"MODEL_LOCATOR"));
        result.put("originalRequest",turns.get(0));result.put("userTurns",List.copyOf(turns));result.put("clarificationsAnswered",replies);
        if(category!=null)result.put("protocolFailure",category);
        confirmations.clarify(actor.id(),session,requestId,turns,questions,candidates,replies,true).ifPresent(i->result.put("retryToken",i.token()));
        return result;
    }
    private Map<String,Object> paused(String reason,List<String> turns,int replies,String unresolved) {
        var result=new LinkedHashMap<>(stopped("CLARIFICATION_PAUSED",reason,"MODEL_LOCATOR"));
        result.put("originalRequest",turns.get(0));result.put("userTurns",List.copyOf(turns));
        result.put("unresolvedQuestion",unresolved);result.put("clarificationsAnswered",replies);result.put("maxClarifications",AssistantContract.MAX_CLARIFICATIONS);
        result.put("answer",result.get("answer")+"\n仍待明确："+unresolved);
        return result;
    }
    private String followupQuestion(QueryPlan p) {
        var missing=AssistantQualification.missing(p);
        return "请在业务表单补全："+String.join("；",missing.stream().map(m->"第 "+m.queryIndex()+" 项 "+m.label()).toList())+"。尚未查询，已有明确条件保留。";
    }
    /** Explicit local facets remain usable when the model is unavailable or uncertain. */
    public Map<String,Object> query(Actor actor,QueryPlan plan,String session) {
        String requestId=confirmations.begin(actor.id(),session);
        var incomplete=AssistantQualification.incompleteDraft(plan);
        if(incomplete.isPresent()) {
            var draft=incomplete.get();var response=new LinkedHashMap<>(stopped("CLARIFY",draft.reason(),"FACETS"));
            response.put("draft",draft);response.put("missingConditions",AssistantQualification.missing(draft));response.put("question",followupQuestion(draft));
            response.put("decisionStage","COMPLETE_CONDITIONS");return response;
        }
        if (plan==null || !plan.valid() || !"QUERY".equals(plan.action())) return stopped("CLARIFY","PLAN_INVALID","FACETS");
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
            Map.entry("confirmationToken",issued.get().token()),Map.entry("confirmationExpiresAt",issued.get().expiresAt().toString()),Map.entry("contractVersion",AssistantContract.VERSION));
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
        // Gate 4 closes the selected capability with fixed SQL and the session-bound consent.
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
        // Presentation uses only authorized DB fields, never model-generated facts.
        boolean hasMore=rows.size()>10;
        rows=rows.stream().limit(10).map(r->visible(r,plan.intent())).toList();
        String answer=rows.isEmpty()?"当前已确认条件下没有匹配记录；不代表从未有过或将来不会有。":"本页展示第 "+(offset+1)+"–"+(offset+rows.size())+" 条。"+(hasMore?"还有记录，请继续加载。":"按本次读取的当前状态已到末页。");
        var warnings=new ArrayList<String>();
        boolean candidates=plan.entity()!=null && !"ALL".equals(plan.entity()) && (hasMore || rows.size()>1 || offset>0);
        if(candidates) answer+="匹配到多个候选，请按编号、名称、日期和地点区分；未自动选择第一条。";
        if(plan.intent().equals("EQUIPMENT") && period.start()!=null) {String warning="时段可用量按当前预约计算，不保证届时可借。";answer+=warning;warnings.add(warning);}
        if(hasMore||offset>0){String warning="分页不固定业务快照，期间如有新增、取消或改期，结果可能变化；需要完整核对时请重新查询。";answer+=warning;warnings.add(warning);}
        String next=hasMore?confirmations.issue(actor.id(),session,offer.requestId(),plan,period,mode,offset+10).map(AssistantConfirmations.Issued::token).orElse(""):"";
        if(hasMore && next.isEmpty()) {String warning="下一页凭据已失效，请重新查询并确认范围。";answer+=warning;warnings.add(warning);}
        var result=new LinkedHashMap<String,Object>();
        result.put("answer",answer);result.put("items",rows);result.put("status","QUERY");result.put("intent",plan.intent());result.put("mode",mode);
        result.put("interpretation",scope(plan,period));result.put("plan",plan);
        result.put("hasMore",hasMore);result.put("nextToken",next);result.put("offset",offset);result.put("selectionRequired",candidates);result.put("warnings",List.copyOf(warnings));
        result.put("contractVersion",AssistantContract.VERSION);
        return result;
    }
    private Optional<AssistantHint> locate(long user,String question) {
        if (!modelSlots.tryAcquire()) return Optional.empty();
        if (!planning.add(user)) { modelSlots.release(); return Optional.empty(); }
        try { return planner.locate(question); }
        catch(ProtocolViolation invalid){throw invalid;}
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
    private String scope(QueryPlan p,AssistantTime period) {
        String object=switch(p.intent()) {
            case "MY_REGISTRATIONS" -> "我的报名（仅当前登录用户，含现已停用社团的本人记录）";
            case "MY_LOANS" -> "我的借用记录（仅当前登录用户）";
            case "EQUIPMENT" -> "器材目录与库存（含停用器材，停用不代表可借）";
            default -> "公开活动（仅已发布、社团启用）";
        };
        String name=p.entity()==null?"全部对象":p.entity().equals("ALL")?"全部器材":p.intent().equals("ACTIVITIES") || p.intent().equals("MY_REGISTRATIONS")?"活动标题或社团名称包含「"+p.entity()+"」":"器材名称或业务别名为「"+p.entity()+"」（不做模糊扩词）";
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
            case "INVALID_PROTOCOL" -> "模型输出未符合查询协议，本次未查询；已保留原请求和补充，可以重试解析或直接选择业务条件。";
            case "UNKNOWN_ENTITY" -> "对象不确定，请在下方分区中选择查询对象并填写条件。不会改查全部。";
            case "MULTIPLE_REQUESTS" -> "请说明这些查询是否相互独立；最多支持四项独立查询，不支持关联推断。";
            case "UNSUPPORTED_FILTER" -> "当前能力不能完整表达这些条件或缺少相应事实字段，未查询，也不会忽略条件。可以修改条件，或明确选择支持的部分；这不等于原需求已完成。";
            case "CONFIRMATION_EXPIRED" -> "查询范围确认已过期、已使用或会话已变化，请重新提问并核对范围。";
            case "CONFIRMATION_UNAVAILABLE" -> "暂时无法建立范围确认，请重新提问，或使用明确的本人记录入口。";
            case "MODEL_UNAVAILABLE" -> "模型辅助定位暂不可用；业务目录、表单和固定查询仍可使用。这不是你没说清楚。";
            case "PLAN_INVALID", "INVALID_REQUEST" -> "本次查询请求或解析结果无效，请重新发起或手选分区。";
            case "NOT_UNDERSTOOD" -> "经过澄清，我仍未能确定你的意思。本轮停止自动解析，请换种说法或手选分区。";
            case "CLARIFICATION_LIMIT" -> "本轮已完成五次澄清，仍有必要条件未确定，已暂停自动追问，尚未查询。你可以自主重新提问或直接选择业务条件；这不表示请求被拒绝或能力不支持。";
            case "NO_CLARIFICATION_PROGRESS" -> "本轮澄清暂未取得进一步进展，已暂停自动追问，尚未查询。你可以自主重新提问或直接选择业务条件。";
            case "MISSING_TIME" -> "请补充查询时间；前面的对象和条件会保留。";
            case "DATE_MEANING" -> "日期含义尚不明确，请先说明按哪种业务时间查询。";
            default -> "本次无法确定唯一查询，请换个说法，或选择下方明确的查询入口。";
        };
        String interpretation=switch(status){case "CLARIFY"->"需要补充或澄清";case "CLARIFICATION_PAUSED"->"本轮澄清已暂停，尚未查询";case "UNSUPPORTED"->"理解了需求，但当前能力不支持";case "NOT_UNDERSTOOD"->"本轮仍无法理解，已停止";case "SYSTEM_ERROR"->"系统暂时无法完成解析";default->"请求不允许执行";};
        return Map.of("answer",answer,"items",List.of(),"status",status,"intent","","reason",reason,"mode",mode,
            "interpretation",interpretation,"contractVersion",AssistantContract.VERSION);
    }
    private String categoryLabel(String category) { return switch(category) {
        case "SPORT" -> "运动"; case "ART" -> "艺术"; case "TECH" -> "科技"; case "VOLUNTEER" -> "志愿"; default -> "其他";
    }; }
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
