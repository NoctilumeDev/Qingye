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
    public static final long DEFAULT_TIMEOUT_MS=18000;
    private final Duration deadline;
    public enum TransportFailure { TIMEOUT, CONNECT, DNS, TLS, HTTP_STATUS, RESPONSE_TOO_LARGE, INTERRUPTED, IO_ERROR, CONFIGURATION, UNKNOWN }
    public static final class Unavailable extends RuntimeException {
        private static final long serialVersionUID=1L;
        private final TransportFailure category;
        public Unavailable(TransportFailure category){super(category.name());this.category=category;}
        public TransportFailure category(){return category;}
    }
    private final ExternalHttp http;
    private final ObjectMapper json;
    private final String url,key,model;
    private final Clock clock;
    public LlmPlanner(ExternalHttp http,ObjectMapper json,Clock clock,String url,String key,String model) {
        this(http,json,clock,url,key,model,DEFAULT_TIMEOUT_MS);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public LlmPlanner(ExternalHttp http,ObjectMapper json,Clock clock,@Value("${qingye.llm-url}") String url,@Value("${qingye.llm-key}") String key,@Value("${qingye.llm-model}") String model,
        @Value("${qingye.llm-timeout-ms:18000}") long timeoutMs) {
        this.deadline=Duration.ofMillis(timeoutMs);
        this.http=http;
        this.json=json;
        this.clock=clock;
        this.url=url;
        this.key=key;
        this.model=model;
    }
    public Optional<AssistantHint> locate(String question) {
        if (url.isBlank() || key.isBlank() || model.isBlank()) return Optional.empty();
        if(deadline.toMillis()<100||deadline.toMillis()>18000)throw new Unavailable(TransportFailure.CONFIGURATION);
        final String instruction;
        try { instruction=instruction(clock); }
        catch(java.io.IOException missingConfiguration){throw new Unavailable(TransportFailure.CONFIGURATION);}
        var body=new HashMap<String,Object>(Map.of("model",model,"response_format",Map.of("type","json_object"),"messages",List.of(Map.of("role","system","content",instruction),Map.of("role","user","content",question))));
        // Provider-specific transport flags do not change the shared business contract.
        if(model.equals("gpt-6-astra")||model.startsWith("gpt-6-astra-")){
            body.put("max_completion_tokens",2048);
            body.put("reasoning_effort","low");
            body.put("store",false);
        }else{body.put("temperature",0);body.put("max_tokens",350);}
        if (model.startsWith("deepseek-")) body.put("thinking",Map.of("type","disabled"));
        if (model.startsWith("qwen")) body.put("enable_thinking",false);
        final Map<?,?> response;
        try {
            response=http.post(URI.create(url),key,body,deadline);
        }catch(com.fasterxml.jackson.core.JsonProcessingException malformedEnvelope){
            throw new AssistantHintDecoder.ProtocolViolation(AssistantHintDecoder.Failure.INVALID_RESPONSE_ENVELOPE);
        }catch(Exception transportFailure){throw new Unavailable(classify(transportFailure));}
        // Decode outside the transport catch: a received, invalid reply is not a network outage.
        return Optional.of(AssistantHintDecoder.decode(json,response));
    }
    public boolean configured() {
        return !url.isBlank() && !key.isBlank() && !model.isBlank();
    }
    private static TransportFailure classify(Throwable failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()){
            if(cause instanceof java.util.concurrent.TimeoutException||cause instanceof java.net.http.HttpTimeoutException||cause instanceof java.net.SocketTimeoutException)return TransportFailure.TIMEOUT;
            if(cause instanceof java.nio.channels.UnresolvedAddressException||cause instanceof java.net.UnknownHostException)return TransportFailure.DNS;
            if(cause instanceof javax.net.ssl.SSLException)return TransportFailure.TLS;
            if(cause instanceof ExternalHttp.ResponseFailure response)return response.status()==0?TransportFailure.RESPONSE_TOO_LARGE:TransportFailure.HTTP_STATUS;
            if(cause instanceof InterruptedException){Thread.currentThread().interrupt();return TransportFailure.INTERRUPTED;}
        }
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()){
            if(cause instanceof java.net.ConnectException)return TransportFailure.CONNECT;
            if(cause instanceof java.io.IOException)return TransportFailure.IO_ERROR;
        }
        return TransportFailure.UNKNOWN;
    }
    public static String instruction(Clock clock) throws java.io.IOException {
        return new ClassPathResource("assistant-planner.txt").getContentAsString(StandardCharsets.UTF_8)
            +"\n已有业务目录与明确选项："+new ObjectMapper().writeValueAsString(AssistantContract.locatorView())
            +"\nwireProtocol（程序解析器同源的输出字段、上限、原因和完整示例；不是新增业务能力）："+new ObjectMapper().writeValueAsString(AssistantLocatorProtocol.describe());
    }
}
