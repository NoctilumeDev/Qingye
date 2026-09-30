package cn.qingye;
import cn.qingye.api.Forms;
import cn.qingye.business.*;
import cn.qingye.db.*;
import cn.qingye.integration.*;
import cn.qingye.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static cn.qingye.db.Rows.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties= {
    "qingye.demo-enabled=true","qingye.worker-enabled=false","spring.datasource.url=${QINGYE_TEST_URL:jdbc:h2:mem:qingye;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}","spring.datasource.username=${QINGYE_TEST_USER:sa}","spring.datasource.password=${QINGYE_TEST_PASSWORD:}"
})
@AutoConfigureMockMvc
@Import(BusinessIntegrationTest.TimeConfig.class)
class BusinessIntegrationTest {
    @Autowired Sql sql;
    @Autowired ActivityService activities;
    @Autowired ClubService clubs;
    @Autowired LoanService loans;
    @Autowired LoanStore loanStore;
    @Autowired MessageStore messages;
    @Autowired TaskService tasks;
    @Autowired AuthService auth;
    @Autowired UserStore users;
    @Autowired ActivityStore activityStore;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;
    Actor admin,manager,secondManager,student,other;
    long clubA,clubB,activityA,activityB,equipment;
    private static final LocalDateTime NOW=LocalDateTime.of(2026,10,1,8,0);
    @TestConfiguration static class TimeConfig {
        @Bean @Primary MutableClock mutableClock() {
            return new MutableClock();
        }
    }
    static class MutableClock extends Clock {
        private final AtomicReference<Instant> time=new AtomicReference<>(NOW.atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        void set(LocalDateTime value) {
            time.set(value.atZone(getZone()).toInstant());
        }
        public ZoneId getZone() {
            return ZoneId.of("Asia/Shanghai");
        }
        public Clock withZone(ZoneId zone) {
            return this;
        }
        public Instant instant() {
            return time.get();
        }
    }
    @BeforeEach void fixture() {
        // Only the disposable H2 database or explicitly named qingye_test may be reset.
        String configured=System.getenv("QINGYE_TEST_URL");
        if(configured!=null && !configured.matches("jdbc:mysql://[^/]+/qingye_test(?:\\?.*)?")) throw new IllegalStateException("Test database must be qingye_test");
        for(String table:List.of("message_task","notification","loan","registration","activity","club_member","club","equipment","app_user")) sql.update("DELETE FROM "+table);
        clock.set(NOW);
        admin=user("admin",true);
        manager=user("manager-a",false);
        secondManager=user("manager-b",false);
        student=user("student",false);
        other=user("other",false);
        clubA=club("摄影社",manager);
        clubB=club("篮球社",secondManager);
        sql.insert("INSERT INTO club_member(club_id,user_id,role,status) VALUES (?,?,'MEMBER','ACTIVE')",clubB,manager.id());
        activityA=activity(clubA,manager);
        activityB=activity(clubB,secondManager);
        equipment=loans.createEquipment(admin,new Forms.Equipment("相机","摄影","","",5,true));
    }
    private Actor user(String name,boolean isAdmin) {
        long id=sql.insert("INSERT INTO app_user(openid,name,admin) VALUES (?,?,?)","demo:"+name,name,isAdmin);
        return users.actor(id);
    }
    private long club(String name,Actor manager) {
        long id=sql.insert("INSERT INTO club(name,created_by) VALUES (?,?)",name,admin.id());
        sql.insert("INSERT INTO club_member(club_id,user_id,role,status) VALUES (?,?,'MANAGER','ACTIVE')",id,manager.id());
        return id;
    }
    private long activity(long club,Actor manager) {
        return sql.insert("INSERT INTO activity(club_id,created_by,title,category,location,start_time,end_time,signup_deadline,capacity,status) VALUES (?,?,?,?,?,?,?,?,?,?)",club,manager.id(),"校园摄影活动","ART","东操场",NOW.plusHours(10),NOW.plusHours(12),NOW.plusHours(8),1,"PUBLISHED");
    }
    private long apply(Actor manager,long activity,int quantity,int start,int end) {
        return loans.apply(manager,new Forms.Loan(activity,equipment,quantity,NOW.withHour(start),NOW.withHour(end),"测试",UUID.randomUUID().toString()));
    }
    private void approve(long id) {
        loans.decide(admin,id,new Forms.Decision(true,""));
    }
    @Test void lastSeatHasOneRegistrationAndCancellationPromotesEarliestWaiter() throws Exception {
        var result=concurrently(()->activities.register(student,activityA),()->activities.register(other,activityA));
        assertThat(result).allMatch(x->x instanceof Map);
        assertThat(sql.count("SELECT COUNT(*) FROM registration WHERE status='REGISTERED'")).isEqualTo(1);
        assertThat(sql.count("SELECT COUNT(*) FROM registration WHERE status='WAITLISTED'")).isEqualTo(1);
        long winner=id(sql.one("SELECT user_id FROM registration WHERE status='REGISTERED'"),"userId");
        long waiter=id(sql.one("SELECT user_id FROM registration WHERE status='WAITLISTED'"),"userId");
        clock.set(NOW.plusHours(9));
        // Deadline passed; existing waiters can still be promoted before start.
        activities.cancelRegistration(users.actor(winner),activityA);
        assertThat(sql.count("SELECT COUNT(*) FROM registration WHERE user_id=? AND status='REGISTERED'",waiter)).isEqualTo(1);
        activities.cancelRegistration(users.actor(winner),activityA);
        assertThat(sql.count("SELECT COUNT(*) FROM registration WHERE status='REGISTERED'")).isEqualTo(1);
    }
    @Test void adjacentReservationsApproveButGenuineOverlapIsRejected() {
        approve(apply(manager,activityA,3,14,15));
        approve(apply(secondManager,activityB,3,15,16));
        approve(apply(manager,activityA,2,14,16));
        assertThat(loans.availability(equipment,NOW.withHour(14),NOW.withHour(16)).get("peakOccupied")).isEqualTo(5);
        long impossible=apply(secondManager,activityB,1,14,16);
        assertThatThrownBy(()->approve(impossible)).isInstanceOf(Problem.class).hasMessageContaining("最多还能预约");
        assertThat(text(loanStore.loan(impossible,false),"status")).isEqualTo("PENDING");
    }
    @Test void concurrentApprovalsAcrossDifferentActivitiesShareEquipmentLock() throws Exception {
        long a=apply(manager,activityA,3,14,16),b=apply(secondManager,activityB,3,14,16);
        var result=concurrently(()-> {
            approve(a);return true;
        },()-> {
            approve(b);return true;
        });
        assertThat(result.stream().filter(Boolean.TRUE::equals).count()).isEqualTo(1);
        assertThat(result.stream().filter(Problem.class::isInstance).count()).isEqualTo(1);
        assertThat(sql.count("SELECT SUM(quantity) FROM loan WHERE status='APPROVED'")).isEqualTo(3);
    }
    @Test void overdueLoanBlocksPhysicalPickupUntilActualReturn() {
        long a=apply(manager,activityA,3,14,15),b=apply(secondManager,activityB,3,15,16);
        approve(a);
        approve(b);
        clock.set(NOW.withHour(14));
        loans.checkout(admin,a);
        clock.set(NOW.withHour(15));
        assertThatThrownBy(()->loans.checkout(admin,b)).isInstanceOf(Problem.class).hasMessageContaining("实物仅剩 2");
        assertThat(text(loanStore.loan(b,false),"status")).isEqualTo("APPROVED");
        loans.returned(admin,a);
        loans.returned(admin,a);
        loans.checkout(admin,b);
        loans.checkout(admin,b);
        assertThat(loanStore.borrowed(equipment)).isEqualTo(3);
    }
    @Test void membershipPermissionIsSpecificToClubAndAdminDecisionIsProtected() {
        assertThatThrownBy(()->apply(manager,activityB,1,14,15)).isInstanceOf(Problem.class).hasMessageContaining("权限");
        assertThatThrownBy(()->apply(student,activityA,1,14,15)).isInstanceOf(Problem.class);
        long a=apply(manager,activityA,1,14,15);
        assertThatThrownBy(()->loans.decide(manager,a,new Forms.Decision(true,""))).isInstanceOf(Problem.class);
    }
    @Test void quantityReductionCannotInvalidateApprovedCapacity() {
        approve(apply(manager,activityA,5,14,15));
        assertThatThrownBy(()->loans.editEquipment(admin,equipment,new Forms.Equipment("相机","摄影","","",4,true))).isInstanceOf(Problem.class);
        assertThat(integer(loanStore.equipment(equipment,false),"totalQuantity")).isEqualTo(5);
    }
    @Test void managerHandoverKeepsAtLeastOneActiveManager() {
        assertThatThrownBy(()->clubs.decide(admin,clubA,manager.id(),new Forms.Member(true,"MEMBER"))).isInstanceOf(Problem.class);
        clubs.join(student,clubA);
        clubs.decide(admin,clubA,student.id(),new Forms.Member(true,"MANAGER"));
        clubs.decide(admin,clubA,manager.id(),new Forms.Member(true,"MEMBER"));
        assertThatThrownBy(()->apply(manager,activityA,1,14,15)).isInstanceOf(Problem.class);
    }
    @Test void cancellationReleasesReservationsButKeepsCheckedOutFacts() {
        long a=apply(manager,activityA,2,14,15),b=apply(manager,activityA,2,15,16);
        approve(a);
        approve(b);
        clock.set(NOW.withHour(14));
        loans.checkout(admin,a);
        activities.cancel(manager,activityA);
        assertThat(text(loanStore.loan(a,false),"status")).isEqualTo("CHECKED_OUT");
        assertThat(text(loanStore.loan(b,false),"status")).isEqualTo("CANCELLED");
        loans.returned(admin,a);
        assertThat(loanStore.borrowed(equipment)).isZero();
    }
    @Test void requestKeyAndNotificationDeliveryAreIdempotent() {
        var request=new Forms.Loan(activityA,equipment,1,NOW.withHour(14),NOW.withHour(15),"",UUID.randomUUID().toString());
        long id=loans.apply(manager,request);
        assertThat(loans.apply(manager,request)).isEqualTo(id);
        activities.register(student,activityA);
        for(var task:messages.pending(NOW)) {
            tasks.complete(id(task,"id"));
            tasks.complete(id(task,"id"));
        }
        assertThat(messages.list(student.id())).hasSize(1);
        assertThat(sql.count("SELECT COUNT(*) FROM message_task WHERE status='DONE'")).isEqualTo(1);
    }
    @Test void disabledMqUsesLocalTaskWorkerAndBrokenRedisReturnsDatabaseRows() {
        activities.register(student,activityA);
        var rabbit=mock(RabbitBridge.class);
        when(rabbit.publish(anyLong())).thenReturn(false);
        new TaskWorker(messages,rabbit,tasks,clock).tick();
        assertThat(messages.list(student.id())).hasSize(1);
        var redis=mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("offline"));
        var cache=new QueryCache(redis,json,true);
        var actual=List.<Map<String,Object>>of(Map.of("id",123));
        assertThat(cache.publicList("test",()->actual)).isEqualTo(actual);
        assertThat(cache.mode()).isEqualTo("DB_FALLBACK");
    }
    @Test void fourGatesRejectUnsupportedScopeAndPrivateQueryUsesSessionIdentity() {
        activities.register(student,activityA);
        var planner=mock(LlmPlanner.class);
        when(planner.plan(anyString())).thenReturn(Optional.of(new QueryPlan("MY_REGISTRATIONS",null,null,null,null)));
        var service=new AssistantService(activityStore,loanStore,loans,planner,clock);
        assertThat(service.ask(other,"我的报名活动").get("items")).isEqualTo(List.of());
        reset(planner);
        service.ask(student,"给我写一道数学题");
        verifyNoInteractions(planner);
        assertThat(service.valid(new QueryPlan("DELETE_ALL",null,null,null,null))).isFalse();
    }
    @Test void httpAuthenticationValidationAndClubAuthorizationAreEnforced() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        String token=auth.demo(student.id()).get("token").toString();
        mvc.perform(get("/api/equipment/"+equipment+"/availability").header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/clubs/"+clubA+"/members").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(post("/api/loans").header("Authorization","Bearer "+token).contentType("application/json").content("{\"quantity\":-1}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/me").header("Authorization","Bearer "+token+"x")).andExpect(status().isUnauthorized());
    }
    private List<Object> concurrently(Supplier<?> left,Supplier<?> right) throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        var ready=new CountDownLatch(2);
        var go=new CountDownLatch(1);
        try {
            var futures=new ArrayList<Future<Object>>();
            for(var job:List.of(left,right)) futures.add(pool.submit(()-> {
                ready.countDown();go.await();try {
                    return job.get();
                }
                catch(Problem e) {
                    return e;
                }
            }));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();
            go.countDown();
            var result=new ArrayList<Object>();
            for(var future:futures) result.add(future.get(10,TimeUnit.SECONDS));
            return result;
        }
        finally {
            pool.shutdownNow();
        }
    }
}
