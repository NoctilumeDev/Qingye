package cn.qingye.integration;
import cn.qingye.model.AssistantHint;
import cn.qingye.model.AssistantContract;
import cn.qingye.model.AssistantLocatorProtocol;
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
    public Optional<AssistantHint> locate(String question) {
        if (url.isBlank() || key.isBlank() || model.isBlank()) return Optional.empty();
        final String instruction;
        try { instruction=instruction(clock); }
        catch(java.io.IOException missingConfiguration){return Optional.empty();}
        var body=new HashMap<String,Object>(Map.of("model",model,"temperature",0,"max_tokens",350,"response_format",Map.of("type","json_object"),"messages",List.of(Map.of("role","system","content",instruction),Map.of("role","user","content",question))));
        // Provider-specific transport flags do not change the shared business contract.
        if (model.startsWith("deepseek-")) body.put("thinking",Map.of("type","disabled"));
        if (model.startsWith("qwen")) body.put("enable_thinking",false);
        final Map<?,?> response;
        try {
            response=http.post(URI.create(url),key,body);
        }catch(com.fasterxml.jackson.core.JsonProcessingException malformedEnvelope){
            throw new AssistantHintDecoder.ProtocolViolation(AssistantHintDecoder.Failure.INVALID_RESPONSE_ENVELOPE);
        }catch(Exception transportFailure){return Optional.empty();}
        // Decode outside the transport catch: a received, invalid reply is not a network outage.
        return Optional.of(AssistantHintDecoder.decode(json,response));
    }
    public boolean configured() {
        return !url.isBlank() && !key.isBlank() && !model.isBlank();
    }
    public static String instruction(Clock clock) throws java.io.IOException {
        return new ClassPathResource("assistant-planner.txt").getContentAsString(StandardCharsets.UTF_8)
            +"\n已有业务目录与明确选项："+new ObjectMapper().writeValueAsString(Map.of("intents",AssistantContract.INTENTS,"labels",AssistantContract.intentLabels(),"explicitSelections",AssistantContract.locatorSelections(),"querySemantics",AssistantContract.describe().get("querySemantics"),"states",AssistantContract.describe().get("states"),"timeOptionsByIntent",AssistantContract.describe().get("timeOptionsByIntent"),"maxClarifications",AssistantContract.MAX_CLARIFICATIONS,"clarificationQuestions",AssistantContract.clarificationQuestions()))
            +"\nwireProtocol（程序解析器同源的输出字段、上限、原因和完整示例；不是新增业务能力）："+new ObjectMapper().writeValueAsString(AssistantLocatorProtocol.describe());
    }
}
