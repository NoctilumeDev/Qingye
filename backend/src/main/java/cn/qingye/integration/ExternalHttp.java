package cn.qingye.integration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class ExternalHttp {
    private final ObjectMapper json;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    public ExternalHttp(ObjectMapper json) {
        this.json=json;
    }
    public Map<?,?> get(URI uri) throws Exception {
        return send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4)).GET().build());
    }
    public Map<?,?> post(URI uri,String key,Map<String,Object> body) throws Exception {
        return send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json").header("Authorization","Bearer "+key).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build());
    }
    private Map<?,?> send(HttpRequest request) throws Exception {
        var pending=client.sendAsync(request,HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> result;
        try { result=pending.get(request.timeout().orElse(Duration.ofSeconds(5)).toMillis(),TimeUnit.MILLISECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw e; }
        finally { if(!pending.isDone()) pending.cancel(true); }
        if (result.statusCode()!=200 || result.body().length()>100_000) throw new IllegalStateException("外部服务不可用");
        return json.readValue(result.body(),Map.class);
    }
}
