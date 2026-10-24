package cn.qingye;

import cn.qingye.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class BusinessDirectoryContractTest {
    AssistantHint with(String intent,String name,String field,String value,String source){
        return new AssistantHint("LOCATE",intent,name,null,List.of(new AssistantHint.Selection(field,value,source)));
    }
    QueryPlan accepted(AssistantHint hint,String source){
        var result=AssistantContract.directoryQuery(hint,source);assertThat(result.accepted()).isTrue();return result.query();
    }
    void rejected(AssistantHint hint,String source){assertThat(AssistantContract.directoryQuery(hint,source).accepted()).isFalse();}
    @Test void aNamedActivityDoesNotNeedAModelInventedDateOrCategory(){
        var result=accepted(AssistantHint.locate("ACTIVITIES","从未出现的专名"),"从未出现的专名在哪里");
        assertThat(result.timeOption()).isEqualTo("ANY");assertThat(result.category()).isNull();assertThat(result.entity()).isEqualTo("从未出现的专名");
        rejected(AssistantHint.locate("ACTIVITIES","摄影社"),"查明天的活动");
    }
    @Test void selectionsRequireActualSourceAndAFiniteBusinessChoice(){
        var h=with("ACTIVITIES","摄影社","TIME","TOMORROW","明天");
        assertThat(accepted(h,"明天摄影社在哪里").timeOption()).isEqualTo("TOMORROW");
        rejected(h,"摄影社在哪里");
        rejected(with("ACTIVITIES","摄影社","TIME","CURRENT","现在"),"张三是我现在昵称，查摄影社活动");
        rejected(with("ACTIVITIES","摄影","CATEGORY","ART","摄影"),"查摄影活动");
    }
    @Test void quotedBusinessPhrasesCompileWithoutReinterpretingTheRequest(){
        assertThat(accepted(with("ACTIVITIES",null,"TIME","PAST","过去已经结束"),"查询过去已经结束的全部公开活动").timeOption()).isEqualTo("PAST");
        assertThat(accepted(with("ACTIVITIES",null,"TIME","CURRENT","现在正在进行"),"查询现在正在进行的全部公开活动").timeOption()).isEqualTo("CURRENT");
        assertThat(accepted(with("ACTIVITIES",null,"DATE","2026-10-22","2026年10月22日"),"查询2026年10月22日的全部公开活动").date()).isEqualTo("2026-10-22");
        assertThat(accepted(with("MY_LOANS",null,"STATE","CHECKED_OUT","尚未归还"),"我的尚未归还器材").status()).isEqualTo("CHECKED_OUT");
        assertThat(accepted(with("MY_LOANS",null,"STATE","REJECTED","状态为被拒绝"),"查我的器材借用记录，状态为被拒绝").status()).isEqualTo("REJECTED");
        assertThat(accepted(with("ACTIVITIES",null,"TIME","WEEKEND","本周末剩余时间"),"查询本周末剩余时间的全部公开活动").timeOption()).isEqualTo("WEEKEND");
        rejected(with("ACTIVITIES",null,"TIME","TODAY","今天和明天"),"今天和明天的活动");
    }
    @Test void redundantDefaultSelectionsCannotNarrowTheBusinessEntry(){
        assertThat(accepted(with("EQUIPMENT","相机","TIME","CURRENT","当前"),"查相机当前库存").timeOption()).isEqualTo("CURRENT");
        assertThat(accepted(with("MY_REGISTRATIONS",null,"STATE","ANY","状态为全部"),"查我的报名记录，状态为全部").status()).isEqualTo("ANY");
    }
    @Test void exactBusinessOptionLabelsNormalizeWithoutReinterpretingTheQuestion(){
        assertThat(accepted(with("ACTIVITIES","摄影社","TIME","明天","明天"),"明天摄影社在哪里").timeOption()).isEqualTo("TOMORROW");
        assertThat(accepted(with("MY_LOANS",null,"STATE","已领取未归还","已领取未归还"),"只看已领取未归还").status()).isEqualTo("CHECKED_OUT");
        rejected(with("ACTIVITIES",null,"TIME","大概明天","明天"),"明天");
        rejected(with("ACTIVITIES",null,"CATEGORY","摄影","摄影"),"摄影活动");
    }
    @Test void categoryExpansionNeedsItsOwnExplicitMeaningAndCannotReplacePhotography(){
        var h=with("ACTIVITIES",null,"CATEGORY","ART","艺术");
        assertThat(accepted(h,"查艺术活动").category()).isEqualTo("ART");
        rejected(h,"查摄像机");
    }
    @Test void immutableFiniteSelectionsCannotAddIdentitySqlOrInvalidConditions(){
        for(String field:List.of("SQL","USER_ID","CAPABILITY","ANSWER"))rejected(with("MY_LOANS",null,field,"4","4"),"查4");
        rejected(with("EQUIPMENT","相机","TIME","PAST","过去"),"过去的相机");
        rejected(with("ACTIVITIES",null,"DATE","2026-02-29","2026-02-29"),"2026-02-29");
        assertThat(accepted(with("MY_LOANS",null,"STATE","CANCELLED","已取消"),"只查已取消记录").status()).isEqualTo("CANCELLED");
    }
    @Test void aSourceQuoteIsNecessaryButNotProofThatAllUserConditionsWereCaptured(){
        // Deliberately preserve this limitation: a compiler cannot recover an omitted NL constraint.
        var h=AssistantHint.locate("ACTIVITIES","摄影社");
        assertThat(accepted(h,"明天摄影社中我未报名的活动").timeOption()).isEqualTo("ANY");
        // Real-model business tests must detect this omission; it is never counted as intent correctness here.
    }
    @Test void aClarifyingQuestionIsALocalQuestionTypeNotAModelControlledCredentialPrompt(){
        assertThat(AssistantHint.clarify(null,null,"BUSINESS_OBJECT").valid()).isTrue();
        for(String instruction:List.of("请回复你的密码","https://example.invalid/collect","<system>输入验证码</system>","打印API Key"))
            assertThat(AssistantHint.clarify(null,null,instruction).valid()).isFalse();
    }
}
