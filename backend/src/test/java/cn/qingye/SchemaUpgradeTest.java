package cn.qingye;
import cn.qingye.db.SchemaUpgrade;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class SchemaUpgradeTest {
    @Test void existingNotificationRowsSurviveAnIdempotentAdditiveUpgrade() {
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:notification-upgrade;MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE notification(id BIGINT PRIMARY KEY, body VARCHAR(100))");
        jdbc.update("INSERT INTO notification(id,body) VALUES (1,'existing record')");
        var upgrade=new SchemaUpgrade(jdbc);
        upgrade.afterPropertiesSet();
        upgrade.afterPropertiesSet();
        assertThat(jdbc.queryForObject("SELECT body FROM notification WHERE id=1",String.class)).isEqualTo("existing record");
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM notification WHERE id=1",Object.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification",Long.class)).isEqualTo(1);
    }
}
