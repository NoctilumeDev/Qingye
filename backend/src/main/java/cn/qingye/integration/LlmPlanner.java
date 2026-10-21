package cn.qingye.integration;
import cn.qingye.model.QueryPlan;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
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
            String instruction=instruction(clock);
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
    public static String instruction(Clock clock) throws java.io.IOException {
        return new ClassPathResource("assistant-planner.txt").getContentAsString(StandardCharsets.UTF_8)
            +"\n当前上海时间："+LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
    }
}
