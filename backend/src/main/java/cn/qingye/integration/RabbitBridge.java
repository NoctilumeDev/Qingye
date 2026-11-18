package cn.qingye.integration;
import cn.qingye.business.TaskService;
import com.rabbitmq.client.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class RabbitBridge {
    private final TaskService tasks;
    private final ConnectionFactory factory=new ConnectionFactory();
    private final boolean enabled;
    private Connection connection;
    private Channel producer,consumer;
    private AtomicBoolean publishReturned;
    private AtomicBoolean consumerCancelled;
    private long retryAfter=0;
    private volatile String mode="DISABLED";
    private static final String QUEUE="qingye.notification.v1";
    public RabbitBridge(TaskService tasks,@Value("${qingye.mq-enabled}") boolean enabled,@Value("${qingye.mq-host}") String host,@Value("${qingye.mq-port}") int port,@Value("${qingye.mq-user}") String user,@Value("${qingye.mq-password}") String password) {
        this.tasks=tasks;
        this.enabled=enabled;
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(user);
        factory.setPassword(password);
        factory.setConnectionTimeout(500);
        factory.setHandshakeTimeout(1000);
        factory.setAutomaticRecoveryEnabled(false);
    }
    public synchronized boolean publish(long id) {
        if (!enabled || System.currentTimeMillis()<retryAfter) return false;
        try {
            if (connection==null || !connection.isOpen() || producer==null || !producer.isOpen()
                    || consumer==null || !consumer.isOpen() || consumerCancelled.get()) {
                close();
                connect();
            }
            publishReturned.set(false);
            producer.basicPublish("",QUEUE,true,MessageProperties.PERSISTENT_TEXT_PLAIN,String.valueOf(id).getBytes(StandardCharsets.UTF_8));
            producer.waitForConfirmsOrDie(1000);
            // A mandatory return arrives before its confirm on the producer channel.
            if (publishReturned.get() || consumerCancelled.get()) {
                throw new IOException("Notification could not be routed to an active subscription");
            }
            mode="RABBITMQ";
            return true;
        }
        catch(Exception e) {
            close();
            retryAfter=System.currentTimeMillis()+5000;
            mode="LOCAL_FALLBACK";
            return false;
        }
    }
    private void connect() throws Exception {
        connection=factory.newConnection("qingye-notifications");
        producer=connection.createChannel();
        producer.queueDeclare(QUEUE,true,false,false,Map.of());
        producer.confirmSelect();
        var returned=new AtomicBoolean();
        publishReturned=returned;
        producer.addReturnListener(message->returned.set(true));
        consumer=connection.createChannel();
        consumer.basicQos(10);
        var cancelled=new AtomicBoolean();
        consumerCancelled=cancelled;
        Channel deliveryChannel=consumer;
        deliveryChannel.basicConsume(QUEUE,false,(tag,delivery)-> {
            try {
                String body=new String(delivery.getBody(),StandardCharsets.UTF_8);
                if (!body.matches("[0-9]{1,18}")) {
                    deliveryChannel.basicReject(delivery.getEnvelope().getDeliveryTag(),false);return;
                }
                tasks.complete(Long.parseLong(body));deliveryChannel.basicAck(delivery.getEnvelope().getDeliveryTag(),false);
            }
            catch(Exception e) {
                if (deliveryChannel.isOpen()) deliveryChannel.basicNack(delivery.getEnvelope().getDeliveryTag(),false,false);
            }
        },tag->cancelled.set(true));
    }
    @PreDestroy public synchronized void close() {
        try {
            if (connection!=null) connection.close();
        }
        catch(Exception ignored) {
        }
        connection=null;
        producer=null;
        consumer=null;
    }
    public String mode() {
        return enabled?mode:"DISABLED";
    }
}
