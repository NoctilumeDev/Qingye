package cn.qingye;
import cn.qingye.integration.*;
import cn.qingye.business.AssistantService;
import cn.qingye.db.ActivityStore;
import cn.qingye.db.LoanStore;
import cn.qingye.business.LoanService;
import cn.qingye.model.Actor;
import cn.qingye.model.AssistantHint;
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
                Map.of("content","{\"action\":\"CLARIFY\",\"intent\":null,\"search\":null,\"reason\":\"MISSING_INFO\",\"selections\":[],\"question\":\"BUSINESS_OBJECT\"}")))));
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
            var responses=questions.stream().map(question->service.ask(student,question,"s")).toList();
            System.out.println("MODEL_STUB_RESPONSE_BYTES="+new String(body,StandardCharsets.UTF_8));
            System.out.println("MODEL_STUB_QUERY_RESULTS="+json.writeValueAsString(responses));
            assertThat(responses).allSatisfy(response->assertThat(response).containsEntry("status","CLARIFY").containsEntry("mode","MODEL_LOCATOR"));
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
    @Test void locatorHttpProtocolHasNoFilterOrExecutionAuthority()throws Exception{
        var json=new ObjectMapper();var payload=new AtomicReference<>(json.writeValueAsString(AssistantHint.locate("ACTIVITIES","陌生名称")));var request=new AtomicReference<Map<?,?>>();var code=new AtomicReference<>(200);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/locate",e->{request.set(json.readValue(e.getRequestBody(),Map.class));byte[] body=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",payload.get())))));
            e.sendResponseHeaders(code.get(),body.length);try(var out=e.getResponseBody()){out.write(body);}});server.start();
        try{
            var planner=new LlmPlanner(new ExternalHttp(json),json,Clock.systemUTC(),"http://127.0.0.1:"+server.getAddress().getPort()+"/locate","fixture-only","deepseek-flash");
            assertThat(planner.locate("陌生名称在哪里")).contains(AssistantHint.locate("ACTIVITIES","陌生名称"));assertThat(request.get().get("max_tokens")).isEqualTo(350);
            assertThat(request.get().get("thinking")).isEqualTo(Map.of("type","disabled"));
            for(String field:List.of("timeOption","date","status","category","location","userId","sql","queries")){
                var extra=json.valueToTree(AssistantHint.locate("ACTIVITIES","陌生名称"));((com.fasterxml.jackson.databind.node.ObjectNode)extra).put(field,"CURRENT");payload.set(json.writeValueAsString(extra));assertThatThrownBy(()->planner.locate("在哪里")).as(field).isInstanceOf(AssistantHintDecoder.ProtocolViolation.class);
            }
            for(String bad:List.of("[]","null","{}","{\"action\":\"LOCATE\",\"action\":\"REJECT\",\"intent\":\"ACTIVITIES\",\"search\":null,\"reason\":null}","{} {}","not JSON")){
                payload.set(bad);assertThatThrownBy(()->planner.locate("在哪里")).isInstanceOf(AssistantHintDecoder.ProtocolViolation.class);
            }
            payload.set(json.writeValueAsString(AssistantHint.locate("ACTIVITIES","陌生名称")));code.set(503);assertThat(planner.locate("在哪里")).isEmpty();
        }finally{server.stop(0);}
    }
    @Test void modelTimeoutFallsBackWithinTheClientDeadline() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.createContext("/slow",exchange-> {
            try { Thread.sleep(10000); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var json=new ObjectMapper().findAndRegisterModules();
            var planner=new LlmPlanner(new ExternalHttp(json),json,Clock.systemUTC(),"http://127.0.0.1:"+server.getAddress().getPort()+"/slow","test-fixture","test-model");
            long started=System.nanoTime();
            assertThat(planner.locate("查相机器材")).isEmpty();
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isBetween(Duration.ofSeconds(7),Duration.ofMillis(9500));
        }
        finally { server.stop(0);executor.shutdownNow(); }
    }
    @Test void modelResponseHeadersCannotBypassTheCompleteBodyDeadline() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newSingleThreadExecutor();server.setExecutor(executor);
        server.createContext("/stalled-body",exchange->{
            try {
                exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,0);
                exchange.getResponseBody().write(' ');exchange.getResponseBody().flush();Thread.sleep(10000);
            } catch(Exception ignored) { }
            finally { exchange.close(); }
        });server.start();
        try {
            var json=new ObjectMapper().findAndRegisterModules();
            var planner=new LlmPlanner(new ExternalHttp(json),json,Clock.systemUTC(),"http://127.0.0.1:"+server.getAddress().getPort()+"/stalled-body","test-fixture","test-model");
            long started=System.nanoTime();assertThat(planner.locate("查相机")).isEmpty();
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isBetween(Duration.ofSeconds(7),Duration.ofMillis(9500));
        } finally { server.stop(0);executor.shutdownNow(); }
    }
}
