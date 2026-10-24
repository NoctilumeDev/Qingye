package cn.qingye;

import cn.qingye.integration.ExternalHttp;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class ExternalHttpTest {
    @ParameterizedTest
    @CsvSource({"false,false,200", "true,false,200", "false,true,200", "true,true,200", "true,true,503"})
    void oversizeBodyIsRejectedWithoutWaitingForEof(boolean chunked, boolean post, int status) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newSingleThreadExecutor();
        var prefixSent=new CountDownLatch(1);
        var releaseEof=new CountDownLatch(1);
        byte[] prefix=" ".repeat(1_048_577).getBytes(StandardCharsets.UTF_8);
        server.createContext("/large",exchange->{
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(status,chunked?0:prefix.length+1);
                var output=exchange.getResponseBody();
                output.write(prefix);output.flush();prefixSent.countDown();
                releaseEof.await(5,TimeUnit.SECONDS);
                output.write(' ');
            } catch(java.io.IOException cancelled) {
                // The client may close the response immediately on the oversized prefix.
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally { prefixSent.countDown();exchange.close(); }
        });
        server.createContext("/small",exchange->{
            byte[] body="{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}
        });
        server.start();
        var http=new ExternalHttp(new ObjectMapper());
        var pending=executor.submit(()->request(http,uri(server,"/large"),post));
        try {
            assertThat(prefixSent.await(2,TimeUnit.SECONDS)).as("server sent prefix").isTrue();
            assertThatThrownBy(()->pending.get(2,TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(releaseEof.getCount()).as("failure occurred before server EOF").isEqualTo(1);
            releaseEof.countDown();
            assertThat(http.get(uri(server,"/small")).get("ok")).isEqualTo(true);
        } finally { releaseEof.countDown();pending.cancel(true);server.stop(0);executor.shutdownNow(); }
    }

    @ParameterizedTest
    @CsvSource({"UTF-8,false", "UTF-8,true", "UTF-16LE,false", "UTF-32BE,true"})
    void characterBoundaryStillAcceptsMultibyteJson(String encoding, boolean post) throws Exception {
        String value="界".repeat(100_000-12);
        String body="{\"value\":\""+value+"\"}";
        assertThat(body.length()).isEqualTo(100_000);
        byte[] bytes=body.getBytes(Charset.forName(encoding));
        assertThat(bytes.length).isBetween(100_001,400_000);
        withResponse(200,bytes,"application/json; charset="+encoding,(http,uri)->
            assertThat(request(http,uri,post).get("value")).isEqualTo(value));
    }

    @ParameterizedTest
    @CsvSource({"ISO-8859-1,café", "UTF-8,青野"})
    void declaredCharsetAndOrdinaryResponsesArePreserved(String encoding,String value) throws Exception {
        byte[] bytes=("{\"value\":\""+value+"\"}").getBytes(Charset.forName(encoding));
        withResponse(200,bytes,"application/json; charset="+encoding,(http,uri)->
            assertThat(http.get(uri).get("value")).isEqualTo(value));
    }

    @Test void utf32BomDoesNotReduceTheExistingCharacterBudget() throws Exception {
        String value="界".repeat(100_000-12);
        byte[] text=("{\"value\":\""+value+"\"}").getBytes(Charset.forName("UTF-32BE"));
        byte[] body=new byte[text.length+4];body[2]=(byte)0xfe;body[3]=(byte)0xff;
        System.arraycopy(text,0,body,4,text.length);
        assertThat(body.length).isEqualTo(400_004);
        withResponse(200,body,"application/json; charset=UTF-32BE",(http,uri)->
            assertThat(http.get(uri).get("value")).isEqualTo(value));
    }

    @Test void statefulEncodingRetainsItsOrdinaryCharacterBudget() throws Exception {
        String value="あa".repeat((100_000-12)/2);
        byte[] body=("{\"value\":\""+value+"\"}").getBytes(Charset.forName("ISO-2022-JP"));
        assertThat(body.length).isGreaterThan(400_004).isLessThan(1_048_576);
        withResponse(200,body,"application/json; charset=ISO-2022-JP",(http,uri)->
            assertThat(http.get(uri).get("value")).isEqualTo(value));
    }

    @Test void decodedCharacterLimitRemainsIndependentOfByteLimit() throws Exception {
        byte[] bytes=("{\"value\":\""+"x".repeat(100_001-12)+"\"}").getBytes(StandardCharsets.UTF_8);
        withResponse(200,bytes,"application/json",(http,uri)->
            assertThatThrownBy(()->http.get(uri)).isInstanceOf(IllegalStateException.class));
    }

    @Test void malformedJsonRemainsAProtocolError() throws Exception {
        withResponse(200,"not JSON".getBytes(StandardCharsets.UTF_8),"application/json",(http,uri)->
            assertThatThrownBy(()->http.get(uri)).isInstanceOf(JsonProcessingException.class));
    }

    @Test void unsuccessfulStatusRemainsATransportFailure() throws Exception {
        withResponse(503,"{}".getBytes(StandardCharsets.UTF_8),"application/json",(http,uri)->
            assertThatThrownBy(()->http.get(uri)).isInstanceOf(IllegalStateException.class));
    }

    @Test void callerInterruptionCancelsAStalledBodyAndRestoresTheFlag() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var started=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var failure=new AtomicReference<Throwable>();
        var interrupted=new AtomicBoolean();
        server.createContext("/stalled",exchange->{
            try {
                exchange.sendResponseHeaders(200,0);
                exchange.getResponseBody().write(' ');exchange.getResponseBody().flush();started.countDown();
                release.await(5,TimeUnit.SECONDS);
            } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        var caller=new Thread(()->{
            try { new ExternalHttp(new ObjectMapper()).get(uri(server,"/stalled")); }
            catch(Exception e) { failure.set(e);interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        try {
            caller.start();assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
            caller.interrupt();caller.join(2000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(InterruptedException.class);
            assertThat(interrupted).isTrue();
        } finally { release.countDown();caller.interrupt();server.stop(0);caller.join(2000); }
    }

    private static Map<?,?> request(ExternalHttp http,URI uri,boolean post) throws Exception {
        return post?http.post(uri,"fixture-only",Map.of("question","青野")):http.get(uri);
    }
    private static URI uri(HttpServer server,String path) {
        return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+path);
    }
    private static void withResponse(int status,byte[] body,String contentType,ResponseCheck check) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/response",exchange->{
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type",contentType);
            exchange.sendResponseHeaders(status,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}
        });
        server.start();
        try { check.run(new ExternalHttp(new ObjectMapper()),uri(server,"/response")); }
        finally { server.stop(0); }
    }
    @FunctionalInterface private interface ResponseCheck {
        void run(ExternalHttp http,URI uri) throws Exception;
    }
}
