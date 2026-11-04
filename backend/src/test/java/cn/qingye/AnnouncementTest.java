package cn.qingye;

import cn.qingye.business.*;
import cn.qingye.db.*;
import cn.qingye.model.Actor;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongConsumer;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"qingye.demo-enabled=true","qingye.worker-enabled=false","qingye.redis-enabled=false","qingye.mq-enabled=false","qingye.llm-url=","qingye.feedback-url=","spring.datasource.url=${QINGYE_TEST_URL:jdbc:h2:mem:announcement-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}","spring.datasource.username=${QINGYE_TEST_USER:sa}","spring.datasource.password=${QINGYE_TEST_PASSWORD:}"})
@AutoConfigureMockMvc
class AnnouncementTest {
    @Autowired Sql sql;
    @Autowired AnnouncementService service;
    @Autowired BuiltinAnnouncements builtins;
    @Autowired AuthService auth;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;
    Actor admin,second,student;
    @BeforeEach void prepare() {
        String configured=System.getenv("QINGYE_TEST_URL");
        if(configured!=null && !configured.matches("jdbc:mysql://[^/]+/qingye_test(?:\\?.*)?")) throw new IllegalStateException("Test database must be qingye_test");
        sql.update("DELETE FROM announcement_state");
        sql.update("DELETE FROM announcement_audit");
        sql.update("DELETE FROM announcement WHERE builtin_key IS NULL");
        admin=user(true); second=user(true); student=user(false);
    }
    private Actor user(boolean administrator) {
        long id=sql.insert("INSERT INTO app_user(openid,name,admin) VALUES (?,'公告测试',?)","demo:announcement-"+UUID.randomUUID(),administrator);
        return new Actor(id,"公告测试",administrator);
    }
    private long create() { return service.create(admin,"本周更新","这次优化了公告阅读。","UPDATE"); }
    private int outcome(Runnable operation) { try { operation.run(); return 200; } catch(Problem problem) { return problem.status(); } }
    @Test void switchingToRealModeRejectsPersistedDemoSessionsButKeepsRealAccounts() {
        String key="public-test-only-mode-transition-material";
        var clock=java.time.Clock.systemUTC();
        var http=new cn.qingye.integration.ExternalHttp(new com.fasterxml.jackson.databind.ObjectMapper());
        var local=new AuthService(new UserStore(sql),sql,http,clock,true,key,"","");
        var real=new AuthService(new UserStore(sql),sql,http,clock,false,key,"","");
        String token=local.demo(admin.id()).get("token").toString();
        assertThat(local.authenticate(token).admin()).isTrue();
        assertThat(outcome(()->real.authenticate(token))).isEqualTo(401);
        sql.update("UPDATE app_user SET openid=? WHERE id=?","real-mode-fixture-"+UUID.randomUUID(),admin.id());
        assertThat(real.authenticate(token).id()).isEqualTo(admin.id());
    }
    @Test void builtinsRemainStableAndCannotBeModifiedOrDeletedEvenByAdministrators() throws Exception {
        var before=sql.list("SELECT id,title,body,version FROM announcement WHERE builtin_key IS NOT NULL ORDER BY id");
        builtins.afterPropertiesSet(); builtins.afterPropertiesSet();
        assertThat(sql.list("SELECT id,title,body,version FROM announcement WHERE builtin_key IS NOT NULL ORDER BY id")).isEqualTo(before);
        assertThat(before).hasSize(2);
        long id=((Number)before.get(0).get("id")).longValue();
        long version=((Number)before.get(0).get("version")).longValue();
        assertThat(outcome(()->service.edit(admin,id,version,"改写","改写","GUIDE"))).isEqualTo(403);
        assertThat(outcome(()->service.delete(admin,id,version))).isEqualTo(403);
        assertThat(outcome(()->service.delete(student,id,version))).isEqualTo(403);
    }
    @Test void archivingIsPrivatePersistentReversibleAndHasNoRetentionTimer() {
        long id=create();
        service.archive(student,id,1,true); service.archive(student,id,1,true);
        assertThat(service.detail(student,id)).containsEntry("archived",true);
        assertThat(service.detail(admin,id)).containsEntry("archived",false);
        assertThat(sql.count("SELECT COUNT(*) FROM announcement_state WHERE user_id=? AND announcement_id=?",student.id(),id)).isEqualTo(1);
        assertThat(service.detail(student,id)).containsEntry("body","这次优化了公告阅读。");
        service.archive(student,id,1,false);
        assertThat(service.detail(student,id)).containsEntry("archived",false);
    }
    @Test void editAndDeleteRequirePersistedRoleAndCurrentVersion() {
        long id=create();
        assertThat(outcome(()->service.edit(student,id,1,"误改","正文","UPDATE"))).isEqualTo(403);
        service.edit(second,id,1,"新版","新版正文","MAINTENANCE");
        assertThat(outcome(()->service.edit(admin,id,1,"过时","正文","UPDATE"))).isEqualTo(409);
        assertThat(outcome(()->service.delete(admin,id,1))).isEqualTo(409);
        assertThat(outcome(()->service.archive(student,id,1,true))).isEqualTo(409);
        assertThat(service.detail(student,id)).containsEntry("title","新版");
        sql.update("UPDATE app_user SET admin=FALSE WHERE id=?",admin.id());
        assertThat(outcome(()->service.delete(admin,id,2))).isEqualTo(403);
        service.delete(second,id,2);
        assertThat(outcome(()->service.detail(student,id))).isEqualTo(404);
        assertThat(sql.count("SELECT COUNT(*) FROM announcement_audit WHERE announcement_id=?",id)).isEqualTo(3);
    }
    private List<Integer> race(LongConsumer a,LongConsumer b,long id) throws Exception {
        var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->{start.await(); return outcome(()->a.accept(id));});
            var last=pool.submit(()->{start.await(); return outcome(()->b.accept(id));});
            start.countDown(); return List.of(first.get(10,TimeUnit.SECONDS),last.get(10,TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    @Test void concurrentEditorsCannotOverwriteEachOther() throws Exception {
        long id=create();
        assertThat(race(key->service.edit(admin,key,1,"甲","甲正文","UPDATE"),key->service.edit(second,key,1,"乙","乙正文","UPDATE"),id)).containsExactlyInAnyOrder(200,409);
        assertThat(((Number)service.detail(student,id).get("version")).longValue()).isEqualTo(2);
        assertThat(sql.count("SELECT COUNT(*) FROM announcement_audit WHERE announcement_id=?",id)).isEqualTo(2);
    }
    @Test void editDeleteRaceHasOneWinnerAndArchiveDoesNotChangeContentVersion() throws Exception {
        long id=create();
        service.archive(student,id,1,true);
        var result=race(key->service.edit(admin,key,1,"新版","新正文","UPDATE"),key->service.delete(second,key,1),id);
        assertThat(result.stream().filter(code->code==200).count()).isEqualTo(1);
        assertThat(result).allMatch(code->Set.of(200,409,404).contains(code));
    }
    @Test void administratorRevokedWhileWaitingCannotWrite() throws Exception {
        long id=create(); var locked=new CountDownLatch(1); var release=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try {
            var revocation=pool.submit(()->new TransactionTemplate(transactions).execute(s->{
                sql.one("SELECT id FROM app_user WHERE id=? FOR UPDATE",admin.id()); locked.countDown();
                try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                sql.update("UPDATE app_user SET admin=FALSE WHERE id=?",admin.id()); return true;
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var write=pool.submit(()->outcome(()->service.edit(admin,id,1,"禁止","禁止","UPDATE")));
            release.countDown(); revocation.get(5,TimeUnit.SECONDS);
            assertThat(write.get(5,TimeUnit.SECONDS)).isEqualTo(403);
            assertThat(service.detail(student,id)).containsEntry("title","本周更新");
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void listIsKeysetPaginatedAndDeletedAnnouncementsDisappearFromArchives() {
        for(int n=0;n<23;n++) create();
        var first=service.list(student,false,false,0);
        @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)first.get("items");
        assertThat(rows).hasSize(20); assertThat(first.get("hasMore")).isEqualTo(true);
        var next=service.list(student,false,false,((Number)first.get("nextBefore")).longValue());
        @SuppressWarnings("unchecked") var nextRows=(List<Map<String,Object>>)next.get("items");
        assertThat(nextRows).hasSize(5);
        assertThat(nextRows.stream().map(row->row.get("id"))).doesNotContainAnyElementsOf(rows.stream().map(row->row.get("id")).toList());
        long id=create(); service.archive(student,id,1,true); service.delete(admin,id,1);
        assertThat((List<?>)service.list(student,true,false,0).get("items")).isEmpty();
    }
    @Test void httpValidatesIdentityBodyAndProtectedRecords() throws Exception {
        String token=auth.demo(student.id()).get("token").toString();
        mvc.perform(get("/api/announcements")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/announcements?management=true").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/announcements").header("Authorization","Bearer "+token).contentType("application/json").content("{\"title\":\"公告\",\"body\":\"正文\",\"category\":\"UPDATE\",\"version\":1}")).andExpect(status().isForbidden());
        String adminToken=auth.demo(admin.id()).get("token").toString();
        mvc.perform(post("/api/admin/announcements").header("Authorization","Bearer "+adminToken).contentType("application/json").content("{\"title\":\" \",\"body\":\"正文\",\"category\":\"UPDATE\",\"version\":1}")).andExpect(status().isBadRequest());
        long id=create();
        mvc.perform(put("/api/announcements/"+id+"/archive").header("Authorization","Bearer "+token).contentType("application/json").content("{\"archived\":true,\"version\":1,\"userId\":"+admin.id()+"}")).andExpect(status().isOk());
        assertThat(service.detail(admin,id)).containsEntry("archived",false);
        assertThat(service.detail(student,id)).containsEntry("archived",true);
    }
}
