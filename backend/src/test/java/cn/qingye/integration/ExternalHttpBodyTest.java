package cn.qingye.integration;

import org.junit.jupiter.api.Test;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import static org.assertj.core.api.Assertions.*;

class ExternalHttpBodyTest {
    @Test void smallSlicesDoNotRetainOrReuseLargeSourceStorage() {
        var capture=new Capture();var receiver=new ExternalHttp.BoundedBody(capture);
        receiver.onSubscribe(new Subscription());
        var originals=new ArrayList<ByteBuffer>();
        for(int i=0;i<64;i++) {
            byte[] backing=new byte[65536];backing[0]=42;
            var slice=ByteBuffer.wrap(backing).limit(1).slice().asReadOnlyBuffer();
            originals.add(slice);receiver.onNext(List.of(slice));backing[0]=99;
        }
        receiver.onComplete();
        assertThat(capture.body.join()).isEqualTo("*".repeat(64));
        assertThat(capture.buffers.stream().mapToInt(ByteBuffer::capacity).sum()).isLessThanOrEqualTo(8192);
        assertThat(capture.buffers).noneMatch(b->originals.stream().anyMatch(original->b==original));
    }

    @Test void exactWireLimitIsAdmittedAndOneMoreByteCancelsAndFails() {
        var allowed=new Capture();var receiver=new ExternalHttp.BoundedBody(allowed);
        receiver.onSubscribe(new Subscription());receiver.onNext(List.of(ByteBuffer.allocate(1_048_576)));receiver.onComplete();
        assertThat(allowed.body.join()).hasSize(1_048_576);

        var rejected=new Capture();var upstream=new Subscription();receiver=new ExternalHttp.BoundedBody(rejected);
        receiver.onSubscribe(upstream);receiver.onNext(List.of(ByteBuffer.allocate(1_048_576)));
        receiver.onNext(List.of(ByteBuffer.wrap(new byte[]{1})));
        receiver.onNext(List.of(ByteBuffer.wrap(new byte[]{2})));receiver.onComplete();
        assertThat(upstream.cancelled).isTrue();
        assertThatThrownBy(rejected.body::join).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(rejected.buffers.stream().mapToInt(ByteBuffer::remaining).sum()).isEqualTo(1_048_576);
    }

    @Test void wholeOverflowingBatchIsRejectedBeforeAnyForwarding() {
        var capture=new Capture();var upstream=new Subscription();var receiver=new ExternalHttp.BoundedBody(capture);
        receiver.onSubscribe(upstream);
        receiver.onNext(List.of(ByteBuffer.allocate(1_048_576),ByteBuffer.wrap(new byte[]{1})));
        assertThat(capture.buffers).isEmpty();assertThat(upstream.cancelled).isTrue();
        assertThatThrownBy(capture.body::join).hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test void missingSubscriptionFailsAndLateSubscriptionIsCancelled() {
        var capture=new Capture();var receiver=new ExternalHttp.BoundedBody(capture);
        receiver.onNext(List.of(ByteBuffer.wrap(new byte[]{1})));
        var late=new Subscription();receiver.onSubscribe(late);
        assertThat(late.cancelled).isTrue();assertThat(capture.buffers).isEmpty();
        assertThatThrownBy(capture.body::join).hasCauseInstanceOf(IllegalStateException.class);
    }

    private static final class Subscription implements Flow.Subscription {
        boolean cancelled;
        @Override public void request(long count) { }
        @Override public void cancel() { cancelled=true; }
    }
    private static final class Capture implements HttpResponse.BodySubscriber<String> {
        final List<ByteBuffer> buffers=new ArrayList<>();final CompletableFuture<String> body=new CompletableFuture<>();
        @Override public CompletionStage<String> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
        @Override public void onNext(List<ByteBuffer> values) { buffers.addAll(values); }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() {
            byte[] bytes=new byte[buffers.stream().mapToInt(ByteBuffer::remaining).sum()];int offset=0;
            for(var buffer:buffers) { var read=buffer.duplicate();int count=read.remaining();read.get(bytes,offset,count);offset+=count; }
            body.complete(new String(bytes,java.nio.charset.StandardCharsets.ISO_8859_1));
        }
    }
}
