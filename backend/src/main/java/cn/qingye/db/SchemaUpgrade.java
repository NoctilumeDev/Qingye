package cn.qingye.db;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** One additive upgrade for existing Qingye databases; never resets user records. */
@Component
@DependsOnDatabaseInitialization
public class SchemaUpgrade implements InitializingBean {
    private final JdbcTemplate jdbc;
    public SchemaUpgrade(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
    }
    @Override public void afterPropertiesSet() {
        boolean present=Boolean.TRUE.equals(jdbc.query("SELECT * FROM notification WHERE 1=0",rs-> {
            var metadata=rs.getMetaData();
            for(int column=1;column<=metadata.getColumnCount();column++) {
                if("deleted_at".equalsIgnoreCase(metadata.getColumnName(column))) return true;
            }
            return false;
        }));
        if(!present) jdbc.execute("ALTER TABLE notification ADD COLUMN deleted_at TIMESTAMP(6) NULL");
    }
}
