package cn.qingye.integration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

@Component
public class ExternalHttp {
    // Independent wire budget: preserve the existing character limit for multibyte responses.
    private static final int MAX_RESPONSE_BYTES=1_048_576;
    private final ObjectMapper json;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    public ExternalHttp(ObjectMapper json) {
        this.json=json;
    }
    public Map<?,?> get(URI uri) throws Exception {
        return send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4)).GET().build());
    }
    public Map<?,?> post(URI uri,String key,Map<String,Object> body) throws Exception {
        return post(uri,key,body,Duration.ofSeconds(8));
    }
    public Map<?,?> post(URI uri,String key,Map<String,Object> body,Duration deadline) throws Exception {
        return send(HttpRequest.newBuilder(uri).timeout(deadline).header("Content-Type","application/json").header("Authorization","Bearer "+key).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build());
    }
    private Map<?,?> send(HttpRequest request) throws Exception {
        var pending=client.sendAsync(request,info->new BoundedBody(HttpResponse.BodyHandlers.ofString().apply(info)));
        HttpResponse<String> result;
        try { result=pending.get(request.timeout().orElse(Duration.ofSeconds(8)).toMillis(),TimeUnit.MILLISECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw e; }
        finally { if(!pending.isDone()) pending.cancel(true); }
        if (result.statusCode()!=200 || result.body().length()>100_000) throw new IllegalStateException("外部服务不可用");
        return json.readValue(result.body(),Map.class);
    }

    static final class BoundedBody implements HttpResponse.BodySubscriber<String> {
        private final HttpResponse.BodySubscriber<String> delegate;
        private Flow.Subscription subscription;
        private ByteBuffer chunk=ByteBuffer.allocate(8192);
        private int received;
        private boolean done;

        BoundedBody(HttpResponse.BodySubscriber<String> delegate) { this.delegate=delegate; }
        @Override public CompletionStage<String> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            if(done || this.subscription!=null) { subscription.cancel();return; }
            this.subscription=subscription;
            delegate.onSubscribe(subscription);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if(done) return;
            var upstream=subscription;
            if(upstream==null) {
                onError(new IllegalStateException("外部服务不可用"));
                return;
            }
            for(var buffer:buffers) {
                if(buffer.remaining()>MAX_RESPONSE_BYTES-received) {
                    done=true;
                    chunk=null;
                    upstream.cancel();
                    delegate.onError(new IllegalStateException("外部服务不可用"));
                    return;
                }
                received+=buffer.remaining();
            }
            // Compact admitted bytes: tiny network slices can retain much larger backing buffers.
            for(var buffer:buffers) {
                while(buffer.hasRemaining()) {
                    int count=Math.min(chunk.remaining(),buffer.remaining());
                    chunk.put(buffer.slice(buffer.position(),count));
                    buffer.position(buffer.position()+count);
                    if(!chunk.hasRemaining()) {
                        chunk.flip();delegate.onNext(List.of(chunk));chunk=ByteBuffer.allocate(8192);
                    }
                }
            }
        }
        @Override public void onError(Throwable error) {
            if(done) return;
            done=true;
            chunk=null;
            delegate.onError(error);
        }
        @Override public void onComplete() {
            if(done) return;
            done=true;
            if(chunk.position()>0) { chunk.flip();delegate.onNext(List.of(chunk)); }
            chunk=null;
            delegate.onComplete();
        }
    }
}
