package cn.qingye.model;

import java.util.Set;

/** A proposal for one closed capability, never a user identity, SQL or answer. */
public record QueryPlan(String action, String intent, String entity, String category,
                        String timeOption, String date, String status, String location, String reason) {
    public static final Set<String> FIELDS=Set.of("action","intent","entity","category","timeOption","date","status","location","reason");
    private static final Set<String> INTENTS=Set.of("ACTIVITIES","EQUIPMENT","MY_REGISTRATIONS","MY_LOANS");
    private static final Set<String> CATEGORIES=Set.of("SPORT","ART","TECH","VOLUNTEER","OTHER");
    private static final Set<String> TIMES=Set.of("ANY","PAST","CURRENT","FUTURE","TODAY","TOMORROW","DAY_AFTER_TOMORROW","THIS_WEEK","WEEKEND","DATE");
    private static final Set<String> CLARIFICATIONS=Set.of("MISSING_INFO","MULTIPLE_REQUESTS","UNKNOWN_ENTITY","UNSUPPORTED_FILTER");
    private static final Set<String> REJECTIONS=Set.of("OUTSIDE_DOMAIN","PRIVATE_DATA","WRITE_OPERATION","IDENTITY_CLAIM","UNSUPPORTED_CAPABILITY","INVALID_PROTOCOL");

    public static QueryPlan query(String intent,String entity,String category,String timeOption) {
        return new QueryPlan("QUERY",intent,entity,category,timeOption,null,"ANY",null,null);
    }
    public static QueryPlan clarify(String reason) { return new QueryPlan("CLARIFY",null,null,null,null,null,null,null,reason); }
    public static QueryPlan reject(String reason) { return new QueryPlan("REJECT",null,null,null,null,null,null,null,reason); }

    public boolean valid() {
        if ("CLARIFY".equals(action) || "REJECT".equals(action)) {
            return intent==null && entity==null && category==null && timeOption==null && date==null && status==null && location==null && reason!=null
                && (action.equals("CLARIFY")?CLARIFICATIONS:REJECTIONS).contains(reason);
        }
        if (!"QUERY".equals(action) || intent==null || !INTENTS.contains(intent)
                || reason!=null || timeOption==null || !TIMES.contains(timeOption)) return false;
        if (entity!=null && (entity.isBlank() || entity.length()>80 || !entity.equals(entity.strip()) || entity.codePoints().anyMatch(Character::isISOControl))) return false;
        if ("DATE".equals(timeOption)) {
            try { if (date==null || !date.matches("(?:19|20|21)[0-9]{2}-[0-9]{2}-[0-9]{2}") || !java.time.LocalDate.parse(date).toString().equals(date)) return false; }
            catch (java.time.DateTimeException e) { return false; }
        } else if (date!=null) return false;
        if (category!=null && !CATEGORIES.contains(category)) return false;
        if (location!=null && (location.isBlank() || location.length()>100 || !location.equals(location.strip()) || location.codePoints().anyMatch(Character::isISOControl))) return false;
        if (status==null) return false;
        return switch(intent) {
            case "MY_LOANS" -> category==null && location==null && !"ALL".equals(entity)
                && Set.of("ANY","NON_CANCELLED","PENDING","APPROVED","REJECTED","CHECKED_OUT","RETURNED","CANCELLED").contains(status);
            case "MY_REGISTRATIONS" -> !"ALL".equals(entity)
                && Set.of("ANY","NON_CANCELLED","REGISTERED","WAITLISTED","CANCELLED").contains(status);
            case "EQUIPMENT" -> entity!=null && entity.length()<=60 && category==null && location==null && status.equals("ANY")
                && Set.of("CURRENT","TODAY","TOMORROW","DAY_AFTER_TOMORROW","THIS_WEEK","WEEKEND","DATE").contains(timeOption);
            default -> status.equals("ANY") && !"ALL".equals(entity);
        };
    }
}
