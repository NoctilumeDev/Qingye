package cn.qingye.integration;
import cn.qingye.business.TaskService;
import com.rabbitmq.client.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class RabbitBridge {
    private final TaskService tasks;
    private final ConnectionFactory factory=new ConnectionFactory();
    private final boolean enabled;
    private Connection connection;
    private Channel producer,consumer;
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
            if (connection==null || !connection.isOpen() || producer==null || !producer.isOpen() || consumer==null || !consumer.isOpen()) {
                close();
                connect();
            }
            producer.basicPublish("",QUEUE,MessageProperties.PERSISTENT_TEXT_PLAIN,String.valueOf(id).getBytes(StandardCharsets.UTF_8));
            producer.waitForConfirmsOrDie(1000);
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
        consumer=connection.createChannel();
        consumer.basicQos(10);
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
        },tag-> {
        });
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
