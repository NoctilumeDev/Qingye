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
                只生成校园查询参数，不回答问题，不生成SQL，不调用工具。输出纯JSON，且仅含 intent,category,keyword,start,end。
                intent只能为ACTIVITIES、EQUIPMENT、MY_REGISTRATIONS、MY_LOANS、OUT_OF_SCOPE。
                没有清楚的校园查询意图、无意义文字或无关问题，即使碰巧含相机或活动等词，也必须返回OUT_OF_SCOPE，其余四个字段全部null。不要猜测用户想查询什么。
                category只能为SPORT、ART、TECH、VOLUNTEER、OTHER或null。运动/篮球/足球/跑步活动归SPORT，摄影/音乐/艺术活动归ART，编程/科技活动归TECH，志愿活动归VOLUNTEER。
                keyword只用于用户明确指定的活动标题或器材名称，最多30字符。泛问摄影、篮球等分类活动时只设category，keyword必须为null，不能把分类当成标题搜索。
                start/end为Asia/Shanghai的ISO本地时间或null，表示半开区间[start,end)。这周末从本周六00:00到下周一00:00，不使用23:59:59。无法确定时字段用null。
                示例JSON：查相机器材 → {"intent":"EQUIPMENT","category":null,"keyword":"相机","start":null,"end":null}；有什么摄影活动 → {"intent":"ACTIVITIES","category":"ART","keyword":null,"start":null,"end":null}。
                当前时间：
                """+LocalDateTime.now(clock);
            var body=new HashMap<String,Object>(Map.of("model",model,"temperature",0,"max_tokens",300,"response_format",Map.of("type","json_object"),"messages",List.of(Map.of("role","system","content",instruction),Map.of("role","user","content",question))));
            // DeepSeek defaults to thinking; this bounded parser only needs the final JSON.
            if (model.startsWith("deepseek-")) body.put("thinking",Map.of("type","disabled"));
            var response=http.post(URI.create(url),key,body);
            var choices=(List<?>)response.get("choices");
            var choice=(Map<?,?>)choices.get(0);
            if ("length".equals(choice.get("finish_reason"))) return Optional.empty();
            var message=(Map<?,?>)choice.get("message");
            String content=(String)message.get("content");
            var tree=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(content);
            if (!tree.isObject() || tree.size()>5) return Optional.empty();
            var fields=tree.fieldNames();
            while(fields.hasNext()) {
                String field=fields.next();
                if (!Set.of("intent","category","keyword","start","end").contains(field) || (!tree.get(field).isNull() && !tree.get(field).isTextual())) return Optional.empty();
            }
            return Optional.of(json.treeToValue(tree,QueryPlan.class));
        }
        catch(Exception e) {
            return Optional.empty();
        }
    }
    public boolean configured() {
        return !url.isBlank() && !key.isBlank() && !model.isBlank();
    }
}
