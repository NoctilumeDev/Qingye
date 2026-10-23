package cn.qingye;

import cn.qingye.business.TaskService;
import cn.qingye.integration.RabbitBridge;
import com.rabbitmq.client.*;
import com.rabbitmq.client.impl.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.*;
import java.net.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Bounded parser inputs: never allocate a huge message or exhaust the JVM. */
class RabbitClientBoundaryTest {
    private static final int BODY_LIMIT = 64 * 1024 * 1024;

    private static byte[] table(char kind, byte[] value) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeInt(3 + value.length); // one-byte key length, key, field type
        out.writeByte(1); out.writeByte('k'); out.writeByte(kind); out.write(value);
        return bytes.toByteArray();
    }

    private static byte[] length(int value) throws IOException {
        var bytes = new ByteArrayOutputStream();
        new DataOutputStream(bytes).writeInt(value);
        return bytes.toByteArray();
    }

    private static byte[] nested(int depth) throws IOException {
        byte[] value = length(0);
        for (int i = 0; i < depth; i++) {
            if (i % 2 == 0) {
                value = table('F', value);
            } else {
                var bytes = new ByteArrayOutputStream();
                var out = new DataOutputStream(bytes);
                out.writeInt(1 + value.length); out.writeByte('F'); out.write(value);
                value = table('A', bytes.toByteArray());
            }
        }
        return value;
    }

    private static ValueReader reader(byte[] data) {
        return new ValueReader(new DataInputStream(new ByteArrayInputStream(data)));
    }

    @Test void declaredStringAndByteLengthsMustFitTheAvailableFrame() throws Exception {
        for (char type : new char[]{'S', 'x'}) {
            // 1 KiB declared, zero bytes present. The old parser allocates then
            // throws EOFException; the fixed boundary rejects before allocation.
            byte[] input = table(type, length(1024));
            assertThatThrownBy(() -> reader(input).readTable())
                    .isInstanceOf(MalformedFrameException.class);
        }
    }

    @Test void deepMixedTablesAndArraysAreRejectedButShallowValuesRemainValid() throws Exception {
        assertThat(reader(nested(4)).readTable()).isNotEmpty();
        byte[] input = nested(70); // small fixture, not a stack exhaustion test
        assertThatThrownBy(() -> reader(input).readTable())
                .isInstanceOf(MalformedFrameException.class);
    }

    private static AMQCommand command() throws Exception {
        // This inbound constructor exists in both evaluated client versions.
        var constructor = AMQCommand.class.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        var command = constructor.newInstance(BODY_LIMIT);
        command.handleFrame(new AMQImpl.Basic.Deliver("test", 1L, false, "", "test").toFrame(1));
        return command;
    }

    @Test void messageHeaderLimitIsExecutedBeforeAnyBodyIsReceived() throws Exception {
        var command = command();
        var header = new AMQP.BasicProperties.Builder().build().toFrame(1, BODY_LIMIT + 1L);
        assertThatThrownBy(() -> command.handleFrame(header))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("too large");
    }

    @Test void inconsistentBodyLengthIsAProtocolRejectionNotAnInternalException() throws Exception {
        var command = command();
        command.handleFrame(new AMQP.BasicProperties.Builder().build().toFrame(1, 1));
        assertThatThrownBy(() -> command.handleFrame(new Frame(AMQP.FRAME_BODY, 1, new byte[]{1, 2})))
                .isInstanceOf(MalformedFrameException.class);
    }

    @Test void validFragmentedTaskBodyStillAssemblesExactly() throws Exception {
        var command = command();
        assertThat(command.handleFrame(new AMQP.BasicProperties.Builder().build().toFrame(1, 3))).isFalse();
        assertThat(command.handleFrame(new Frame(AMQP.FRAME_BODY, 1, new byte[]{'1'}))).isFalse();
        assertThat(command.handleFrame(new Frame(AMQP.FRAME_BODY, 1, new byte[]{'2', '3'}))).isTrue();
        assertThat(command.getContentBody()).containsExactly((byte)'1', (byte)'2', (byte)'3');
    }

    @Test void actualBridgeFactoryRejectsMalformedHandshakeValues() throws Exception {
        for (byte[] properties : new byte[][]{table('S', length(1024)), table('x', length(1024)), nested(70)}) {
            var start = new Frame(AMQP.FRAME_METHOD, 0);
            var out = start.getOutputStream();
            out.writeShort(10); out.writeShort(10); // connection.start
            out.writeByte(0); out.writeByte(9);
            out.write(properties);
            var writer = new ValueWriter(out);
            writer.writeLongstr("PLAIN"); writer.writeLongstr("en_US");
            Throwable failure = receiveFromLoopback(start);
            assertThat(hasCause(failure, MalformedFrameException.class)).isTrue();
        }
    }

    @Test void actualBridgeFactoryEnforcesTheDefaultFrameCapBeforeReadingPayload() throws Exception {
        // Only seven header bytes; do not allocate or send a 64 MiB payload.
        Throwable failure = receiveFromLoopback(null);
        assertThat(messages(failure)).contains("maximum configured size");
        assertThat(hasCause(failure, MalformedFrameException.class)
                || hasCause(failure, IllegalStateException.class)).isTrue();
    }

    @Test void connectionFailureReturnsLocalFallbackAndDoesNotCompleteTasks() throws Exception {
        var tasks = mock(TaskService.class);
        // An owned listening socket never answers AMQP: exercise handshake timeout.
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var bridge = new RabbitBridge(tasks, true, "127.0.0.1", server.getLocalPort(), "test", "test");
            try {
                assertThat(bridge.publish(107)).isFalse();
                assertThat(bridge.mode()).isEqualTo("LOCAL_FALLBACK");
                assertThat(bridge.publish(108)).isFalse(); // existing retry cooldown
                verifyNoInteractions(tasks);
            } finally { bridge.close(); }
        }
    }

    @Test void negotiatedFrameMaxIsEnforced() throws Exception {
        rejectAfterTune(16384, 16384, true); // above the protocol minimum, plus frame overhead
    }

    @Test void unlimitedNegotiatedFrameMaxDoesNotDisableTheBodyCap() throws Exception {
        rejectAfterTune(0, BODY_LIMIT + 1, false);
    }

    private static void readPeerFrame(DataInputStream in) throws IOException {
        in.readUnsignedByte(); in.readUnsignedShort();
        int size = in.readInt();
        assertThat(size).isBetween(0, 8192);
        assertThat(in.readNBytes(size)).hasSize(size);
        assertThat(in.readUnsignedByte()).isEqualTo(AMQP.FRAME_END);
    }

    private static void rejectAfterTune(int frameMax, int declaredLength, boolean sendPayload) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var ready = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var reason = new AtomicReference<Throwable>();
        Connection connection = null;
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var peer = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    var in = new DataInputStream(socket.getInputStream());
                    var out = new DataOutputStream(socket.getOutputStream());
                    assertThat(in.readNBytes(8)).hasSize(8);
                    new AMQImpl.Connection.Start(0, 9, Map.of(),
                            LongStringHelper.asLongString("PLAIN"), LongStringHelper.asLongString("en_US"))
                            .toFrame(0).writeTo(out); out.flush();
                    readPeerFrame(in); // start-ok
                    new AMQImpl.Connection.Tune(0, frameMax, 0).toFrame(0).writeTo(out); out.flush();
                    readPeerFrame(in); readPeerFrame(in); // tune-ok, open
                    new AMQImpl.Connection.OpenOk("").toFrame(0).writeTo(out); out.flush();
                    assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
                    out.writeByte(AMQP.FRAME_HEARTBEAT); out.writeShort(0); out.writeInt(declaredLength);
                    if (sendPayload) { out.write(new byte[declaredLength]); out.writeByte(AMQP.FRAME_END); }
                    out.flush();
                    try { in.read(); } catch (SocketTimeoutException ignored) { }
                }
                return null;
            });
            var bridge = new RabbitBridge(mock(TaskService.class), true, "127.0.0.1",
                    server.getLocalPort(), "synthetic-user", "synthetic-password");
            var factory = (ConnectionFactory) ReflectionTestUtils.getField(bridge, "factory");
            connection = factory.newConnection("bounded-negotiation-test");
            connection.addShutdownListener(signal -> { reason.set(signal); stopped.countDown(); });
            ready.countDown();
            assertThat(stopped.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(messages(reason.get())).contains("maximum configured size");
            assertThat(hasCause(reason.get(), MalformedFrameException.class)
                    || hasCause(reason.get(), IllegalStateException.class)).isTrue();
            peer.get(3, TimeUnit.SECONDS);
        } finally {
            if (connection != null) connection.abort(1000);
            ready.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(4, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static Throwable receiveFromLoopback(Frame start) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var sent = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    var in = new DataInputStream(socket.getInputStream());
                    assertThat(in.readNBytes(8)).containsExactly((byte)'A', (byte)'M', (byte)'Q', (byte)'P',
                            (byte)0, (byte)0, (byte)9, (byte)1);
                    var out = new DataOutputStream(socket.getOutputStream());
                    if (start != null) start.writeTo(out);
                    else { out.writeByte(AMQP.FRAME_METHOD); out.writeShort(0); out.writeInt(BODY_LIMIT + 1); }
                    out.flush();
                    // Keep input available until the client rejects or replies.
                    try { in.read(); } catch (SocketTimeoutException ignored) { }
                }
                return null;
            });
            var bridge = new RabbitBridge(mock(TaskService.class), true, "127.0.0.1",
                    server.getLocalPort(), "synthetic-user", "synthetic-password");
            var factory = (ConnectionFactory) ReflectionTestUtils.getField(bridge, "factory");
            assertThat(ReflectionTestUtils.getField(factory, "maxInboundMessageBodySize")).isEqualTo(BODY_LIMIT);
            Throwable failure = catchThrowable(() -> {
                try (var connection = factory.newConnection("bounded-parser-test")) {
                    fail("The malformed handshake must not open a connection");
                }
            });
            assertThat(failure).isNotNull();
            sent.get(3, TimeUnit.SECONDS);
            return failure;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static boolean hasCause(Throwable value, Class<? extends Throwable> type) {
        for (int i = 0; value != null && i < 12; i++, value = value.getCause())
            if (type.isInstance(value)) return true;
        return false;
    }

    private static String messages(Throwable value) {
        var result = new StringBuilder();
        for (int i = 0; value != null && i < 12; i++, value = value.getCause())
            result.append(value.getMessage()).append(' ');
        return result.toString();
    }
}
