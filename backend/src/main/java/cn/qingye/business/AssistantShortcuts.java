package cn.qingye.business;

import cn.qingye.model.QueryPlan;
import java.util.*;

/** Whole-message shortcuts only. No clause extraction, keyword scan or semantic guessing. */
final class AssistantShortcuts {
    private static final Map<String,QueryPlan> REQUESTS;
    static {
        var requests=new HashMap<String,QueryPlan>();
        add(requests,QueryPlan.query("ACTIVITIES",null,null,"ANY"),"有哪些校园活动","校园有啥活动","有什么活动");
        add(requests,QueryPlan.query("ACTIVITIES",null,null,"WEEKEND"),"这周末有什么活动","本周末有什么活动");
        add(requests,QueryPlan.query("ACTIVITIES",null,null,"TODAY"),"今天有什么活动");
        add(requests,QueryPlan.query("ACTIVITIES",null,null,"TOMORROW"),"明天有什么活动");
        add(requests,QueryPlan.query("ACTIVITIES",null,"ART","ANY"),"有什么摄影活动");
        add(requests,QueryPlan.query("ACTIVITIES",null,"ART","TODAY"),"今天有什么摄影活动");
        add(requests,QueryPlan.query("MY_REGISTRATIONS",null,null,"ANY"),"我的报名","我的报名记录","我的报名活动","我的报名活动有哪些");
        add(requests,QueryPlan.query("MY_LOANS",null,null,"ANY"),"我的借用","我的借用记录","我的借用器材","我的借用器材有哪些");
        add(requests,QueryPlan.query("EQUIPMENT","ALL",null,"CURRENT"),"查器材库存","有哪些器材");
        add(requests,QueryPlan.query("EQUIPMENT","相机",null,"CURRENT"),"查相机","相机现在还有多少","有什么相机器材");
        REQUESTS=Map.copyOf(requests);
    }
    private static void add(Map<String,QueryPlan> requests,QueryPlan plan,String... phrases) {
        for (String phrase:phrases) requests.put(phrase,plan);
    }
    static Optional<QueryPlan> match(String message) {
        return Optional.ofNullable(REQUESTS.get(message.replaceAll("\\s","").replaceFirst("[。！？.!?]+$","")));
    }
    private AssistantShortcuts() {}
}
