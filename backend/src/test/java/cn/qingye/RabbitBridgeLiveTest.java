package cn.qingye;

import cn.qingye.business.TaskService;
import cn.qingye.integration.RabbitBridge;
import com.rabbitmq.client.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Only an explicitly provisioned, disposable local test vhost is allowed. */
@EnabledIfEnvironmentVariable(named = "QINGYE_MQ_TEST", matches = "1")
class RabbitBridgeLiveTest {
    private static final String QUEUE = "qingye.notification.v1";
    private final TaskService tasks = mock(TaskService.class);
    private final MetricsCollector metrics = mock(MetricsCollector.class, CALLS_REAL_METHODS);
    private RabbitBridge bridge;
    private Connection inspector;
    private Channel channel;

    @BeforeEach void connectIsolatedFixture() throws Exception {
        String vhost = System.getenv("QINGYE_MQ_TEST_VHOST");
        assertThat(vhost).startsWith("qingye-test-");
        String user = System.getenv("QINGYE_MQ_TEST_USER");
        String password = System.getenv("QINGYE_MQ_TEST_PASSWORD");
        assertThat(user).isNotBlank();
        assertThat(password).isNotBlank();
        int port = Integer.parseInt(System.getenv().getOrDefault("QINGYE_MQ_TEST_PORT", "5672"));
        bridge = new RabbitBridge(tasks, true, "127.0.0.1", port, user, password);
        var factory = (ConnectionFactory) ReflectionTestUtils.getField(bridge, "factory");
        factory.setVirtualHost(vhost); // test-only isolation, production contract unchanged
        factory.setMetricsCollector(metrics);
        var inspectionFactory = new ConnectionFactory();
        inspectionFactory.setHost("127.0.0.1"); inspectionFactory.setPort(port);
        inspectionFactory.setUsername(user); inspectionFactory.setPassword(password);
        inspectionFactory.setVirtualHost(vhost); inspectionFactory.setAutomaticRecoveryEnabled(false);
        inspector = inspectionFactory.newConnection("qingye-test-inspection");
        channel = inspector.createChannel();
        channel.queueDeclare(QUEUE, true, false, false, null);
        channel.queuePurge(QUEUE); // this dedicated fixture contains synthetic IDs only
        channel.confirmSelect();
    }

    @AfterEach void closeOwnedConnections() throws Exception {
        if (bridge != null) bridge.close();
        if (inspector != null && inspector.isOpen()) inspector.close();
    }

    @Test void publishConfirmsConsumeAndAckRemainCompatible() throws Exception {
        assertThat(bridge.publish(101)).isTrue();
        verify(tasks, timeout(3000)).complete(101);
        verify(metrics, timeout(3000)).basicAck(any(), anyLong(), eq(false));
        assertThat(bridge.mode()).isEqualTo("RABBITMQ");
        bridge.close();
        assertThat(channel.basicGet(QUEUE, false)).isNull();
    }

    @Test void malformedBodiesAreRejectedWithoutRequeue() throws Exception {
        assertThat(bridge.publish(102)).isTrue();
        verify(metrics, timeout(3000)).basicAck(any(), anyLong(), eq(false));
        for (String value : new String[]{"", "-1", "x", "1.0", "1234567890123456789"}) {
            channel.basicPublish("", QUEUE, null, value.getBytes(StandardCharsets.UTF_8));
        }
        channel.waitForConfirmsOrDie(3000);
        verify(metrics, timeout(3000).times(5)).basicReject(any(), anyLong());
        verify(tasks, times(1)).complete(anyLong());
        bridge.close();
        assertThat(channel.basicGet(QUEUE, false)).isNull();
    }

    @Test void processingFailureNacksWithoutRequeue() throws Exception {
        doThrow(new IllegalStateException("synthetic processing failure")).when(tasks).complete(103);
        assertThat(bridge.publish(103)).isTrue();
        verify(metrics, timeout(3000)).basicNack(any(), anyLong());
        verify(metrics, never()).basicAck(any(), anyLong(), anyBoolean());
        bridge.close();
        assertThat(channel.basicGet(QUEUE, false)).isNull();
    }

    @Test void closedProducerIsReconnectedBeforeNextPublish() throws Exception {
        assertThat(bridge.publish(104)).isTrue();
        verify(metrics, timeout(3000)).basicAck(any(), anyLong(), eq(false));
        var producer = (Channel) ReflectionTestUtils.getField(bridge, "producer");
        producer.close();
        assertThat(bridge.publish(105)).isTrue();
        verify(tasks, timeout(3000)).complete(105);
        verify(metrics, timeout(3000).times(2)).basicAck(any(), anyLong(), eq(false));
        verify(metrics, times(2)).newConnection(any());
        bridge.close();
        assertThat(channel.basicGet(QUEUE, false)).isNull();
    }

    @Test void disabledModeDoesNotOpenAnAmqpConnection() {
        var disabled = new RabbitBridge(tasks, false, "127.0.0.1", 1, "unused", "unused");
        try {
            assertThat(disabled.publish(106)).isFalse();
            assertThat(disabled.mode()).isEqualTo("DISABLED");
            verifyNoInteractions(tasks);
        } finally { disabled.close(); }
    }
}
