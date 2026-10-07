package cn.qingye.model;

import java.util.Set;

/** A proposal for one closed capability, never a user identity, SQL or answer. */
public record QueryPlan(String action, String intent, String entity, String category,
                        String timeOption, String reason) {
    public static final Set<String> FIELDS=Set.of("action","intent","entity","category","timeOption","reason");
    private static final Set<String> INTENTS=Set.of("ACTIVITIES","EQUIPMENT","MY_REGISTRATIONS","MY_LOANS");
    private static final Set<String> CATEGORIES=Set.of("SPORT","ART","TECH","VOLUNTEER","OTHER");
    private static final Set<String> TIMES=Set.of("ANY","CURRENT","TODAY","TOMORROW","THIS_WEEK","WEEKEND");
    private static final Set<String> CLARIFICATIONS=Set.of("MISSING_INFO","MULTIPLE_REQUESTS","UNKNOWN_ENTITY","UNSUPPORTED_FILTER");
    private static final Set<String> REJECTIONS=Set.of("OUTSIDE_DOMAIN","PRIVATE_DATA","WRITE_OPERATION","IDENTITY_CLAIM","UNSUPPORTED_CAPABILITY","INVALID_PROTOCOL");

    public static QueryPlan query(String intent,String entity,String category,String timeOption) {
        return new QueryPlan("QUERY",intent,entity,category,timeOption,null);
    }
    public static QueryPlan clarify(String reason) { return new QueryPlan("CLARIFY",null,null,null,null,reason); }
    public static QueryPlan reject(String reason) { return new QueryPlan("REJECT",null,null,null,null,reason); }

    public boolean valid() {
        if ("CLARIFY".equals(action) || "REJECT".equals(action)) {
            return intent==null && entity==null && category==null && timeOption==null && reason!=null
                && (action.equals("CLARIFY")?CLARIFICATIONS:REJECTIONS).contains(reason);
        }
        if (!"QUERY".equals(action) || intent==null || !INTENTS.contains(intent)
                || reason!=null || timeOption==null || !TIMES.contains(timeOption)) return false;
        if (entity!=null && (entity.isBlank() || entity.length()>30 || !entity.equals(entity.strip()))) return false;
        if (intent.startsWith("MY_")) return entity==null && category==null && timeOption.equals("ANY");
        if (intent.equals("EQUIPMENT")) return entity!=null && category==null
            && Set.of("CURRENT","TODAY","TOMORROW","WEEKEND").contains(timeOption);
        return (category==null || CATEGORIES.contains(category)) && !"ALL".equals(entity) && !"CURRENT".equals(timeOption);
    }
}
