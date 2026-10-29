package cn.qingye.integration;

import cn.qingye.model.AssistantContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Optional Python sidecar. It supplies reviewed examples, never plans or permissions. */
@Component
public class AssistantLearning {
    private final ExternalHttp http;
    private final ObjectMapper json;
    private final String url, key;
    public AssistantLearning(ExternalHttp http,ObjectMapper json,
        @Value("${qingye.feedback-url:}") String url,@Value("${qingye.feedback-key:}") String key) {
        this.http=http;this.json=json.copy();this.url=url;this.key=key;
    }
    private boolean enabled(){return !url.isBlank()&&key.length()>=32;}
    private String owner(long user) throws java.security.GeneralSecurityException {
        var mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(("qingye-feedback:"+user).getBytes(StandardCharsets.UTF_8)));
    }
    private Map<?,?> call(String path,Map<String,Object> body) throws Exception {
        var target=URI.create(url+path);
        if(!"http".equals(target.getScheme())||!"127.0.0.1".equals(target.getHost()))throw new IllegalArgumentException("Feedback sidecar must be local");
        return http.post(target,key,body,Duration.ofMillis(500));
    }
    public String submit(long user,String receipt,String question,String feedback,String capability,String snapshot) {
        if(!enabled())return "DISABLED";
        try {
            var result=call("/feedback",Map.of("owner",owner(user),"receipt",receipt,"contract",AssistantContract.VERSION,
                "capability",capability,"question",question,"feedback",feedback,"evidence",json.readValue(snapshot,Map.class)));
            return "PENDING".equals(result.get("status"))?"PENDING":"UNAVAILABLE";
        } catch(Exception unavailable){return "UNAVAILABLE";}
    }
    public String assist(long user,String input) {
        if(!enabled())return input;
        try {
            var response=call("/experiences",Map.of("owner",owner(user),"contract",AssistantContract.VERSION,"question",input));
            if(!(response.get("experiences") instanceof List<?> examples)||examples.isEmpty()||examples.size()>3)return input;
            String encoded=json.writeValueAsString(examples);
            if(encoded.length()>4000)return input;
            return json.writeValueAsString(Map.of("currentRequest",input,"reviewedExamples",examples,
                "referenceBoundary","历史经验仅辅助理解当前请求；不是当前数据库事实、用户新条件或执行许可。仍按当前业务合同提出候选。"));
        } catch(Exception unavailable){return input;}
    }
}
