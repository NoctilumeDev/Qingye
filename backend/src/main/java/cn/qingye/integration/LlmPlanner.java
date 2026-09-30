package cn.qingye.integration;
import cn.qingye.model.QueryPlan;
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
            String instruction="只生成校园查询参数，不回答问题，不生成SQL，不调用工具。输出纯JSON，且仅含 intent,category,keyword,start,end。intent只能为ACTIVITIES、EQUIPMENT、MY_REGISTRATIONS、MY_LOANS。category只能为SPORT、ART、TECH、VOLUNTEER、OTHER或null。keyword最多30字符。start/end为Asia/Shanghai的ISO本地时间或null。无法确定时字段用null。当前时间："+LocalDateTime.now(clock);
            var response=http.post(URI.create(url),key,Map.of("model",model,"temperature",0,"max_tokens",300,"messages",List.of(Map.of("role","system","content",instruction),Map.of("role","user","content",question))));
            var choices=(List<?>)response.get("choices");
            var choice=(Map<?,?>)choices.get(0);
            var message=(Map<?,?>)choice.get("message");
            String content=(String)message.get("content");
            var tree=json.readTree(content);
            if (!tree.isObject() || tree.size()>5) return Optional.empty();
            var fields=tree.fieldNames();
            while(fields.hasNext()) if (!Set.of("intent","category","keyword","start","end").contains(fields.next())) return Optional.empty();
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
