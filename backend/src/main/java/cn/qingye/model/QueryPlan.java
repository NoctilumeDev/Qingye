package cn.qingye.model;

import java.util.*;

/** A proposal for one closed capability, never a user identity, SQL or answer. */
public record QueryPlan(String action, String intent, String entity, String category,
                        String timeOption, String date, String status, String location, String reason,List<QueryPlan> queries) {
    public static final Set<String> FIELDS=Set.of("action","intent","entity","category","timeOption","date","status","location","reason","queries");
    public QueryPlan { queries=queries==null?null:List.copyOf(queries); }
    public QueryPlan(String action,String intent,String entity,String category,String timeOption,String date,String status,String location,String reason) {
        this(action,intent,entity,category,timeOption,date,status,location,reason,null);
    }

    public static QueryPlan query(String intent,String entity,String category,String timeOption) {
        return new QueryPlan("QUERY",intent,entity,category,timeOption,null,"ANY",null,null);
    }
    public static QueryPlan batch(List<QueryPlan> queries) { return new QueryPlan("QUERY","BATCH",null,null,null,null,null,null,null,queries); }
    public boolean batch() { return "QUERY".equals(action)&&"BATCH".equals(intent); }

    public boolean valid() {
        if(batch()) return entity==null&&category==null&&timeOption==null&&date==null&&status==null&&location==null&&reason==null
            &&queries!=null&&queries.size()>=2&&queries.size()<=AssistantContract.MAX_QUERIES
            &&queries.stream().allMatch(q->q!=null&&q.queries()==null&&"QUERY".equals(q.action())&&q.valid());
        boolean draft="CLARIFY".equals(action);
        if(!draft&&!"QUERY".equals(action)) return false;
        if(draft ? !AssistantContract.CLARIFICATIONS.contains(Objects.toString(reason,"")) : reason!=null) return false;
        if(queries!=null) return draft&&(intent==null||intent.equals("BATCH"))&&entity==null&&category==null&&timeOption==null&&date==null&&status==null&&location==null&&!queries.isEmpty()&&queries.size()<=AssistantContract.MAX_QUERIES
            &&queries.stream().allMatch(q->q!=null&&q.queries()==null&&q.valid());
        if(intent!=null&&!AssistantContract.INTENTS.contains(intent)||!draft&&intent==null) return false;
        if (entity!=null && (entity.isBlank() || entity.length()>80 || !entity.equals(entity.strip()) || entity.codePoints().anyMatch(Character::isISOControl))) return false;
        if(timeOption!=null&&!AssistantContract.TIMES.contains(timeOption)||!draft&&timeOption==null)return false;
        if(timeOption!=null&&intent!=null&&!AssistantContract.times(intent).contains(timeOption))return false;
        if ("DATE".equals(timeOption)) {
            if(date==null&&!draft)return false;
            try { if (date!=null&&(!date.matches("(?:19|20|21)[0-9]{2}-[0-9]{2}-[0-9]{2}") || !java.time.LocalDate.parse(date).toString().equals(date))) return false; }
            catch (java.time.DateTimeException e) { return false; }
        } else if (date!=null) return false;
        if (category!=null && !AssistantContract.CATEGORIES.contains(category)) return false;
        if (location!=null && (location.isBlank() || location.length()>100 || !location.equals(location.strip()) || location.codePoints().anyMatch(Character::isISOControl))) return false;
        if (status==null&&!draft) return false;
        if(intent==null)return status==null||AssistantContract.LOAN_STATES.contains(status)||AssistantContract.REGISTRATION_STATES.contains(status);
        if(status!=null&&!AssistantContract.states(intent).contains(status))return false;
        if("MY_LOANS".equals(intent))return category==null&&location==null&&!"ALL".equals(entity);
        if("EQUIPMENT".equals(intent))return (draft||entity!=null)&&(entity==null||entity.length()<=60)&&category==null&&location==null;
        return !"ALL".equals(entity);
    }
}
