package cn.qingye;
import cn.qingye.integration.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class IntegrationBoundaryTest {
    @Test void failedCacheInvalidationBypassesOldEntriesUntilTheirTtlExpires() {
        var redis=mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") var values=(ValueOperations<String,String>)mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var json=new ObjectMapper();
        var cache=new QueryCache(redis,json,true);
        var old=List.<Map<String,Object>>of(Map.of("id",1));
        var current=List.<Map<String,Object>>of(Map.of("id",2));
        assertThat(cache.publicList("equipment",()->old)).isEqualTo(old);
        when(values.get("qingye:v1:equipment")).thenReturn("[{\"id\":1}]");
        when(redis.delete(anyCollection())).thenThrow(new IllegalStateException("offline"));
        cache.invalidate();
        assertThat(cache.publicList("equipment",()->current)).isEqualTo(current);
        assertThat(cache.mode()).isEqualTo("DB_FALLBACK");
        verify(values,times(1)).get("qingye:v1:equipment");
    }
    @Test void modelHttpAdapterAcceptsOnlyQueryParametersAndDegradesOnProviderFailure() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var payload=new AtomicReference<>("{\"intent\":\"EQUIPMENT\",\"category\":null,\"keyword\":\"相机\",\"start\":null,\"end\":null}");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/plan",exchange-> {
            exchange.getRequestBody().readAllBytes();
            byte[] body=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",payload.get())))));
            exchange.sendResponseHeaders(200,body.length);
            try(var output=exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var planner=new LlmPlanner(new ExternalHttp(json),json,Clock.systemUTC(),"http://127.0.0.1:"+server.getAddress().getPort()+"/plan","test-fixture","test-model");
            assertThat(planner.plan("查相机器材")).hasValueSatisfying(plan->assertThat(plan.keyword()).isEqualTo("相机"));
            payload.set("{\"intent\":\"EQUIPMENT\",\"sql\":\"SELECT * FROM app_user\"}");
            assertThat(planner.plan("查相机器材")).isEmpty();
            payload.set("this is not JSON");
            assertThat(planner.plan("查相机器材")).isEmpty();
            server.stop(0);
            assertThat(planner.plan("查相机器材")).isEmpty();
        }
        finally {
            server.stop(0);
        }
    }
}
