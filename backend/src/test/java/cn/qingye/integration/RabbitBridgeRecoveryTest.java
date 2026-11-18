package cn.qingye.integration;

import cn.qingye.business.TaskService;
import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.MessageProperties;
import com.rabbitmq.client.ReturnCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RabbitBridgeRecoveryTest {
    private static final String QUEUE = "qingye.notification.v1";

    private final ConnectionFactory factory = mock(ConnectionFactory.class);
    private final RabbitBridge bridge = new RabbitBridge(mock(TaskService.class), true,
            "127.0.0.1", 1, "unused", "unused");

    RabbitBridgeRecoveryTest() {
        ReflectionTestUtils.setField(bridge, "factory", factory);
    }

    @AfterEach
    void closeBridge() {
        bridge.close();
    }

    @Test
    void switchesToLocalFallbackWhenBrokerReturnsAConfirmedPublication() throws Exception {
        var connection = mock(Connection.class);
        var producer = mock(Channel.class);
        var returned = new AtomicReference<ReturnCallback>();
        when(factory.newConnection("qingye-notifications")).thenReturn(connection);
        when(connection.createChannel()).thenReturn(producer, mock(Channel.class));
        when(producer.addReturnListener(any(ReturnCallback.class))).thenAnswer(call -> {
            returned.set(call.getArgument(0));
            return null;
        });
        doAnswer(call -> {
            var listener = returned.get();
            if (listener != null) {
                listener.handle(null);
            }
            return null;
        }).when(producer).waitForConfirmsOrDie(anyLong());

        assertThat(bridge.publish(71)).isFalse();
        verify(producer).basicPublish(eq(""), eq(QUEUE), eq(true),
                eq(MessageProperties.PERSISTENT_TEXT_PLAIN), any(byte[].class));
        assertThat(bridge.mode()).isEqualTo("LOCAL_FALLBACK");
        verify(connection).close();
    }

    @Test
    void reopensSubscriptionEvenWhenCancelledConsumerChannelRemainsOpen() throws Exception {
        var first = mock(Connection.class);
        var firstProducer = mock(Channel.class);
        var firstConsumer = mock(Channel.class);
        var second = mock(Connection.class);
        var secondProducer = mock(Channel.class);
        var secondConsumer = mock(Channel.class);
        when(factory.newConnection("qingye-notifications")).thenReturn(first, second);
        when(first.createChannel()).thenReturn(firstProducer, firstConsumer);
        when(first.isOpen()).thenReturn(true);
        when(firstProducer.isOpen()).thenReturn(true);
        when(firstConsumer.isOpen()).thenReturn(true);
        when(second.createChannel()).thenReturn(secondProducer, secondConsumer);

        assertThat(bridge.publish(72)).isTrue();
        var cancelled = ArgumentCaptor.forClass(CancelCallback.class);
        verify(firstConsumer).basicConsume(eq(QUEUE), eq(false), any(DeliverCallback.class), cancelled.capture());
        cancelled.getValue().handle("initial-subscription");

        assertThat(bridge.publish(73)).isTrue();
        assertThat(bridge.mode()).isEqualTo("RABBITMQ");
        verify(factory, times(2)).newConnection("qingye-notifications");
        verify(first).close();
        verify(secondConsumer).basicConsume(eq(QUEUE), eq(false),
                any(DeliverCallback.class), any(CancelCallback.class));
        verify(secondProducer).basicPublish("", QUEUE, true, MessageProperties.PERSISTENT_TEXT_PLAIN,
                "73".getBytes(StandardCharsets.UTF_8));
    }
}
