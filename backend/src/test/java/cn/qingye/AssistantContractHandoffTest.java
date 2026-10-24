package cn.qingye;

import cn.qingye.business.*;
import cn.qingye.db.*;
import cn.qingye.integration.LlmPlanner;
import cn.qingye.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantContractHandoffTest {
    @Test void resolvedPromptOptionsAndDefaultsAgreeWithActualQualification() throws Exception {
        var json=new ObjectMapper();var view=json.valueToTree(AssistantContract.locatorView());
        for(String intent:AssistantContract.INTENTS){
            var caps=view.path("capabilities").path(intent);
            assertThat(caps.path("timeOptions")).isEqualTo(json.valueToTree(AssistantContract.times(intent)));
            assertThat(caps.path("states")).isEqualTo(json.valueToTree(AssistantContract.states(intent)));
            var compiled=AssistantContract.directoryQuery(AssistantHint.locate(intent,null),"");
            assertThat(compiled.accepted()).isTrue();
            assertThat(compiled.query().timeOption()).isEqualTo(caps.path("defaultTime").asText());
            assertThat(compiled.query().status()).isEqualTo(caps.path("defaultState").asText());
            for(String time:AssistantContract.TIMES){
                var p=new QueryPlan("QUERY",intent,intent.equals("EQUIPMENT")?"ALL":null,null,time,
                    time.equals("DATE")?"2026-10-22":null,"ANY",null,null);
                assertThat(p.valid()).as(intent+"/"+time).isEqualTo(AssistantContract.times(intent).contains(time));
            }
        }
        assertThat(AssistantContract.times("EQUIPMENT")).doesNotContain("FUTURE","ANY","PAST");
        assertThat(LlmPlanner.instruction(Clock.systemUTC())).contains(json.writeValueAsString(AssistantContract.locatorView()));
    }

    @Test void runtimeStopPrecedesQuestionButMissingTimeRoleStillAsksWithoutReading() {
        var activities=mock(ActivityStore.class);var loans=mock(LoanStore.class);var capacity=mock(LoanService.class);var planner=mock(LlmPlanner.class);
        var service=new AssistantService(activities,loans,capacity,planner,Clock.fixed(Instant.parse("2026-10-21T02:00:00Z"),ZoneId.of("Asia/Shanghai")));
        var actor=new Actor(3,"测试用户",false);
        for(String reason:AssistantLocatorProtocol.CLARIFICATIONS){
            when(planner.locate(anyString())).thenReturn(Optional.of(new AssistantHint("CLARIFY","MY_LOANS",null,reason,List.of(),"DATE_MEANING")));
            var response=service.ask(actor,"我今天借的相机有哪些","s");
            assertThat(response.get("status")).isEqualTo(reason.equals("UNSUPPORTED_FILTER")?"UNSUPPORTED":"CLARIFY");
            assertThat(response).doesNotContainKey("confirmationToken");
            if(reason.equals("UNSUPPORTED_FILTER"))assertThat(response).doesNotContainKeys("clarificationToken","question");
            else assertThat(response).containsEntry("question",AssistantContract.clarificationQuestions().get("DATE_MEANING"));
        }
        for(String reason:AssistantLocatorProtocol.REJECTIONS){
            when(planner.locate(anyString())).thenReturn(Optional.of(AssistantHint.reject(reason)));
            assertThat(service.ask(actor,"测试请求","s").get("status")).isEqualTo(reason.equals("UNSUPPORTED_CAPABILITY")?"UNSUPPORTED":"REJECT");
        }
        verifyNoInteractions(activities,loans,capacity);
    }
}
