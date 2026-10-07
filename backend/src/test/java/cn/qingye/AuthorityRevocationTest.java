package cn.qingye;

import cn.qingye.business.AuthService;
import cn.qingye.db.ActivityStore;
import cn.qingye.db.LoanStore;
import cn.qingye.db.Sql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties={"qingye.demo-enabled=true","qingye.worker-enabled=false",
        "qingye.redis-enabled=false","qingye.mq-enabled=false","qingye.llm-url=",
        "spring.datasource.url=${QINGYE_TEST_URL:jdbc:h2:mem:authority;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${QINGYE_TEST_USER:sa}","spring.datasource.password=${QINGYE_TEST_PASSWORD:}"})
@AutoConfigureMockMvc
class AuthorityRevocationTest {
    @Autowired Sql sql;
    @Autowired DataSource datasource;
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired Clock clock;
    @MockitoSpyBean ActivityStore activities;
    @MockitoSpyBean LoanStore loans;
    long admin,manager,club,activity;

    @BeforeEach void fixture() {
        String url=System.getenv("QINGYE_TEST_URL");
        if(url!=null && !url.matches("jdbc:mysql://[^/]+/qingye_test(?:\\?.*)?"))
            throw new IllegalStateException("Test database must be qingye_test");
        for(String table:List.of("message_task","notification","loan","registration","activity","club_member","club","equipment","app_user"))
            sql.update("DELETE FROM "+table);
        admin=sql.insert("INSERT INTO app_user(openid,name,admin) VALUES ('demo:admin','admin',TRUE)");
        manager=sql.insert("INSERT INTO app_user(openid,name) VALUES ('demo:manager','manager')");
        club=sql.insert("INSERT INTO club(name,created_by) VALUES ('owned club',?)",admin);
        sql.insert("INSERT INTO club_member(club_id,user_id,role,status) VALUES (?,?,'MANAGER','ACTIVE')",club,manager);
        var now=LocalDateTime.now(clock);
        activity=sql.insert("INSERT INTO activity(club_id,created_by,title,category,location,start_time,end_time,signup_deadline,capacity,status) VALUES (?,?,?,'ART','fixture',?,?,?,?,?)",
                club,manager,"owned contention",now.plusDays(2),now.plusDays(2).plusHours(2),now.plusDays(1),1,"PENDING");
    }

    @Test void queuedDecisionCannotUseRevokedAdministratorSnapshot() throws Exception {
        queued(admin,"decision","UPDATE app_user SET admin=FALSE WHERE id="+admin,403);
    }
    @Test void queuedDecisionCannotUseDisabledAdministratorSnapshot() throws Exception {
        queued(admin,"decision","UPDATE app_user SET enabled=FALSE WHERE id="+admin,403);
    }
    @Test void queuedCancellationCannotUseRevokedAdministratorShortcut() throws Exception {
        queued(admin,"cancel","UPDATE app_user SET admin=FALSE WHERE id="+admin,403);
    }
    @Test void queuedManagerCancellationStillRejectsRemovedMembership() throws Exception {
        queued(manager,"cancel","UPDATE club_member SET role='MEMBER' WHERE club_id="+club+" AND user_id="+manager,403);
    }
    @Test void queuedDecisionWithUnchangedAuthorityStillSucceeds() throws Exception {
        queued(admin,"decision",null,200);
    }

