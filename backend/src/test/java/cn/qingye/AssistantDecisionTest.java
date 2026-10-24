package cn.qingye;
import cn.qingye.business.*;
import cn.qingye.db.*;
import cn.qingye.integration.LlmPlanner;
import cn.qingye.model.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantDecisionTest {
    final Actor student=new Actor(3,"张三1",false),admin=new Actor(8,"张三",true);
    final Clock clock=Clock.fixed(Instant.parse("2026-10-07T17:00:00Z"),ZoneId.of("Asia/Shanghai"));
    final ActivityStore activities=mock(ActivityStore.class);final LoanStore loans=mock(LoanStore.class);
    final LoanService capacity=mock(LoanService.class);final LlmPlanner planner=mock(LlmPlanner.class);
    final AssistantService service=new AssistantService(activities,loans,capacity,planner,clock);
    Map<String,Object> run(QueryPlan p,String text) {
        var preview=service.query(student,p,"s");
        assertThat(preview).containsEntry("status","CONFIRM_SCOPE");return service.confirm(student,preview.get("confirmationToken").toString(),"s");
    }
    Map<String,Object> equipment(long id,String name){return Map.of("id",id,"name",name,"totalQuantity",5,"borrowedQuantity",0,"enabled",true);}
    @Test void everyQueryWaitsWithoutReadingFacts() {
        for(var p:List.of(QueryPlan.query("ACTIVITIES",null,null,"TODAY"),QueryPlan.query("EQUIPMENT","ALL",null,"CURRENT"),QueryPlan.query("MY_REGISTRATIONS",null,null,"ANY"),QueryPlan.query("MY_LOANS",null,null,"ANY"))) {

            assertThat(service.query(student,p,"s")).containsEntry("status","CONFIRM_SCOPE").containsEntry("items",List.of());
        }
        verifyNoInteractions(activities,loans,capacity);
    }
    @Test void sameSubjectSessionPlanOnceAndNoReplanning() {
        var p=QueryPlan.query("MY_LOANS",null,null,"ANY");
        var preview=service.query(student,p,"s");String token=preview.get("confirmationToken").toString();

        assertThat(service.more(student,token,"s")).containsEntry("status","CLARIFY");
        assertThat(service.confirm(admin,token,"s")).containsEntry("status","CLARIFY");
        assertThat(service.confirm(student,token,"another")).containsEntry("status","CLARIFY");
        assertThat(service.confirm(student,token,"s")).containsEntry("status","QUERY");
        assertThat(service.confirm(student,token,"s")).containsEntry("status","CLARIFY");
        verifyNoInteractions(planner);verify(loans).assistantMine(eq(student.id()),eq(p),isNull(),isNull(),any(),eq(0));verify(loans,never()).list(any());
    }
    @Test void duplicatesAreCandidatesAndUnknownEquipmentDoesNotExpandToAll() {
        when(loans.assistantEquipment("相机",0)).thenReturn(List.of(equipment(1,"相机"),equipment(2,"相机")));
        var result=run(QueryPlan.query("EQUIPMENT","相机",null,"CURRENT"),"相机在哪里");
        assertThat((List<?>)result.get("items")).hasSize(2);assertThat(result).containsEntry("selectionRequired",true);
        assertThat(run(QueryPlan.query("EQUIPMENT","机相",null,"CURRENT"),"查机相器材")).containsEntry("items",List.of());
        verify(loans,never()).assistantEquipment(eq("ALL"),anyInt());
        run(QueryPlan.query("EQUIPMENT","ALL",null,"CURRENT"),"查全部器材");verify(loans).assistantEquipment("ALL",0);
    }
    @Test void unavailableModelNeverGuessesCanonicalMessagesAndExplicitClarifyIsNeverOverridden() {
        when(planner.locate(anyString())).thenReturn(Optional.empty());
        for(String q:List.of("查相机？","查那个相机","我的借用记录和器材库存","手机不用管，查相机","查相机，不对，查摄像机")) assertThat(service.ask(student,q,"s")).containsEntry("status","SYSTEM_ERROR").containsEntry("reason","MODEL_UNAVAILABLE").containsKey("retryToken");
        when(planner.locate(anyString())).thenReturn(Optional.of(AssistantHint.clarify(null,null)));
        assertThat(service.ask(student,"查相机","s")).containsEntry("status","CLARIFY");verifyNoInteractions(activities,loans,capacity);
    }
    @Test void historyDoesNotReplaceTheCurrentModelPlan() {
        String q="我之前借用相机，现在只查投影仪库存";run(QueryPlan.query("EQUIPMENT","投影仪",null,"CURRENT"),q);
        verifyNoInteractions(planner);verify(loans).assistantEquipment("投影仪",0);verify(loans,never()).assistantEquipment(eq("相机"),anyInt());
    }
    @Test void newQuestionAndExactExpiryInvalidateConsent() {
        var now=new AtomicReference<>(clock.instant());var timed=new AssistantService(activities,loans,capacity,planner,ticking(now));

        var first=timed.query(student,QueryPlan.query("MY_LOANS",null,null,"ANY"),"s");timed.ask(student,"帮我批准器材预约","s");
        assertThat(timed.confirm(student,first.get("confirmationToken").toString(),"s")).containsEntry("status","CLARIFY");
        var second=timed.query(student,QueryPlan.query("MY_LOANS",null,null,"ANY"),"s");now.set(now.get().plusSeconds(300));
        assertThat(timed.confirm(student,second.get("confirmationToken").toString(),"s")).containsEntry("status","CLARIFY");verifyNoInteractions(activities,loans,capacity);
    }
    Clock ticking(AtomicReference<Instant> now){return new Clock(){public ZoneId getZone(){return clock.getZone();}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now.get();}};}
    @Test void midnightKeepsTheDisplayedDate() {
        var now=new AtomicReference<>(Instant.parse("2026-10-07T15:59:50Z"));var timed=new AssistantService(activities,loans,capacity,planner,ticking(now));
        var p=QueryPlan.query("ACTIVITIES",null,null,"TODAY");
        var preview=timed.query(student,p,"s");assertThat(preview.get("answer").toString()).contains("2026-10-07");
        now.set(now.get().plusSeconds(20));timed.confirm(student,preview.get("confirmationToken").toString(),"s");
        verify(activities).assistant(eq(student),eq(p),eq(LocalDateTime.of(2026,10,7,0,0)),eq(LocalDateTime.of(2026,10,8,0,0)),any(),eq(0));
    }
    @Test void lateModelResponseCannotReviveOldConsent() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        when(planner.locate(anyString())).thenAnswer(i->{entered.countDown();release.await(3,TimeUnit.SECONDS);return Optional.of(AssistantHint.locate("MY_LOANS",null));});
        try{var old=pool.submit(()->service.ask(student,"模糊的借用请求","s"));assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();service.ask(student,"帮我批准器材预约","s");release.countDown();assertThat(old.get(2,TimeUnit.SECONDS)).containsEntry("status","CLARIFY");verifyNoInteractions(activities,loans,capacity);}
        finally{release.countDown();pool.shutdownNow();}
    }
    @Test void weekendAvailabilityDoesNotMutateCatalog() {
        var cached=equipment(1,"相机");when(loans.assistantEquipment("相机",0)).thenReturn(List.of(cached));when(capacity.availability(anyLong(),any(),any())).thenReturn(Map.of("availableQuantity",2));
        var r=run(QueryPlan.query("EQUIPMENT","相机",null,"WEEKEND"),"周末相机能借吗");
        verify(capacity).availability(1,LocalDateTime.of(2026,10,10,0,0),LocalDateTime.of(2026,10,12,0,0));assertThat(r.get("answer").toString()).contains("不保证届时可借");
        assertThat((List<?>)r.get("warnings")).anySatisfy(warning->assertThat(warning.toString()).contains("不保证届时可借"));assertThat(cached).doesNotContainKey("availability");
    }
    @Test void malformedAndUnsupportedFacetsNeverExecuteOrDropConditions() {
        for(var p:List.of(new QueryPlan("QUERY","ACTIVITIES",null,null,"DATE","2026-02-30","ANY",null,null),
            new QueryPlan("QUERY","EQUIPMENT","相机","ART","CURRENT",null,"ANY",null,null),
            new QueryPlan("QUERY","MY_LOANS",null,null,"ANY",null,"DROP TABLE","东操场",null),
            QueryPlan.query("ACTIVITIES","x".repeat(81),null,"ANY"),QueryPlan.query("EQUIPMENT",null,null,"CURRENT")))
            assertThat(service.query(student,p,"s")).containsEntry("status","CLARIFY");
        for(String day:List.of("2026-10-01","2030-01-01"))assertThat(service.query(student,new QueryPlan("QUERY","EQUIPMENT","相机",null,"DATE",day,"ANY",null,null),"s")).containsEntry("status","UNSUPPORTED");
        verifyNoInteractions(planner,activities,loans,capacity);
    }
}
