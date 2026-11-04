package cn.qingye.integration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

@Component
public class QueryCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final boolean enabled;
    private volatile long bypassUntil=0;
    private volatile String mode="DISABLED";
    private final Set<String> keys=new HashSet<>();
    private long generation;
    public QueryCache(StringRedisTemplate redis,ObjectMapper json,@Value("${qingye.redis-enabled}") boolean enabled) {
        this.redis=redis;
        this.json=json;
        this.enabled=enabled;
    }
    @SuppressWarnings("unchecked")
    public List<Map<String,Object>> publicList(String key,Supplier<List<Map<String,Object>>> query) {
        if (!enabled) return query.get();
        long observed=-1;
        synchronized(this) {
            // Bound arbitrary keys. Invalidation is local; Redis remains opt-in.
            if (System.currentTimeMillis()>=bypassUntil && (keys.contains(key) || keys.size()<256)) {
                keys.add(key);
                try {
                    String cached=redis.opsForValue().get("qingye:v1:"+key);
                    mode="REDIS";
                    if (cached!=null) return json.readValue(cached,List.class);
                    observed=generation;
                }
                catch(Exception e) { degraded(); }
            }
        }
        // Database work must not serialize unrelated readers or invalidations.
        var rows=query.get();
        synchronized(this) {
            // A query started before invalidation may finish, but cannot refill the cache.
            if (observed==generation && System.currentTimeMillis()>=bypassUntil) {
                try { redis.opsForValue().set("qingye:v1:"+key,json.writeValueAsString(rows),Duration.ofSeconds(15)); }
                catch(Exception e) { degraded(); }
            }
        }
        return rows;
    }
    private void degraded() {
        mode="DB_FALLBACK";
        bypassUntil=Math.max(bypassUntil,System.currentTimeMillis()+5000);
    }
    public synchronized void invalidate() {
        generation++;
        if (!enabled || keys.isEmpty()) return;
        try {
            redis.delete(keys.stream().map(key->"qingye:v1:"+key).toList());
        }
        catch(Exception e) {
            // A failed delete must outlast the old entries' 15-second TTL.
            mode="DB_FALLBACK";
            bypassUntil=System.currentTimeMillis()+16000;
        }
    }
    public String mode() {
        return enabled?mode:"DISABLED";
    }
}
