package cn.qingye;
import cn.qingye.integration.*;
import cn.qingye.business.AssistantService;
import cn.qingye.db.ActivityStore;
import cn.qingye.db.LoanStore;
import cn.qingye.business.LoanService;
import cn.qingye.model.Actor;
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
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class IntegrationBoundaryTest {
    @Test void explicitModelClarificationIsNotReplacedByACanonicalFallback() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        byte[] body=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason","stop","message",
                Map.of("content","{\"action\":\"CLARIFY\",\"intent\":null,\"entity\":null,\"category\":null,\"timeOption\":null,\"reason\":\"MISSING_INFO\"}")))));
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/plan",exchange->{
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200,body.length);
            try(var output=exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var clock=Clock.systemUTC();
            var planner=new LlmPlanner(new ExternalHttp(json),json,clock,"http://127.0.0.1:"+server.getAddress().getPort()+"/plan","fixture-only","test-model");
            var activities=mock(ActivityStore.class);
            var loans=mock(LoanStore.class);
            var service=new AssistantService(activities,loans,mock(LoanService.class),planner,clock);
            var student=new Actor(3,"student",false);
            var questions=List.of("有哪些校园活动？","查相机","我的报名活动有哪些？","我的借用器材有哪些？");
            var responses=questions.stream().map(question->service.ask(student,question)).toList();
            System.out.println("MODEL_STUB_RESPONSE_BYTES="+new String(body,StandardCharsets.UTF_8));
            System.out.println("MODEL_STUB_QUERY_RESULTS="+json.writeValueAsString(responses));
            assertThat(responses).allSatisfy(response->assertThat(response).containsEntry("status","CLARIFY").containsEntry("mode","MODEL_PLAN"));
            verifyNoInteractions(loans,activities);
        } finally { server.stop(0); }
    }
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
        var payload=new AtomicReference<>("{\"action\":\"QUERY\",\"intent\":\"EQUIPMENT\",\"entity\":\"相机\",\"category\":null,\"timeOption\":\"CURRENT\",\"reason\":null}");
        var request=new AtomicReference<Map<?,?>>();
        var status=new AtomicReference<>(200);
        var finish=new AtomicReference<>("stop");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/plan",exchange-> {
            request.set(json.readValue(exchange.getRequestBody(),Map.class));
            byte[] body=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason",finish.get(),"message",Map.of("content",payload.get())))));
            exchange.sendResponseHeaders(status.get(),body.length);
            try(var output=exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var planner=new LlmPlanner(new ExternalHttp(json),json,Clock.systemUTC(),"http://127.0.0.1:"+server.getAddress().getPort()+"/plan","test-fixture","deepseek-flash");
            assertThat(planner.plan("查相机器材")).hasValueSatisfying(plan->assertThat(plan.entity()).isEqualTo("相机"));
            assertThat(request.get().get("response_format")).isEqualTo(Map.of("type","json_object"));
            assertThat(request.get().get("thinking")).isEqualTo(Map.of("type","disabled"));
            assertThat(request.get().get("max_tokens")).isEqualTo(300);
            for(String invalid:List.of(
                "{\"intent\":\"EQUIPMENT\",\"sql\":\"SELECT * FROM app_user\"}",
                "{\"intent\":\"MY_LOANS\",\"userId\":2}",
                "{\"intent\":\"EQUIPMENT\",\"answer\":\"相机999台\"}",
                "{\"intent\":\"EQUIPMENT\",\"keyword\":123}",
                "{\"intent\":\"EQUIPMENT\",\"keyword\":[\"相机\"]}",
                "{\"intent\":\"EQUIPMENT\",\"start\":\"not-a-date\"}",
                "{\"intent\":\"EQUIPMENT\",\"intent\":\"MY_LOANS\"}",
                "{\"intent\":\"EQUIPMENT\"} {\"sql\":\"DROP TABLE loan\"}",
                "[]","null","","this is not JSON")) {
                payload.set(invalid);
                assertThat(planner.plan("查相机器材").map(plan->plan.action().equals("QUERY")).orElse(false)).as(invalid).isFalse();
            }
            payload.set("{\"action\":\"QUERY\",\"intent\":\"MY_LOANS\",\"entity\":\"相机\",\"category\":null,\"timeOption\":\"TODAY\",\"reason\":null}");
            assertThat(planner.plan("我的相机借用")).hasValueSatisfying(plan->assertThat(plan.action()).isEqualTo("CLARIFY"));
            payload.set("{\"action\":\"QUERY\",\"intent\":\"MY_LOANS\",\"entity\":null,\"category\":null,\"timeOption\":\"ANY\",\"reason\":null,\"userId\":99}");
            assertThat(planner.plan("我的借用")).hasValueSatisfying(plan->assertThat(plan.action()).isEqualTo("REJECT"));
            payload.set("{\"intent\":\"EQUIPMENT\"}");
            finish.set("length");
            assertThat(planner.plan("查相机器材")).isEmpty();
            finish.set("stop");
            status.set(503);
            assertThat(planner.plan("查相机器材")).isEmpty();
            server.stop(0);
            assertThat(planner.plan("查相机器材")).isEmpty();
        }
        finally {
            server.stop(0);
        }
    }
    @Test void modelTimeoutFallsBackWithinTheClientDeadline() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.createContext("/slow",exchange-> {
            try { Thread.sleep(6000); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var json=new ObjectMapper().findAndRegisterModules();
            var planner=new LlmPlanner(new ExternalHttp(json),json,Clock.systemUTC(),"http://127.0.0.1:"+server.getAddress().getPort()+"/slow","test-fixture","test-model");
            long started=System.nanoTime();
            assertThat(planner.plan("查相机器材")).isEmpty();
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isBetween(Duration.ofSeconds(4),Duration.ofMillis(6500));
        }
        finally { server.stop(0);executor.shutdownNow(); }
    }
    @Test void modelResponseHeadersCannotBypassTheCompleteBodyDeadline() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newSingleThreadExecutor();server.setExecutor(executor);
        server.createContext("/stalled-body",exchange->{
            try {
                exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,0);
                exchange.getResponseBody().write(' ');exchange.getResponseBody().flush();Thread.sleep(7000);
            } catch(Exception ignored) { }
            finally { exchange.close(); }
        });server.start();
        try {
            var json=new ObjectMapper().findAndRegisterModules();
            var planner=new LlmPlanner(new ExternalHttp(json),json,Clock.systemUTC(),"http://127.0.0.1:"+server.getAddress().getPort()+"/stalled-body","test-fixture","test-model");
            long started=System.nanoTime();assertThat(planner.plan("查相机")).isEmpty();
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofMillis(6500));
        } finally { server.stop(0);executor.shutdownNow(); }
    }
}