    @Test void cancellationRollsBackEarlierWritesWhenAuthorityChangesDuringLaterEquipmentWait() throws Exception {
        sql.update("UPDATE activity SET status='PUBLISHED' WHERE id=?",activity);
        long equipment=sql.insert("INSERT INTO equipment(name,category,total_quantity) VALUES ('owned camera','fixture',2)");
        var now=LocalDateTime.now(clock);
        long loan=sql.insert("INSERT INTO loan(activity_id,applicant_id,equipment_id,quantity,planned_start,planned_end,request_key,status) VALUES (?,?,?,1,?,?,?,'PENDING')",
                activity,manager,equipment,now.plusHours(1),now.plusHours(2),"owned-later-wait");
        var before=sql.one("SELECT * FROM activity WHERE id=?",activity);
        String token=auth.demo(admin).get("token").toString();
        var reachedEquipment=new CountDownLatch(1);
        doAnswer(call->{reachedEquipment.countDown();return call.callRealMethod();}).when(loans).equipment(equipment,true);
        var pool=Executors.newSingleThreadExecutor();
        try(var held=datasource.getConnection()) {
            held.setAutoCommit(false);
            try(var lock=held.prepareStatement("SELECT id FROM equipment WHERE id=? FOR UPDATE")) {
                lock.setLong(1,equipment);
                try(var row=lock.executeQuery()) { assertThat(row.next()).isTrue(); }
            }
            var response=pool.submit(()->mvc.perform(post("/api/activities/"+activity+"/cancel")
                    .header("Authorization","Bearer "+token)).andReturn());
            assertThat(reachedEquipment.await(5,TimeUnit.SECONDS)).isTrue();
            observeMysqlWait(held,"EQUIPMENT",equipment);
            assertThat(response.isDone()).isFalse();
            sql.update("UPDATE app_user SET admin=FALSE WHERE id=?",admin);
            held.commit();
            assertThat(response.get(5,TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(403);
            assertThat(sql.one("SELECT * FROM activity WHERE id=?",activity)).isEqualTo(before);
            assertThat(sql.one("SELECT status FROM loan WHERE id=?",loan).get("status")).isEqualTo("PENDING");
            assertThat(sql.count("SELECT COUNT(*) FROM notification")).isZero();
            assertThat(sql.count("SELECT COUNT(*) FROM message_task")).isZero();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();
        }
    }

    private void queued(long actor,String action,String revoke,int expected) throws Exception {
        if(action.equals("cancel")) sql.update("UPDATE activity SET status='PUBLISHED' WHERE id=?",activity);
        var before=sql.one("SELECT * FROM activity WHERE id=?",activity);
        long notices=sql.count("SELECT COUNT(*) FROM notification");
        long tasks=sql.count("SELECT COUNT(*) FROM message_task");
        String token=auth.demo(actor).get("token").toString();
        CountDownLatch reachedLock=new CountDownLatch(1);
        doAnswer(call->{reachedLock.countDown();return call.callRealMethod();}).when(activities).lock(activity);
        var pool=Executors.newSingleThreadExecutor();
        try(var held=datasource.getConnection()) {
            held.setAutoCommit(false);
            try(var lock=held.prepareStatement("SELECT id FROM activity WHERE id=? FOR UPDATE")) {
                lock.setLong(1,activity);
                try(var row=lock.executeQuery()) { assertThat(row.next()).isTrue(); }
            }
            var response=pool.submit(()->mvc.perform(post("/api/activities/"+activity+"/"+action)
                    .header("Authorization","Bearer "+token).contentType("application/json")
                    .content(action.equals("decision")?"{\"approve\":true,\"note\":\"queued\"}":"")).andReturn());
            assertThat(reachedLock.await(5,TimeUnit.SECONDS)).isTrue();
            observeMysqlWait(held,"ACTIVITY",activity);
            assertThat(response.isDone()).isFalse();
            if(revoke!=null) sql.update(revoke); // separate autocommit connection, before releasing the held row
            held.commit();
            assertThat(response.get(5,TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(expected);
            if(expected==403) {
                assertThat(sql.one("SELECT * FROM activity WHERE id=?",activity)).isEqualTo(before);
                assertThat(sql.count("SELECT COUNT(*) FROM notification")).isEqualTo(notices);
                assertThat(sql.count("SELECT COUNT(*) FROM message_task")).isEqualTo(tasks);
            } else assertThat(sql.one("SELECT status FROM activity WHERE id=?",activity).get("status")).isEqualTo("PUBLISHED");
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();
        }
    }
    private void observeMysqlWait(java.sql.Connection held,String table,long id) throws Exception {
        if(!held.getMetaData().getURL().startsWith("jdbc:mysql:")) return;
        // Observe the actual SQL wait; a sleep alone cannot establish the interleaving.
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        boolean observed=false;
        while(System.nanoTime()<deadline) {
            observed=sql.list("SHOW FULL PROCESSLIST").stream().anyMatch(row->{
                String query=String.valueOf(row.get("info")).toUpperCase();
                return query.contains("FROM "+table) && query.contains("FOR UPDATE") && query.contains(Long.toString(id));
            });
            if(observed) break;
            Thread.sleep(20);
        }
        assertThat(observed).as("real MySQL "+table+" lock wait").isTrue();
    }
}
