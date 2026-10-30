package cn.qingye;

import cn.qingye.integration.*;
import cn.qingye.model.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class LlmPlannerContractTest {
    @Test void advertisedTimeOptionsMatchEveryExecutableCapability() throws Exception {
        var json=new ObjectMapper();
        String instruction=LlmPlanner.instruction(Clock.systemUTC());
        String metadata=instruction.split("\n已有业务目录与明确选项：",2)[1].split("\nwireProtocol",2)[0];
        var view=json.readTree(metadata);
        for(String intent:AssistantContract.INTENTS){
            assertThat(json.convertValue(view.path("timeOptionsByIntent").path(intent),List.class)).isEqualTo(AssistantContract.times(intent));
            assertThat(json.convertValue(view.path("timeLabelsByIntent").path(intent),List.class)).isEqualTo(AssistantContract.timeLabels(intent));
            for(String time:AssistantContract.TIMES){
                var plan=new QueryPlan("QUERY",intent,intent.equals("EQUIPMENT")?"ALL":null,null,time,
                    time.equals("DATE")?"2026-10-29":null,"ANY",null,null);
                assertThat(plan.valid()).as(intent+"/"+time).isEqualTo(AssistantContract.times(intent).contains(time));
            }
        }
        for(String time:List.of("THIS_WEEK","WEEKEND","PAST"))
            assertThat(view.path("timeOptionsByIntent").path("ACTIVITIES").toString()).contains('"'+time+'"');
    }

    @ParameterizedTest
    @ValueSource(strings={"deepseek-flash","qwen-plus","gpt-6-astra","gpt-6-astra-2026-09-25","test-model"})
    void providerParametersDoNotChangeTheSharedBusinessProtocol(String model) throws Exception {
        var json=new ObjectMapper();var captured=new AtomicReference<JsonNode>();
        var hint=AssistantHint.locate("EQUIPMENT",null);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/plan",exchange->{
            captured.set(json.readTree(exchange.getRequestBody()));
            byte[] body=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",json.writeValueAsString(hint))))));
            exchange.sendResponseHeaders(200,body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}
        });server.start();
        try{
            var planner=new LlmPlanner(new ExternalHttp(json),json,Clock.systemUTC(),"http://127.0.0.1:"+server.getAddress().getPort()+"/plan","fixture-only",model);
            assertThat(planner.locate("器材名称不确定，想看看器材")).contains(hint);
            var request=captured.get();
            assertThat(request.path("model").asText()).isEqualTo(model);
            assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_object");
            assertThat(request.path("messages").get(0).path("content").asText()).isEqualTo(LlmPlanner.instruction(Clock.systemUTC()));
            assertThat(request.path("messages").get(1).path("content").asText()).isEqualTo("器材名称不确定，想看看器材");
            if(model.startsWith("gpt-6-astra")){
                assertThat(request.has("max_tokens")).isFalse();assertThat(request.has("temperature")).isFalse();
                assertThat(request.path("max_completion_tokens").asInt()).isEqualTo(2048);
                assertThat(request.path("reasoning_effort").asText()).isEqualTo("low");
                assertThat(request.path("store").asBoolean(true)).isFalse();
            }else{assertThat(request.path("max_tokens").asInt()).isEqualTo(350);assertThat(request.path("temperature").asInt(-1)).isZero();}
            assertThat(request.has("thinking")).isEqualTo(model.startsWith("deepseek-"));
            assertThat(request.has("enable_thinking")).isEqualTo(model.startsWith("qwen"));
        }finally{server.stop(0);}
    }

    @Test void aUsefulCollectionStillCompilesButWrongFieldValuesStillStop(){
        var collection=AssistantContract.directoryQuery(AssistantHint.locate("EQUIPMENT",null),"器材名称不确定");
        assertThat(collection.accepted()).isTrue();
        assertThat(collection.query()).isEqualTo(QueryPlan.query("EQUIPMENT","ALL",null,"CURRENT"));
        for(var choice:List.of(new AssistantHint.Selection("STATE","CURRENT","当前"),new AssistantHint.Selection("STATE","APPROVED","已批准"),new AssistantHint.Selection("TIME","FUTURE","未来"))){
            var candidate=new AssistantHint("LOCATE","EQUIPMENT",null,null,List.of(choice));
            assertThat(AssistantContract.directoryQuery(candidate,choice.source()).accepted()).isFalse();
        }
    }
    @Test void timeoutConfigurationIsBoundedBelowTheClientAndNeverRetries()throws Exception{
        var json=new ObjectMapper();var budget=new AtomicReference<java.time.Duration>();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var http=new ExternalHttp(json){@Override public Map<?,?> post(java.net.URI uri,String key,Map<String,Object> body,java.time.Duration deadline)throws Exception{
            budget.set(deadline);calls.incrementAndGet();throw new java.net.SocketTimeoutException("private transport detail");
        }};
        var planner=new LlmPlanner(http,json,Clock.systemUTC(),"http://localhost/fixture","fixture","test",750);
        assertThatThrownBy(()->planner.locate("查相机")).isInstanceOfSatisfying(LlmPlanner.Unavailable.class,e->{
            assertThat(e.category()).isEqualTo(LlmPlanner.TransportFailure.TIMEOUT);assertThat(e.getMessage()).isEqualTo("TIMEOUT");
        });
        assertThat(calls.get()).isEqualTo(1);assertThat(budget.get()).isEqualTo(java.time.Duration.ofMillis(750));
        for(long value:new long[]{0,99,18001})assertThatThrownBy(()->new LlmPlanner(http,json,Clock.systemUTC(),"http://localhost","fixture-key","fixture-model",value).locate("查相机"))
            .isInstanceOf(LlmPlanner.Unavailable.class).hasMessage("CONFIGURATION");
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void connectionAndProviderFailuresKeepDistinctDiagnosticsWithoutBodiesOrSecrets(){
        var json=new ObjectMapper();
        Map<Exception,LlmPlanner.TransportFailure> cases=Map.of(
            new java.net.ConnectException("private endpoint"),LlmPlanner.TransportFailure.CONNECT,
            new java.net.UnknownHostException("private host"),LlmPlanner.TransportFailure.DNS,
            new javax.net.ssl.SSLHandshakeException("private certificate"),LlmPlanner.TransportFailure.TLS,
            new ExternalHttp.ResponseFailure(429),LlmPlanner.TransportFailure.HTTP_STATUS,
            new ExternalHttp.ResponseFailure(0),LlmPlanner.TransportFailure.RESPONSE_TOO_LARGE);
        cases.forEach((error,category)->{
            var http=new ExternalHttp(json){@Override public Map<?,?> post(java.net.URI uri,String key,Map<String,Object> body,java.time.Duration deadline)throws Exception{throw new java.util.concurrent.ExecutionException(error);}};
            var planner=new LlmPlanner(http,json,Clock.systemUTC(),"http://localhost/fixture","fixture","test");
            assertThatThrownBy(()->planner.locate("查相机")).isInstanceOfSatisfying(LlmPlanner.Unavailable.class,e->assertThat(e.category()).isEqualTo(category));
        });
    }
}
