package cn.qingye;

import cn.qingye.integration.QueryCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryCacheConcurrencyTest {
    private static final List<Map<String,Object>> ROWS=List.of(Map.of("id",1));
    private static void await(CountDownLatch latch) {
        try { if(!latch.await(3,TimeUnit.SECONDS)) throw new IllegalStateException("supplier serialized"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    @Test void databaseReadersRunInParallelWithAndWithoutCache() throws Exception {
        for(boolean enabled:new boolean[]{false,true}) {
            var redis=mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked") var values=(ValueOperations<String,String>)mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values);
            var cache=new QueryCache(redis,new ObjectMapper(),enabled);
            var entered=new CountDownLatch(2); var pool=Executors.newFixedThreadPool(2);
            try {
                var a=pool.submit(()->cache.publicList("a",()->{entered.countDown(); await(entered); return ROWS;}));
                var b=pool.submit(()->cache.publicList("b",()->{entered.countDown(); await(entered); return ROWS;}));
                assertThat(a.get(5,TimeUnit.SECONDS)).isEqualTo(ROWS);
                assertThat(b.get(5,TimeUnit.SECONDS)).isEqualTo(ROWS);
            } finally { pool.shutdownNow(); }
        }
    }
    @Test void invalidationDoesNotWaitForDatabaseAndOldQueryCannotRefillCache() throws Exception {
        var redis=mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") var values=(ValueOperations<String,String>)mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var cache=new QueryCache(redis,new ObjectMapper(),true);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try {
            var reader=pool.submit(()->cache.publicList("a",()->{entered.countDown(); await(release); return ROWS;}));
            assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            pool.submit(cache::invalidate).get(1,TimeUnit.SECONDS);
            release.countDown(); assertThat(reader.get(3,TimeUnit.SECONDS)).isEqualTo(ROWS);
            verify(values,never()).set(anyString(),anyString(),any(java.time.Duration.class));
            cache.publicList("a",()->ROWS);
            verify(values,times(1)).set(anyString(),anyString(),any(java.time.Duration.class));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
