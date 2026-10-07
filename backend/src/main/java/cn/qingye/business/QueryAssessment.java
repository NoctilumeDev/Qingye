package cn.qingye.business;

import cn.qingye.model.QueryPlan;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded evidence about one current request. Unknown input has no automatic fallback. */
final class QueryAssessment {
    private static final List<String> EQUIPMENT = List.of(
        "相机充电器", "摄像机", "摄像头", "投影仪", "三脚架", "开发板", "相机", "手机");
    private static final Pattern QUERY = Pattern.compile(
        "查(?!了|过)|搜(?!了|过)|找(?!了|过)|看(?!了|过|完|见|到|着)|推荐|列出|有哪些|有什么|有啥|有没有|还有|多少|哪些|哪里|在哪|能(?:不能)?借|能否|是否|什么|有.{0,12}吗");
    private static final Pattern PAST = Pattern.compile(
        "(?:看|查|搜|借|归还|还|参加|报名)(?:了|过)|(?:已经|之前|曾经|刚才|刚|昨天|上周).*(?:借用|归还|参加|报名|查看)");
    private static final Pattern NEGATED = Pattern.compile(
        "不查|别查|不要查|不用查|不用管|先不管|不看|别看|不要看|不用看|不查询|不搜索|撤销");
    private static final Pattern REVISION = Pattern.compile(
        "算了|等等|不对|改查|改成|最后|最终|只查|只看|仅查|仅看|直接查|直接看");
    private static final Pattern BOUNDARY = Pattern.compile(
        "[,，。！？?；;:：…→]+|(?=现在|最后|最终|算了|等等|不对|改查|改成|又改成|直接查|直接看|只查|只看|仅查|仅看)");
    private static final Pattern SELF = Pattern.compile(
        "我的|本人|我(?:今天|昨天|之前|已经|曾经|现在|有没有|是否|已|都)?(?:报名|候补|参加|借用|借过|借了|归还)");
    private static final Pattern GENERIC_EQUIPMENT = Pattern.compile(
        "(?:请|帮我|给我|麻烦|现在|只|仅|直接)*(?:查(?:询|一下)?|查看|看看?|有哪些|有什么|有啥)?(?:全部|所有|可用)?(?:器材|设备)(?:库存|数量|记录)?(?:有哪些|有什么|多少|吗)?");
    private static final Pattern ACTIVITY_SHORTCUT = Pattern.compile(
        "(?:今天|明天|这周末|本周末|这周|本周|周末)?(?:校园|社团|运动|篮球|摄影|音乐|编程|科技|志愿)?活动");
    private static final Pattern RECORD_SHORTCUT = Pattern.compile(
        "(?:我的|本人)(?:报名(?:活动|记录)?|器材借用|借用(?:器材|记录)?|候补(?:活动|记录)?)");
    private static final Pattern REQUEST_PREFIX = Pattern.compile(
        "(?:请|帮我|给我|麻烦|我想|想|现在|只|仅|直接)*(?:查(?:一下|询)?|查看|看看?)");

    final String request;
    final Set<String> allowedIntents;
    final Set<String> entities;
    final Set<String> excludedEntities;
    final Optional<QueryPlan> fallback;
    final QueryPlan constraints;
    final boolean requiresKeyword;

    private QueryAssessment(String request, Set<String> intents, Set<String> entities,
                            Set<String> excluded, Optional<QueryPlan> fallback, QueryPlan constraints, boolean requiresKeyword) {
        this.request = request;
        this.allowedIntents = Set.copyOf(intents);
        this.entities = Set.copyOf(entities);
        this.excludedEntities = Set.copyOf(excluded);
        this.fallback = fallback;
        this.constraints = constraints;
        this.requiresKeyword = requiresKeyword;
    }

