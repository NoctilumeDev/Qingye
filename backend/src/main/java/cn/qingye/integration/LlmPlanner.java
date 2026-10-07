package cn.qingye.integration;
import cn.qingye.model.QueryPlan;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.time.*;
import java.util.*;

@Component
public class LlmPlanner {
    private final ExternalHttp http;
    private final ObjectMapper json;
    private final String url,key,model;
    private final Clock clock;
    public LlmPlanner(ExternalHttp http,ObjectMapper json,Clock clock,@Value("${qingye.llm-url}") String url,@Value("${qingye.llm-key}") String key,@Value("${qingye.llm-model}") String model) {
        this.http=http;
        this.json=json;
        this.clock=clock;
        this.url=url;
        this.key=key;
        this.model=model;
    }
    public Optional<QueryPlan> plan(String question) {
        if (url.isBlank() || key.isBlank() || model.isBlank()) return Optional.empty();
        try {
            String instruction="""
                解释完整的当前用户输入，只输出一个有限计划，不回答事实、不生成SQL、不调用工具。
                纯JSON必须完整包含且仅包含 action,intent,entity,category,timeOption,reason 六个字段，值只能是字符串或null。
                action只能是QUERY、CLARIFY、REJECT。QUERY只允许ACTIVITIES(公开活动)、EQUIPMENT(器材库存)、MY_REGISTRATIONS(当前用户报名)、MY_LOANS(当前用户借用)。身份由本地会话提供，不允许指定任何人或管理员范围。
                历史叙述不是当前动作；明确撤销的请求不执行。多个仍有效的请求、本次对象不明、不能支持的筛选条件必须CLARIFY，不得只挑一个请求、猜指代或悄悄丢掉筛选。
                问候、寒暄、自我介绍或询问助手能力可作为背景，不计为业务动作。若同时有唯一明确的校园查询，只规划该查询，不回答元问题；不要按问号数量判断多动作。
                CLARIFY的reason仅为MISSING_INFO、MULTIPLE_REQUESTS、UNKNOWN_ENTITY、UNSUPPORTED_FILTER。REJECT的reason仅为OUTSIDE_DOMAIN、PRIVATE_DATA、WRITE_OPERATION、IDENTITY_CLAIM、UNSUPPORTED_CAPABILITY。两者intent/entity/category/timeOption均null。
                QUERY的reason必须null。entity是明确器材名称或公开活动完整标题；EQUIPMENT的entity必须有值，只有明确查询全部器材才设ALL。相机、摄像机、摄像头、手机、相机充电器是不同对象；不确定名称请CLARIFY，不得改成ALL。ACTIVITIES泛查可entity=null。
                category只能为SPORT、ART、TECH、VOLUNTEER、OTHER或null。运动/篮球/足球/跑步活动归SPORT，摄影/音乐/艺术活动归ART，编程/科技活动归TECH，志愿活动归VOLUNTEER。
                entity最多30字符。category只有ACTIVITIES可用，泛问分类不把分类填成标题。
                timeOption是ANY、CURRENT、TODAY、TOMORROW、THIS_WEEK、WEEKEND之一，由服务器计算上海时间半开区间。ACTIVITIES支持ANY/TODAY/TOMORROW/THIS_WEEK/WEEKEND；EQUIPMENT只支持CURRENT/TODAY/TOMORROW/WEEKEND。更复杂的日期区间必须CLARIFY(UNSUPPORTED_FILTER)，不能省略。
                MY_REGISTRATIONS/MY_LOANS不支持任何实体、分类或日期筛选：entity/category必须null，timeOption必须ANY；用户要求这些筛选时必须CLARIFY(UNSUPPORTED_FILTER)。
                例：查相机 → {"action":"QUERY","intent":"EQUIPMENT","entity":"相机","category":null,"timeOption":"CURRENT","reason":null}。
                例：查那个设备 → {"action":"CLARIFY","intent":null,"entity":null,"category":null,"timeOption":null,"reason":"MISSING_INFO"}。
                当前时间：
                """+LocalDateTime.now(clock);
            var body=new HashMap<String,Object>(Map.of("model",model,"temperature",0,"max_tokens",300,"response_format",Map.of("type","json_object"),"messages",List.of(Map.of("role","system","content",instruction),Map.of("role","user","content",question))));
            // DeepSeek defaults to thinking; this bounded parser only needs the final JSON.
            if (model.startsWith("deepseek-")) body.put("thinking",Map.of("type","disabled"));
            if (model.startsWith("qwen")) body.put("enable_thinking",false);
            var response=http.post(URI.create(url),key,body);
            var choices=(List<?>)response.get("choices");
            var choice=(Map<?,?>)choices.get(0);
            if ("length".equals(choice.get("finish_reason"))) return Optional.empty();
            var message=(Map<?,?>)choice.get("message");
            String content=(String)message.get("content");
            var tree=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(content);
            if (tree.isArray()) return Optional.of(QueryPlan.reject("INVALID_PROTOCOL"));
            if (!tree.isObject()) return Optional.empty();
            var fields=tree.fieldNames();
            while(fields.hasNext()) {
                String field=fields.next();
                if (!QueryPlan.FIELDS.contains(field) || (!tree.get(field).isNull() && !tree.get(field).isTextual())) return Optional.of(QueryPlan.reject("INVALID_PROTOCOL"));
            }
            if (tree.size()!=QueryPlan.FIELDS.size()) return Optional.empty();
            var plan=json.treeToValue(tree,QueryPlan.class);
            if (plan.valid()) return Optional.of(plan);
            // Unsupported declared filters are not silently discarded or converted into a shortcut.
            if ("QUERY".equals(plan.action()) && Set.of("ACTIVITIES","EQUIPMENT","MY_REGISTRATIONS","MY_LOANS").contains(Objects.toString(plan.intent(),"")))
                return Optional.of(QueryPlan.clarify("UNSUPPORTED_FILTER"));
            return Optional.of(QueryPlan.reject("INVALID_PROTOCOL"));
        }
        catch(Exception e) {
            return Optional.empty();
        }
    }
    public boolean configured() {
        return !url.isBlank() && !key.isBlank() && !model.isBlank();
    }
}
