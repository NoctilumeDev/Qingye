package cn.qingye;

import cn.qingye.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AssistantQualificationTest {
    @Test void absentConditionsRemainAbsentInsteadOfBecomingAnExecutableDefault() {
        var raw=new QueryPlan("QUERY","ACTIVITIES","摄影社","ART",null,null,"ANY","东操场",null);
        var draft=AssistantQualification.incompleteDraft(raw).orElseThrow();
        assertThat(draft.action()).isEqualTo("CLARIFY");assertThat(draft.reason()).isEqualTo("MISSING_TIME");
        assertThat(draft.entity()).isEqualTo(raw.entity());assertThat(draft.category()).isEqualTo(raw.category());assertThat(draft.location()).isEqualTo(raw.location());
        assertThat(draft.timeOption()).isNull();assertThat(draft.valid()).isTrue();
        assertThat(AssistantQualification.missing(draft)).extracting(AssistantQualification.MissingCondition::field).containsExactly("timeOption");
    }
    @Test void objectDateEntityAndStateHaveExplicitGapsWithObjectSpecificChoices() {
        var noObject=new QueryPlan("QUERY",null,null,null,null,null,null,null,null);
        var objectDraft=AssistantQualification.incompleteDraft(noObject).orElseThrow();
        assertThat(AssistantQualification.missing(objectDraft)).extracting(AssistantQualification.MissingCondition::field).containsExactly("intent");
        var noDate=new QueryPlan("QUERY","MY_LOANS","相机",null,"DATE",null,null,null,null);
        var dateDraft=AssistantQualification.incompleteDraft(noDate).orElseThrow();
        assertThat(dateDraft.timeOption()).isEqualTo("DATE");assertThat(dateDraft.date()).isNull();assertThat(dateDraft.status()).isNull();
        assertThat(AssistantQualification.missing(dateDraft)).extracting(AssistantQualification.MissingCondition::field).containsExactly("date","status");
        var equipment=new QueryPlan("QUERY","EQUIPMENT",null,null,null,null,"ANY",null,null);
        var equipmentDraft=AssistantQualification.incompleteDraft(equipment).orElseThrow();
        var gaps=AssistantQualification.missing(equipmentDraft);
        assertThat(gaps).extracting(AssistantQualification.MissingCondition::field).containsExactly("entity","timeOption");
        assertThat(gaps.get(1).choices()).contains("当前库存","明天").doesNotContain("全部时间","过去（已结束）");
    }
    @Test void aCompletePlanIsUntouchedAndUnsupportedValuesAreNotRepairedIntoMissingValues() {
        var complete=QueryPlan.query("ACTIVITIES","摄影社",null,"ANY");
        assertThat(AssistantQualification.incompleteDraft(complete)).isEmpty();
        for(var bad:List.of(new QueryPlan("QUERY","MY_LOANS","相机",null,"NEXT_YEAR",null,"ANY",null,null),
            new QueryPlan("QUERY","EQUIPMENT",null,"ART",null,null,"ANY",null,null),
            new QueryPlan("QUERY","MY_LOANS",null,null,null,null,"DELETE",null,null),
            new QueryPlan("QUERY","MY_LOANS",null,null,"DATE","2026-02-30","ANY",null,null),
            new QueryPlan("QUERY","BATCH","偷偷放条件",null,null,null,null,null,null,List.of(complete,complete))))
            assertThat(AssistantQualification.incompleteDraft(bad)).as(bad.toString()).isEmpty();
    }
    @Test void aPartialBatchKeepsEveryBranchWithoutAuthorizingItsCompleteBranch() {
        var ready=QueryPlan.query("EQUIPMENT","相机",null,"CURRENT");
        var missing=new QueryPlan("QUERY","ACTIVITIES","摄影社",null,null,null,"ANY",null,null);
        var draft=AssistantQualification.incompleteDraft(QueryPlan.batch(List.of(ready,missing))).orElseThrow();
        assertThat(draft.action()).isEqualTo("CLARIFY");assertThat(draft.intent()).isEqualTo("BATCH");assertThat(draft.valid()).isTrue();
        assertThat(draft.queries().get(0)).isEqualTo(ready);assertThat(draft.queries().get(1).entity()).isEqualTo("摄影社");
        assertThat(AssistantQualification.missing(draft)).extracting(AssistantQualification.MissingCondition::queryIndex).containsExactly(2);
        assertThat(AssistantQualification.incompleteDraft(QueryPlan.batch(List.of(ready,QueryPlan.batch(List.of(ready,missing)))))).isEmpty();
    }
    @Test void aQueryContainerWithAValidClarificationChildCanOnlyBecomeADraft() {
        var ready=QueryPlan.query("EQUIPMENT","相机",null,"CURRENT");
        var partial=new QueryPlan("CLARIFY","ACTIVITIES","摄影社",null,null,null,"ANY",null,"MISSING_TIME");
        var mixed=QueryPlan.batch(List.of(ready,partial));assertThat(mixed.valid()).isFalse();
        var draft=AssistantQualification.incompleteDraft(mixed).orElseThrow();
        assertThat(draft.action()).isEqualTo("CLARIFY");assertThat(draft.queries()).containsExactly(ready,partial);assertThat(draft.valid()).isTrue();
        var unsupported=new QueryPlan("CLARIFY","ACTIVITIES",null,null,null,null,null,null,"UNSUPPORTED_FILTER");
        assertThat(AssistantQualification.incompleteDraft(QueryPlan.batch(List.of(ready,unsupported))).orElseThrow().reason()).isEqualTo("UNSUPPORTED_FILTER");
        assertThat(AssistantQualification.incompleteDraft(QueryPlan.batch(List.of(ready,QueryPlan.reject("PRIVATE_DATA"))))).isEmpty();
    }
}
