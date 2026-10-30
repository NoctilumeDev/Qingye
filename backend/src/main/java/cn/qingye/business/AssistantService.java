package cn.qingye.business;
import cn.qingye.db.*;
import cn.qingye.integration.LlmPlanner;
import cn.qingye.integration.AssistantHintDecoder.ProtocolViolation;
import cn.qingye.model.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
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
    private static final ObjectMapper FACTS_JSON=new ObjectMapper().findAndRegisterModules()
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
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
        String requestId=confirmations.begin(actor==null?0:actor.id(),session);
        question=normalize(question);
        return enter(actor,session,requestId,List.of(question),List.of(),List.of(),0,requestId,1,null,false);
    }
    private static String normalize(String text){return text==null?"":java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFKC).replaceAll("\\p{Cf}", "").strip();}

    /** Every initial request, supplement and correction starts at Gate 1. */
    private Map<String,Object> enter(Actor actor,String session,String requestId,List<String> turns,List<String> questions,
        List<String> candidates,int replies,String taskId,int gateRound,QueryPlan explicit,boolean structured) {
        String rejection=AssistantIntake.reject(actor,session,turns,explicit);
        if(rejection!=null) return trace(stopped(Set.of("SESSION_REQUIRED","WRITE_OPERATION").contains(rejection)?"REJECT":
            "UNSUPPORTED_CAPABILITY".equals(rejection)?"UNSUPPORTED":"SYSTEM_ERROR",rejection,"INTAKE"),taskId,gateRound,1);
        if(requestId==null)return trace(stopped("SYSTEM_ERROR","CONFIRMATION_UNAVAILABLE","INTAKE"),taskId,gateRound,1);
        confirmations.remember(actor.id(),session,requestId,turns,questions,taskId,gateRound,explicit);
        if(gateRound>AssistantContract.MAX_GATE_ROUNDS)
            return trace(stopped("UNKNOWN","GATE_ROUND_LIMIT","LOCAL"),taskId,AssistantContract.MAX_GATE_ROUNDS,1);
        Map<String,Object> result;
        if(structured) result=structured(actor,explicit,session,requestId);
        else {
            // Only complete, registered root commands bypass the optional model.
            // A follow-up is never treated as an independent command that erases earlier conditions.
            var command=turns.size()==1&&explicit==null?AssistantContract.entryCommand(turns.get(0)):Optional.<QueryPlan>empty();
            result=command.isPresent()?executePlan(actor,session,requestId,command.get(),"BUSINESS_ENTRY"):
                interpret(actor,session,requestId,turns,questions,candidates,replies,gateRound,explicit);
        }
        return trace(result,taskId,gateRound,4);
    }
    private Map<String,Object> trace(Map<String,Object> result,String taskId,int round,int gates) {
        var response=new LinkedHashMap<>(result);
        if(taskId!=null)response.put("taskId",taskId);
        response.put("gateRound",round);response.put("maxGateRounds",AssistantContract.MAX_GATE_ROUNDS);
        response.put("flowVersion",AssistantContract.FLOW_VERSION);
        response.put("gateTrace",List.of("BUSINESS_BOUNDARY","CAPABILITY_MATCH","READ_ONLY_CONTRACT","RESULT_RELEASE").subList(0,gates));
        response.put("businessRead", "QUERY".equals(result.get("status")));
        response.put("fallbackNeeded", "GATE_ROUND_LIMIT".equals(result.get("reason")));
        return response;
    }
    public Map<String,Object> clarify(Actor actor,String token,String answer,String session) {
        answer=normalize(answer);
        if(answer.isEmpty()||answer.length()>300)return stopped("SYSTEM_ERROR","INVALID_REQUEST","LOCAL");
        var dialogue=confirmations.reply(actor.id(),session,token,false);
        if(dialogue.isEmpty())return stopped("CLARIFY","CONFIRMATION_EXPIRED","LOCAL");
        var d=dialogue.get();var turns=new ArrayList<>(d.turns());turns.add(answer);
        var c=d.context();
        return enter(actor,session,d.requestId(),turns,d.questions(),d.candidates(),d.replies()+1,c.taskId(),c.gateRound()+1,c.explicitSelection(),false);
    }
    public Map<String,Object> retry(Actor actor,String token,String session) {
        var dialogue=confirmations.reply(actor.id(),session,token,true);
        if(dialogue.isEmpty())return stopped("CLARIFY","CONFIRMATION_EXPIRED","LOCAL");
        var d=dialogue.get();var c=d.context();
        return enter(actor,session,d.requestId(),d.turns(),d.questions(),d.candidates(),d.replies(),c.taskId(),c.gateRound(),c.explicitSelection(),false);
    }
    private Map<String,Object> interpret(Actor actor,String session,String requestId,List<String> turns,List<String> questions,List<String> candidates,int replies,int gateRound,QueryPlan explicit) {
        String original=explicit==null?turns.get(0):"";
        String input=original;
        if(turns.size()>1||explicit!=null)try {
            var context=new LinkedHashMap<String,Object>();context.put("originalRequest",original);
            context.put("clarificationAnswers",explicit==null?turns.subList(1,turns.size()):turns);
            context.put("clarificationQuestions",questions);
            if(explicit!=null)context.put("explicitSelection",explicit);
            input=JSON.writeValueAsString(context);
        }
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
            String source=String.join("\n",turns);
            if(explicit!=null)try{source+="\n"+JSON.writeValueAsString(explicit);}catch(java.io.IOException impossible){throw new IllegalStateException(impossible);}
            var compiled=AssistantContract.directoryQuery(hint,source);
            if(compiled.accepted())return executePlan(actor,session,requestId,retainExplicit(compiled.query(),explicit,hint),"BUSINESS_DIRECTORY");
            return parsingFailed(actor,session,requestId,turns,questions,candidates,replies,"INVALID_PROTOCOL","BUSINESS_CONTRACT_"+compiled.failure().name());
        }
        String questionKey=hint.question()!=null?hint.question():"MULTIPLE_REQUESTS".equals(hint.reason())?"REQUEST_CHOICE":"BUSINESS_OBJECT";
        String question=AssistantContract.clarificationQuestions().get(questionKey);
        String candidate;
        try{candidate=JSON.writeValueAsString(Arrays.asList(hint.action(),hint.intent(),hint.search(),hint.reason(),hint.selections()));}
        catch(java.io.IOException impossible){throw new IllegalStateException(impossible);}
        if(gateRound>=AssistantContract.MAX_GATE_ROUNDS)return paused("GATE_ROUND_LIMIT",turns,replies,question);
        boolean repeated=false;
        for(int i=0;i<questions.size();i++)if(questions.get(i).equals(question)&&candidates.get(i).equals(candidate)){repeated=true;break;}
        if(replies>0&&("NOT_UNDERSTOOD".equals(hint.reason())||repeated))return paused("NO_CLARIFICATION_PROGRESS",turns,replies,question);
        var result=new LinkedHashMap<>(stopped("CLARIFY",hint.reason(),"MODEL_LOCATOR"));
        result.put("originalRequest",original);result.put("userTurns",List.copyOf(turns));
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
    public Map<String,Object> feedback(Actor actor,String token,String correction,String session) {
        correction=normalize(correction);
        if(correction.isEmpty()||correction.length()>300)return stopped("SYSTEM_ERROR","INVALID_REQUEST","LOCAL");
        var receipt=confirmations.feedback(actor.id(),session,token);
        if(receipt.isEmpty())return stopped("CLARIFY","CONFIRMATION_EXPIRED","LOCAL");
        var f=receipt.get();var o=f.offer();var context=o.context();
        var turns=new ArrayList<String>(context==null?List.of():context.turns());
        var questions=new ArrayList<String>(context==null?List.of():context.questions());
        String source=String.join("\n",turns);
        if(source.length()+correction.length()>8000)return stopped("CLARIFICATION_PAUSED","FEEDBACK_CONTEXT_LIMIT","LOCAL");
        // New generation, same original task and bounded cycle. External learning is outside this run.
        String requestId=confirmations.begin(actor.id(),session);
        turns.add(correction);questions.add("上次查询哪里没对上？请说明本次需要补充或改正的条件。");
        var result=new LinkedHashMap<>(enter(actor,session,requestId,turns,questions,Collections.nCopies(questions.size(),""),0,
            context==null?o.requestId():context.taskId(),context==null?2:context.gateRound()+1,
            context==null?o.plan():context.explicitSelection(),false));
        result.put("feedbackState","NOT_COLLECTED");
        return result;
    }
    private QueryPlan retainExplicit(QueryPlan query,QueryPlan explicit,AssistantHint hint) {
        if(explicit==null||!query.intent().equals(explicit.intent()))return query;
        var changed=hint.selections().stream().map(AssistantHint.Selection::field).collect(java.util.stream.Collectors.toSet());
        boolean time=changed.contains("TIME")||changed.contains("DATE");
        return new QueryPlan("QUERY",query.intent(),hint.search()==null?explicit.entity():query.entity(),
            changed.contains("CATEGORY")?query.category():explicit.category(),time?query.timeOption():explicit.timeOption(),
            time?query.date():explicit.date(),changed.contains("STATE")?query.status():explicit.status(),
            changed.contains("LOCATION")?query.location():explicit.location(),null);
    }
    private Map<String,Object> parsingFailed(Actor actor,String session,String requestId,List<String> turns,List<String> questions,List<String> candidates,int replies,String reason,String category){
        var result=new LinkedHashMap<>(stopped("MODEL_UNAVAILABLE".equals(reason)?"UNKNOWN":"SYSTEM_ERROR",reason,category==null?"LOCAL":"MODEL_LOCATOR"));
        result.put("originalRequest",turns.isEmpty()?"":turns.get(0));result.put("userTurns",List.copyOf(turns));result.put("clarificationsAnswered",replies);
        if(category!=null)result.put("protocolFailure",category);
        confirmations.clarify(actor.id(),session,requestId,turns,questions,candidates,replies,true).ifPresent(i->result.put("retryToken",i.token()));
        return result;
    }
    private Map<String,Object> paused(String reason,List<String> turns,int replies,String unresolved) {
        var result=new LinkedHashMap<>(stopped("UNKNOWN",reason,"MODEL_LOCATOR"));
        result.put("originalRequest",turns.isEmpty()?"":turns.get(0));result.put("userTurns",List.copyOf(turns));
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
        String requestId=confirmations.begin(actor==null?0:actor.id(),session);
        return enter(actor,session,requestId,List.of(),List.of(),List.of(),0,requestId,1,plan,true);
    }
    private Map<String,Object> structured(Actor actor,QueryPlan plan,String session,String requestId) {
        var incomplete=AssistantQualification.incompleteDraft(plan);
        if(incomplete.isPresent()) {
            var draft=incomplete.get();var response=new LinkedHashMap<>(stopped("CLARIFY",draft.reason(),"FACETS"));
            response.put("draft",draft);response.put("missingConditions",AssistantQualification.missing(draft));response.put("question",followupQuestion(draft));
            response.put("decisionStage","COMPLETE_CONDITIONS");return response;
        }
        if (plan==null || !plan.valid() || !"QUERY".equals(plan.action())) return stopped("CLARIFY","PLAN_INVALID","FACETS");
        if(plan.batch()) {
            for(var q:plan.queries())if(!timeSupported(q))return stopped("UNSUPPORTED","UNSUPPORTED_FILTER","FACETS");
            return Map.of("status","QUERY","intent","BATCH","items",List.of(),"sections",plan.queries().stream().map(q->executePlan(actor,session,requestId,q,"FACETS")).toList(),"answer","以下是各项查询结果。","plan",plan,"contractVersion",AssistantContract.VERSION);
        }
        return executePlan(actor,session,requestId,plan,"FACETS");
    }
    private Map<String,Object> executePlan(Actor actor,String session,String requestId,QueryPlan plan,String mode) {
        if(plan==null||!plan.valid()||!"QUERY".equals(plan.action()))return stopped("UNKNOWN","PLAN_INVALID",mode);
        var period=AssistantTime.resolve(plan,clock);
        if(!timeSupported(plan))return stopped("UNSUPPORTED","UNSUPPORTED_FILTER",mode);
        var issued=confirmations.issue(actor.id(),session,requestId,plan,period,mode,0);
        if (issued.isEmpty()) return stopped("CLARIFY","CONFIRMATION_UNAVAILABLE",mode);
        // Freeze and consume the same authorized plan internally. Querying does not require another user click.
        var offer=confirmations.consume(actor.id(),session,issued.get().token(),false);
        if(offer.isEmpty())return stopped("UNKNOWN","CONFIRMATION_UNAVAILABLE",mode);
        return execute(actor,offer.get(),session);
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
        // Gate 4 closes the selected capability with fixed SQL and the session-bound plan.
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
        // Retain only the same authorized fields already returned by the fixed query.
        try {
            String snapshot=FACTS_JSON.writeValueAsString(Map.of("plan",plan,"range",scope(plan,period),"items",rows,
                "offset",offset,"hasMore",hasMore,"observedAt",clock.instant().toString(),
                "requestContext",offer.context()==null?Map.of("entry","STRUCTURED","plan",plan):offer.context()));
            if(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=64000)
                result.put("feedbackToken",confirmations.result(offer,snapshot));
        } catch(java.io.IOException unavailable) { /* The optional receipt cannot break a completed query. */ }
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
            case "CONFIRMATION_EXPIRED" -> "这次查询已过期、已使用或登录状态已变化，请重新查询。";
            case "CONFIRMATION_UNAVAILABLE" -> "这次查询已被更新的请求替代，或暂时无法继续，请重新查询。";
            case "SESSION_REQUIRED" -> "请先登录，再查询。";
            case "GATE_ROUND_LIMIT" -> "补充后仍无法确定要查的内容，这次先停在这里。";
            case "MODEL_UNAVAILABLE" -> "模型辅助定位暂不可用；业务目录、表单和固定查询仍可使用。这不是你没说清楚。";
            case "FEEDBACK_CONTEXT_LIMIT" -> "这轮补充内容较长，请重新发起查询，或直接选择业务条件。";
            case "PLAN_INVALID", "INVALID_REQUEST" -> "本次查询请求或解析结果无效，请重新发起或手选分区。";
            case "NOT_UNDERSTOOD" -> "经过澄清，我仍未能确定你的意思。本轮停止自动解析，请换种说法或手选分区。";
            case "CLARIFICATION_LIMIT" -> "本轮已完成五次澄清，仍有必要条件未确定，已暂停自动追问，尚未查询。你可以自主重新提问或直接选择业务条件；这不表示请求被拒绝或能力不支持。";
            case "NO_CLARIFICATION_PROGRESS" -> "本轮澄清暂未取得进一步进展，已暂停自动追问，尚未查询。你可以自主重新提问或直接选择业务条件。";
            case "MISSING_TIME" -> "请补充查询时间；前面的对象和条件会保留。";
            case "DATE_MEANING" -> "日期含义尚不明确，请先说明按哪种业务时间查询。";
            default -> "本次无法确定唯一查询，请换个说法，或选择下方明确的查询入口。";
        };
        String interpretation=switch(status){case "CLARIFY"->"需要补充或澄清";case "UNKNOWN"->"目前还不能确定";case "CLARIFICATION_PAUSED"->"本轮澄清已暂停，尚未查询";case "UNSUPPORTED"->"理解了需求，但当前能力不支持";case "NOT_UNDERSTOOD"->"本轮仍无法理解，已停止";case "SYSTEM_ERROR"->"系统暂时无法完成解析";default->"请求不允许执行";};
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
