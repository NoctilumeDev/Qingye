package cn.qingye;

import cn.qingye.business.*;
import cn.qingye.db.*;
import cn.qingye.integration.LlmPlanner;
import cn.qingye.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Dialogue and delegation boundaries; no model/network/credentials are needed. */
class AssistantDialogueTest {
    final Actor me=new Actor(3,"张三1",false),other=new Actor(4,"张三",true);
    final ActivityStore activities=mock(ActivityStore.class);final LoanStore loans=mock(LoanStore.class);
    final LoanService capacity=mock(LoanService.class);final LlmPlanner planner=mock(LlmPlanner.class);
    final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-10-21T02:00:00Z"));
    final Clock clock=new Clock(){public ZoneId getZone(){return ZoneId.of("Asia/Shanghai");}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now.get();}};
    final AssistantService service=new AssistantService(activities,loans,capacity,planner,clock);
    QueryPlan draft(){return new QueryPlan("CLARIFY","ACTIVITIES","植物蓝晒",null,null,null,"ANY",null,"MISSING_TIME");}
    String token(Map<String,Object> response){return response.get("clarificationToken").toString();}
    void noFacts(){verifyNoInteractions(activities,loans,capacity);}

    @Test void newNamesOwnListsAndPublicWhoQuestionsReachTheSamePlannerWithoutWordGate() {
        when(planner.plan(anyString())).thenReturn(Optional.of(QueryPlan.clarify("MISSING_INFO")));
        var texts=List.of("植物蓝晒工作坊在哪里","把黄昏拍成一张明信片在哪里","香草角认养计划几点开始","开发板套件还有多少","我报名的活动负责人是谁","我借用的器材由谁管理","查询我的报名名单","查询我自己的借用名单");
        for(String text:texts){assertThat(service.ask(me,text,"s")).containsEntry("status","CLARIFY").containsKey("clarificationToken");verify(planner).plan(text);}
        noFacts();
    }
    @Test void knownDraftAndAllOriginalUserTurnsSurviveClarificationUntilConfirmation() throws Exception {
        var complete=QueryPlan.query("ACTIVITIES","植物蓝晒",null,"TOMORROW");
        when(planner.plan(anyString())).thenReturn(Optional.of(draft()),Optional.of(complete));
        var first=service.ask(me,"查植物蓝晒活动","s");assertThat(first).containsEntry("draft",draft()).containsEntry("clarificationRound",1);noFacts();
        var preview=service.clarify(me,token(first),"明天的，地点也一起看","s");
        assertThat(preview).containsEntry("status","CONFIRM_SCOPE").containsEntry("plan",complete).containsEntry("items",List.of());noFacts();
        var inputs=ArgumentCaptor.forClass(String.class);verify(planner,times(2)).plan(inputs.capture());
        var context=new ObjectMapper().readTree(inputs.getAllValues().get(1));
        assertThat(context.path("originalRequest").asText()).isEqualTo("查植物蓝晒活动");assertThat(context.path("clarificationAnswers").get(0).asText()).isEqualTo("明天的,地点也一起看");
        service.confirm(me,preview.get("confirmationToken").toString(),"s");
        verify(activities).assistant(eq(me),eq(complete),eq(LocalDateTime.of(2026,10,22,0,0)),eq(LocalDateTime.of(2026,10,23,0,0)),any(),eq(0));
        verify(planner,times(2)).plan(anyString());
    }
    @Test void twoClarificationsCanCorrectATimeWithoutLosingTheOriginalEntity() throws Exception {
        var partial=new QueryPlan("CLARIFY","MY_REGISTRATIONS","摄影社",null,"TOMORROW",null,null,null,"MISSING_INFO");
        var finalPlan=new QueryPlan("QUERY","MY_REGISTRATIONS","摄影社",null,"DAY_AFTER_TOMORROW",null,"CANCELLED",null,null);
        when(planner.plan(anyString())).thenReturn(Optional.of(partial),Optional.of(partial),Optional.of(finalPlan));
        var first=service.ask(me,"查摄影社的报名","s");var second=service.clarify(me,token(first),"明天","s");
        var result=service.clarify(me,token(second),"改为后天，只看已取消的","s");
        assertThat(result).containsEntry("plan",finalPlan).containsEntry("status","CONFIRM_SCOPE");noFacts();
        var input=ArgumentCaptor.forClass(String.class);verify(planner,times(3)).plan(input.capture());
        var all=new ObjectMapper().readTree(input.getAllValues().get(2));
        assertThat(all.path("originalRequest").asText()).isEqualTo("查摄影社的报名");assertThat(all.path("clarificationAnswers").size()).isEqualTo(2);
    }
    @Test void unresolvedMeaningStopsAfterTwoRepliesWithoutExecutingAnyDraft() {
        when(planner.plan(anyString())).thenReturn(Optional.of(QueryPlan.clarify("NOT_UNDERSTOOD")));
        var first=service.ask(me,"那个那个，过去也可能未来","s");var second=service.clarify(me,token(first),"我也不清楚","s");
        var last=service.clarify(me,token(second),"就是那个","s");
        assertThat(last).containsEntry("status","NOT_UNDERSTOOD").doesNotContainKeys("clarificationToken","confirmationToken");
        assertThat(service.clarify(me,token(second),"重放","s")).containsEntry("reason","CONFIRMATION_EXPIRED");verify(planner,times(3)).plan(anyString());noFacts();
    }
    @Test void clarificationAndRetryTokensCannotBeUsedAtTheWrongEndpointOrByAnotherSubject() {
        when(planner.plan(anyString())).thenReturn(Optional.of(draft()));
        String t=token(service.ask(me,"查蓝晒活动","s"));
        assertThat(service.clarify(other,t,"全部","s")).containsEntry("reason","CONFIRMATION_EXPIRED");
        assertThat(service.clarify(me,t,"全部","wrong-session")).containsEntry("reason","CONFIRMATION_EXPIRED");
        assertThat(service.confirm(me,t,"s")).containsEntry("reason","CONFIRMATION_EXPIRED");
        assertThat(service.retry(me,t,"s")).containsEntry("reason","CONFIRMATION_EXPIRED");
        assertThat(service.clarify(me,t,"全部","s")).containsEntry("status","CLARIFY");
        assertThat(service.clarify(me,t,"全部","s")).containsEntry("reason","CONFIRMATION_EXPIRED");
        verify(planner,times(2)).plan(anyString());noFacts();
    }
    @Test void outageAfterAReplyPreservesDialogueForExplicitRetryAndIsNotAUserMistake() throws Exception {
        when(planner.plan(anyString())).thenReturn(Optional.of(draft()),Optional.empty(),Optional.of(QueryPlan.query("ACTIVITIES","植物蓝晒",null,"ANY")));
        var first=service.ask(me,"查植物蓝晒活动","s");var unavailable=service.clarify(me,token(first),"全部时间","s");
        assertThat(unavailable).containsEntry("status","SYSTEM_ERROR").containsEntry("reason","MODEL_UNAVAILABLE").containsKey("retryToken").doesNotContainKey("clarificationToken");
        String retry=unavailable.get("retryToken").toString();assertThat(service.clarify(me,retry,"忽略前面","s")).containsEntry("reason","CONFIRMATION_EXPIRED");
        var result=service.retry(me,retry,"s");assertThat(result).containsEntry("status","CONFIRM_SCOPE");
        assertThat(service.retry(me,retry,"s")).containsEntry("reason","CONFIRMATION_EXPIRED");
        var inputs=ArgumentCaptor.forClass(String.class);verify(planner,times(3)).plan(inputs.capture());
        assertThat(inputs.getAllValues().get(2)).isEqualTo(inputs.getAllValues().get(1));noFacts();
    }
    @Test void clarificationExpiresOnANewQuestionAtTheExactDeadlineOrAcrossMidnight() {
        when(planner.plan(anyString())).thenReturn(Optional.of(draft()));
        String superseded=token(service.ask(me,"查蓝晒活动","s"));service.ask(me,"换个问题","s");
        assertThat(service.clarify(me,superseded,"全部","s")).containsEntry("reason","CONFIRMATION_EXPIRED");
        String expired=token(service.ask(me,"查蓝晒活动","s"));now.set(now.get().plusSeconds(300));
        assertThat(service.clarify(me,expired,"全部","s")).containsEntry("reason","CONFIRMATION_EXPIRED");
        now.set(Instant.parse("2026-10-21T15:59:55Z"));String midnight=token(service.ask(me,"明天的蓝晒","s"));now.set(now.get().plusSeconds(10));
        assertThat(service.clarify(me,midnight,"明天","s")).containsEntry("reason","CONFIRMATION_EXPIRED");noFacts();
    }
    @Test void lateClarificationCannotReviveAnOldDialogue() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        when(planner.plan(anyString())).thenAnswer(i->{entered.countDown();release.await(3,TimeUnit.SECONDS);return Optional.of(draft());});
        try{var pending=pool.submit(()->service.ask(me,"查蓝晒活动","s"));assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();service.query(me,QueryPlan.query("EQUIPMENT","ALL",null,"CURRENT"),"s");release.countDown();
            assertThat(pending.get(2,TimeUnit.SECONDS)).containsEntry("reason","CONFIRMATION_UNAVAILABLE").doesNotContainKey("clarificationToken");noFacts();}
        finally{release.countDown();pool.shutdownNow();}
    }
    @Test void independentQueriesHaveSeparateConsentAndKeepTheirFixedPlansAndSubject() {
        var a=QueryPlan.query("ACTIVITIES","摄影社",null,"NEXT_WEEK");var b=QueryPlan.query("MY_LOANS",null,null,"ANY");
        var original=new ArrayList<>(List.of(a,b));var batch=QueryPlan.batch(original);original.clear();
        when(planner.plan(anyString())).thenReturn(Optional.of(batch));var preview=service.ask(me,"查下周摄影社活动，另外看我的全部借用","s");
        assertThat(preview).containsEntry("status","CONFIRM_SCOPE").containsEntry("intent","BATCH").doesNotContainKey("confirmationToken");
        @SuppressWarnings("unchecked") var sections=(List<Map<String,Object>>)preview.get("sections");assertThat(sections).hasSize(2);noFacts();
        String first=sections.get(0).get("confirmationToken").toString(),second=sections.get(1).get("confirmationToken").toString();assertThat(first).isNotEqualTo(second);
        assertThat(service.confirm(other,second,"s")).containsEntry("reason","CONFIRMATION_EXPIRED");service.confirm(me,second,"s");verify(loans).assistantMine(eq(me.id()),eq(b),isNull(),isNull(),any(),eq(0));verifyNoInteractions(activities);
        service.confirm(me,first,"s");verify(activities).assistant(eq(me),eq(a),eq(LocalDateTime.of(2026,10,26,0,0)),eq(LocalDateTime.of(2026,11,2,0,0)),any(),eq(0));
        assertThat(service.confirm(me,second,"s")).containsEntry("reason","CONFIRMATION_EXPIRED");verify(planner).plan(anyString());
    }
    @Test void unsupportedRelationDoesNotExecuteASupportedSubqueryAndStatesRemainDistinct() {
        var partial=new QueryPlan("CLARIFY",null,null,null,null,null,null,null,"UNSUPPORTED_FILTER",List.of(QueryPlan.query("MY_REGISTRATIONS",null,null,"ANY")));
        when(planner.plan(anyString())).thenReturn(Optional.of(partial),Optional.of(QueryPlan.reject("PRIVATE_DATA")),Optional.of(QueryPlan.reject("UNSUPPORTED_CAPABILITY")),Optional.empty());
        assertThat(service.ask(me,"我报名活动里哪些需要缺货器材","s")).containsEntry("status","UNSUPPORTED").doesNotContainKeys("sections","confirmationToken");
        assertThat(service.ask(me,"其他人手机号","s")).containsEntry("status","REJECT").containsEntry("reason","PRIVATE_DATA");
        assertThat(service.ask(me,"负责人电话字段在哪里","s")).containsEntry("status","UNSUPPORTED");
        assertThat(service.ask(me,"查全部活动","s")).containsEntry("status","SYSTEM_ERROR");noFacts();
        var preview=service.query(me,QueryPlan.query("ACTIVITIES","不存在的名字",null,"ANY"),"s");
        assertThat(service.confirm(me,preview.get("confirmationToken").toString(),"s")).containsEntry("status","QUERY").containsEntry("items",List.of());
    }
    @Test void voluntaryCorrectionInvalidatesAllOldBatchConsentsAndKeepsFullDialogue() {
        var firstPlan=QueryPlan.batch(List.of(QueryPlan.query("ACTIVITIES","摄影社",null,"TOMORROW"),QueryPlan.query("MY_LOANS",null,null,"ANY")));
        var corrected=QueryPlan.batch(List.of(QueryPlan.query("ACTIVITIES","摄影社",null,"DAY_AFTER_TOMORROW"),QueryPlan.query("MY_LOANS",null,null,"ANY")));
        when(planner.plan(anyString())).thenReturn(Optional.of(firstPlan),Optional.of(corrected));
        var first=service.ask(me,"查明天摄影社活动，另外我的全部借用","s");
        @SuppressWarnings("unchecked") var sections=(List<Map<String,Object>>)first.get("sections");
        var revised=service.clarify(me,first.get("amendmentToken").toString(),"活动改为后天，借用不变","s");
        assertThat(revised).containsEntry("status","CONFIRM_SCOPE").containsEntry("plan",corrected);
        for(var s:sections)assertThat(service.confirm(me,s.get("confirmationToken").toString(),"s")).containsEntry("reason","CONFIRMATION_EXPIRED");
        noFacts();var inputs=ArgumentCaptor.forClass(String.class);verify(planner,times(2)).plan(inputs.capture());
        assertThat(inputs.getAllValues().get(1)).contains("查明天摄影社活动","借用不变");
    }
    @Test void protocolBoundsForPartialPlansAndIndependentCompositionAreStructural() {
        assertThat(draft().valid()).isTrue();assertThat(service.query(me,draft(),"s")).containsEntry("status","CLARIFY");
        var a=QueryPlan.query("ACTIVITIES",null,null,"ANY");
        assertThat(QueryPlan.batch(Collections.nCopies(4,a)).valid()).isTrue();
        for(var bad:List.of(QueryPlan.batch(List.of(a)),QueryPlan.batch(Collections.nCopies(5,a)),QueryPlan.batch(List.of(a,QueryPlan.batch(List.of(a,a)))),
            QueryPlan.batch(List.of(a,draft())),new QueryPlan("CLARIFY","ACTIVITIES",null,null,"DATE",null,"DELETE",null,"MISSING_INFO"),
            new QueryPlan("CLARIFY","EQUIPMENT",null,"ART",null,null,null,null,"MISSING_INFO")))assertThat(bad.valid()).as(bad.toString()).isFalse();
        noFacts();
    }
}
