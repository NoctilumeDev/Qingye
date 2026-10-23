package cn.qingye;

import cn.qingye.integration.AssistantHintDecoder;
import cn.qingye.integration.AssistantHintDecoder.Failure;
import cn.qingye.integration.AssistantHintDecoder.ProtocolViolation;
import cn.qingye.model.AssistantHint;
import cn.qingye.model.AssistantLocatorProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AssistantHintDecoderTest {
    final ObjectMapper json=new ObjectMapper();
    final String valid="{\"action\":\"LOCATE\",\"intent\":\"ACTIVITIES\",\"search\":\"摄影社\",\"reason\":null,\"selections\":[],\"question\":null}";
    void rejects(String content,Failure failure){
        assertThatThrownBy(()->AssistantHintDecoder.decodeContent(json,content))
            .isInstanceOfSatisfying(ProtocolViolation.class,e->assertThat(e.category()).isEqualTo(failure));
    }
    @Test void keyOrderAndWhitespaceDoNotChangeAValidCandidate(){
        String reordered="{ \"question\":null,\"selections\":[],\"reason\":null,\"search\":\"摄影社\",\"intent\":\"ACTIVITIES\",\"action\":\"LOCATE\" }";
        assertThat(AssistantHintDecoder.decodeContent(json,reordered)).isEqualTo(AssistantHint.locate("ACTIVITIES","摄影社"));
    }
    @Test void identicalAndConflictingRepeatedMembersBothStopInsteadOfChoosingAWinner(){
        rejects(valid.replace("\"selections\":[]","\"selections\":[],\"selections\":[]"),Failure.DUPLICATE_JSON_MEMBER);
        rejects(valid.replace("\"question\":null","\"question\":null,\"search\":null"),Failure.DUPLICATE_JSON_MEMBER);
    }
    @Test void twoTimeRolesCannotBeCollapsedIntoOneExecutableTimeField(){
        String times="[{\"field\":\"TIME\",\"value\":\"TODAY\",\"source\":\"今天报名\"},{\"field\":\"TIME\",\"value\":\"NEXT_WEEK\",\"source\":\"下周举行\"}]";
        rejects(valid.replace("\"selections\":[]","\"selections\":"+times),Failure.DUPLICATE_SELECTION_FIELD);
    }
    @Test void malformedJsonAndAuthorityFieldsRemainRejected(){
        for(String extra:List.of("userId","sql","timeOption"))rejects(valid.replace("\"question\":null","\"question\":null,\""+extra+"\":\"x\""),Failure.INVALID_FIELD_SET);
        rejects(valid+" {}",Failure.INVALID_JSON);
        rejects("not JSON",Failure.INVALID_JSON);
        rejects(valid.replace("\"search\":\"摄影社\"","\"search\":7"),Failure.INVALID_FIELD_TYPE);
        rejects(valid.replace("\"LOCATE\"","\"WRITE\""),Failure.INVALID_HINT_VALUE);
    }
    @Test void receivedEnvelopeAndTruncationHaveSeparateDiagnostics(){
        assertThatThrownBy(()->AssistantHintDecoder.decode(json,Map.of("choices",List.of())))
            .isInstanceOfSatisfying(ProtocolViolation.class,e->assertThat(e.category()).isEqualTo(Failure.INVALID_RESPONSE_ENVELOPE));
        assertThatThrownBy(()->AssistantHintDecoder.decode(json,Map.of("choices",List.of(Map.of("finish_reason","length","message",Map.of("content",valid))))))
            .isInstanceOfSatisfying(ProtocolViolation.class,e->assertThat(e.category()).isEqualTo(Failure.TRUNCATED_OUTPUT));
    }
    @Test void generatedPromptExamplesAreAcceptedByTheRealDecoderWithoutExtraFields()throws Exception{
        var spec=AssistantLocatorProtocol.describe();var instruction=cn.qingye.integration.LlmPlanner.instruction(java.time.Clock.systemUTC());
        assertThat(instruction).contains(json.writeValueAsString(spec));
        for(var example:spec.path("examples")){
            var candidate=AssistantHintDecoder.decodeContent(json,json.writeValueAsString(example));
            assertThat(candidate.valid()).isTrue();assertThat(example.size()).isEqualTo(AssistantHint.FIELDS.size());
        }
        // Exported metadata is a copy; a consumer cannot modify the running protocol.
        ((com.fasterxml.jackson.databind.node.ObjectNode)spec).put("maxSelections",500);
        assertThat(AssistantLocatorProtocol.MAX_SELECTIONS).isEqualTo(4);
        assertThat(AssistantLocatorProtocol.describe().path("maxSelections").asInt()).isEqualTo(4);
    }
}
