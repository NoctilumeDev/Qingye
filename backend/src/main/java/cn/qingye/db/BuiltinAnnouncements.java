package cn.qingye.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/** Stable keys and monotonic content revisions keep deployment retries and old nodes harmless. */
@Component
public class BuiltinAnnouncements implements InitializingBean {
    private final Sql sql;
    private final ObjectMapper json;
    public BuiltinAnnouncements(Sql sql,ObjectMapper json) { this.sql=sql; this.json=json; }
    @Override public void afterPropertiesSet() throws Exception {
        try(var stream=new ClassPathResource("announcements.json").getInputStream()) {
            for(var item:json.readTree(stream)) {
                String key=item.path("key").asText(),title=item.path("title").asText(),body=item.path("body").asText();
                int revision=item.path("revision").asInt();
                if(key.isBlank() || title.isBlank() || body.isBlank() || revision<1) throw new IllegalStateException("Invalid builtin announcement");
                try { sql.insert("INSERT INTO announcement(builtin_key,builtin_revision,title,body,category) VALUES (?,?,?,?,'GUIDE')",key,revision,title,body); }
                catch(DuplicateKeyException existing) { /* Another deployment may have registered this stable key. */ }
                sql.update("UPDATE announcement SET title=?,body=?,builtin_revision=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE builtin_key=? AND builtin_revision<?",title,body,revision,key,revision);
            }
        }
    }
}
