package cn.qingye.model;

import java.util.*;

/** Structural completeness only: this cannot prove coverage of a natural-language request. */
public final class AssistantQualification {
    public record MissingCondition(int queryIndex,String field,String label,List<String> choices) {}
    private AssistantQualification() {}

    /** Preserve supported values exactly; a null is never filled with ANY or a guessed object. */
    public static Optional<QueryPlan> incompleteDraft(QueryPlan candidate) {
        if(candidate==null||!"QUERY".equals(candidate.action())||candidate.valid()||candidate.reason()!=null)return Optional.empty();
        if("BATCH".equals(candidate.intent())) {
            if(candidate.entity()!=null||candidate.category()!=null||candidate.timeOption()!=null||candidate.date()!=null||candidate.status()!=null||candidate.location()!=null
                ||candidate.queries()==null||candidate.queries().size()<2||candidate.queries().size()>AssistantContract.MAX_QUERIES)return Optional.empty();
            var children=new ArrayList<QueryPlan>();boolean incomplete=false;
            for(var child:candidate.queries()) {
                if(child==null||child.queries()!=null||!Set.of("QUERY","CLARIFY").contains(Objects.toString(child.action(),"")))return Optional.empty();
                if("CLARIFY".equals(child.action())) {if(!child.valid())return Optional.empty();children.add(child);incomplete=true;}
                else if(child.valid())children.add(child);
                else {var draft=incompleteDraft(child);if(draft.isEmpty())return Optional.empty();children.add(draft.get());incomplete=true;}
            }
            return incomplete?Optional.of(new QueryPlan("CLARIFY","BATCH",null,null,null,null,null,null,"MISSING_INFO",children)):Optional.empty();
        }
        if(candidate.queries()!=null)return Optional.empty();
        var draft=new QueryPlan("CLARIFY",candidate.intent(),candidate.entity(),candidate.category(),candidate.timeOption(),candidate.date(),candidate.status(),candidate.location(),"MISSING_INFO");
        if(!draft.valid())return Optional.empty();
        var missing=missing(draft);if(missing.isEmpty())return Optional.empty();
        String reason=switch(missing.get(0).field()) {case "timeOption","date" -> "MISSING_TIME";case "entity" -> "UNKNOWN_ENTITY";default -> "MISSING_INFO";};
        return Optional.of(new QueryPlan("CLARIFY",draft.intent(),draft.entity(),draft.category(),draft.timeOption(),draft.date(),draft.status(),draft.location(),reason));
    }

    public static List<MissingCondition> missing(QueryPlan plan) {
        var result=new ArrayList<MissingCondition>();
        if(plan.queries()!=null)for(int i=0;i<plan.queries().size();i++)appendMissing(plan.queries().get(i),i+1,result);
        else appendMissing(plan,1,result);
        return List.copyOf(result);
    }
    private static void appendMissing(QueryPlan p,int index,List<MissingCondition> result) {
        if(p.intent()==null){result.add(new MissingCondition(index,"intent","查询对象",AssistantContract.intentLabels()));return;}
        if(!AssistantContract.INTENTS.contains(p.intent()))return;
        var required=AssistantContract.requiredFields(p.intent());
        if(p.entity()==null&&(required.contains("entity")||"UNKNOWN_ENTITY".equals(p.reason())))
            result.add(new MissingCondition(index,"entity","具体对象或明确全部",List.of("补充名称","明确查询全部对象")));
        if(p.timeOption()==null&&required.contains("timeOption"))result.add(new MissingCondition(index,"timeOption","时间范围",AssistantContract.timeLabels(p.intent())));
        else if("DATE".equals(p.timeOption())&&p.date()==null)result.add(new MissingCondition(index,"date","具体日期",List.of("YYYY-MM-DD")));
        if(p.status()==null&&required.contains("status"))result.add(new MissingCondition(index,"status","记录状态",AssistantContract.stateLabels(p.intent())));
    }
}
