package cn.qingye.db;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
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
        try{jdbc.queryForObject("SELECT COUNT(*) FROM equipment",Long.class);}catch(DataAccessException absent){return;}
        jdbc.execute("CREATE TABLE IF NOT EXISTS equipment_alias (equipment_id BIGINT NOT NULL, alias VARCHAR(60) NOT NULL, PRIMARY KEY(equipment_id,alias), FOREIGN KEY(equipment_id) REFERENCES equipment(id) ON DELETE CASCADE, INDEX idx_equipment_alias(alias,equipment_id))");
        for(var alias:new String[][]{{"单反相机","相机"},{"轻便三脚架","三脚架"},{"便携投影仪","投影仪"}})
            jdbc.update("INSERT INTO equipment_alias(equipment_id,alias) SELECT e.id,? FROM equipment e WHERE e.name=? AND NOT EXISTS (SELECT 1 FROM equipment_alias a WHERE a.equipment_id=e.id AND a.alias=?)",alias[1],alias[0],alias[1]);
    }
}
