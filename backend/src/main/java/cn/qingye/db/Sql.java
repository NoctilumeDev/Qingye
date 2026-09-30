package cn.qingye.db;
import cn.qingye.business.Problem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.*;

@Repository
public class Sql {
    private final JdbcTemplate jdbc;
    public Sql(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
    public List<Map<String,Object>> list(String sql, Object... args) {
        return jdbc.query(sql, (rs, index) -> {
            var row = new LinkedHashMap<String,Object>();
            var meta = rs.getMetaData();
            for (int i=1;i<=meta.getColumnCount();i++) {
                String label = meta.getColumnLabel(i).toLowerCase(Locale.ROOT);
                StringBuilder key = new StringBuilder(); boolean upper = false;
                for (char c : label.toCharArray()) {
                    if (c=='_') upper=true;
                    else {
                        key.append(upper ? Character.toUpperCase(c) : c); upper=false;
                    }
                }
                Object value = rs.getObject(i);
                row.put(key.toString(), value instanceof Timestamp t ? t.toLocalDateTime().toString() : value);
            }
            return row;
        }, args);
    }
    public Map<String,Object> one(String sql, Object... args) {
        var rows = list(sql,args);
        if (rows.isEmpty()) throw Problem.missing();
        return rows.get(0);
    }
    public Map<String,Object> optional(String sql, Object... args) {
        var rows = list(sql,args);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class,args);
    }
    public int update(String sql, Object... args) {
        return jdbc.update(sql,args);
    }
    public long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var stmt = connection.prepareStatement(sql, new String[] {
                "id"
            });
            for (int i=0;i<args.length;i++) stmt.setObject(i+1,args[i]);
            return stmt;
        },key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
}