    static QueryAssessment assess(String input, Clock clock) {
        var candidates = new ArrayList<String>();
        var excluded = new HashSet<String>();
        String context = "";
        String hint = "";
        String requestPrefix = "";
        for (String part : BOUNDARY.split(input)) {
            String clause = part.strip();
            if (clause.isEmpty()) continue;
            if (clause.matches("(器材|设备|活动|校园)查询")) {
                hint = clause.startsWith("器材") || clause.startsWith("设备") ? "EQUIPMENT" : "ACTIVITIES";
                continue;
            }
            if (NEGATED.matcher(clause).find()) {
                var removed = names(clause);
                excluded.addAll(removed);
                if (removed.isEmpty()) candidates.clear();
                else candidates.removeIf(previous -> !Collections.disjoint(names(previous), removed));
                context = "";
                requestPrefix = "";
                continue;
            }
            if (REVISION.matcher(clause).find()) {
                candidates.clear();
                context = "";
                requestPrefix = "";
            }
            boolean query = QUERY.matcher(clause).find();
            if (!query) {
                if (!PAST.matcher(clause).find()
                        && (EQUIPMENT.contains(clause) || clause.startsWith("就是") || ACTIVITY_SHORTCUT.matcher(clause).matches())) {
                    context = clause;
                } else if (!PAST.matcher(clause).find() && names(clause).size()==1) {
                    String target = names(clause).iterator().next();
                    if (clause.replace(target, "").matches("[\\d\\s]+")) context = target;
                }
                continue;
            }
            // '现在' can modify an unfinished request verb; it is not always a new request.
            if (REQUEST_PREFIX.matcher(clause.replaceAll("\\s", "")).matches()) {
                requestPrefix = clause;
                continue;
            }
            if (!requestPrefix.isEmpty()) {
                clause = requestPrefix + clause;
                requestPrefix = "";
            }
            // A bare verb/pronoun may refer to the last explicit topic, never to discarded history.
            if (names(clause).isEmpty() && !clause.contains("活动") && !clause.contains("器材")
                    && !SELF.matcher(clause).find() && !context.isEmpty()) {
                clause = context + " " + clause;
            } else if (hint.equals("EQUIPMENT") && names(clause).isEmpty() && !clause.contains("器材")) {
                clause = "器材 " + clause;
            }
            excluded.removeAll(names(clause));
            candidates.add(clause);
        }
        if (!requestPrefix.isEmpty() && !context.isEmpty()) candidates.add(context + " " + requestPrefix);
        if (candidates.isEmpty()) {
            String compact = input.replaceAll("[\\s，。！？,.!?]", "");
            if (EQUIPMENT.contains(compact) || ACTIVITY_SHORTCUT.matcher(compact).matches()
                    || GENERIC_EQUIPMENT.matcher(compact).matches()
                    || RECORD_SHORTCUT.matcher(compact).matches()) candidates.add(compact);
        }
        var distinct = new LinkedHashMap<QueryPlan,String>();
        boolean unresolved = false;
        for (String candidate : candidates) {
            var plan = evidence(candidate, clock);
            if (plan == null) unresolved = true;
            else distinct.putIfAbsent(plan, candidate);
        }
        // Two retained requests are not permission to pick one. An unsupported retained request counts too.
        if (unresolved || distinct.size()!=1) return unclear(input, excluded);
        var entry = distinct.entrySet().iterator().next();
        var plan = entry.getKey();
        var active = entry.getValue();
        var entities = names(active);
        boolean generic = GENERIC_EQUIPMENT.matcher(active.replaceAll("\\s", "")).matches();
        boolean unknownObject = plan.intent().equals("EQUIPMENT") && entities.isEmpty() && !generic;
        return new QueryAssessment(active, Set.of(plan.intent()), entities, excluded,
            unknownObject ? Optional.empty() : Optional.of(plan), plan, unknownObject);
    }

    private static QueryAssessment unclear(String input, Set<String> excluded) {
        return new QueryAssessment(input, Set.of(), Set.of(), excluded, Optional.empty(), null, false);
    }

    private static QueryPlan evidence(String q, Clock clock) {
        boolean self = SELF.matcher(q).find();
        boolean loan = Pattern.compile("借用|借过|借了|归还|借出").matcher(q).find();
        boolean registration = Pattern.compile("报名|候补|参加").matcher(q).find();
        var targets = names(q);
        if (targets.size()>1 || self && loan && registration) return null;
        String intent;
        if (self && loan) intent = "MY_LOANS";
        else if (self && registration) intent = "MY_REGISTRATIONS";
        else if (q.contains("活动") || ACTIVITY_SHORTCUT.matcher(q).matches()
                || Pattern.compile("报名(?:人数|名额|截止|条件|规则|流程|时间)").matcher(q).find()) intent = "ACTIVITIES";
        else if (!targets.isEmpty() || q.contains("器材") || q.contains("设备")) intent = "EQUIPMENT";
        else return null;
        if (intent.startsWith("MY_")) {
            // These fixed queries do not implement per-device or date filtering.
            if (!targets.isEmpty() || Pattern.compile("今天|昨天|上周|本周|最近|日期").matcher(q).find()) return null;
            return new QueryPlan(intent, null, null, null, null);
        }
        String category = null;
        if (intent.equals("ACTIVITIES")) {
            var categories = new HashSet<String>();
            if (Pattern.compile("运动|篮球|足球|跑步").matcher(q).find()) categories.add("SPORT");
            if (Pattern.compile("摄影|音乐|艺术").matcher(q).find()) categories.add("ART");
            if (Pattern.compile("编程|科技|开发").matcher(q).find()) categories.add("TECH");
            if (q.contains("志愿")) categories.add("VOLUNTEER");
            if (categories.size()>1) return null;
            if (!categories.isEmpty()) category = categories.iterator().next();
        }
        String keyword = targets.isEmpty() ? null : targets.iterator().next();
        LocalDateTime start = null, end = null;
        var today = LocalDate.now(clock);
        if (q.contains("周末")) {
            var saturday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(5);
            start = saturday.atStartOfDay(); end = saturday.plusDays(2).atStartOfDay();
            if (start.isBefore(LocalDateTime.now(clock))) start = LocalDateTime.now(clock);
        } else if (q.contains("明天")) {
            start = today.plusDays(1).atStartOfDay(); end = start.plusDays(1);
        } else if (q.contains("今天")) {
            start = today.atStartOfDay(); end = start.plusDays(1);
        } else if (q.contains("这周") || q.contains("本周")) {
            start = LocalDateTime.now(clock); end = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay();
        }
        return new QueryPlan(intent, category, keyword, start, end);
    }

    private static Set<String> names(String text) {
        var result = new LinkedHashSet<String>();
        String remaining = text;
        // Longest names are consumed first, so an accessory is not silently rewritten as its base device.
        for (String name : EQUIPMENT) {
            if (remaining.contains(name)) {
                result.add(name);
                remaining = remaining.replace(name, "");
            }
        }
        return result;
    }
}
