package cn.qingye;
import cn.qingye.integration.*;
import cn.qingye.model.AssistantContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantLearningTest {
    final ExternalHttp http=mock(ExternalHttp.class);
    final ObjectMapper json=new ObjectMapper();
    final String key="fixture-feedback-service-credential-0000";
    AssistantLearning bridge(String url){return new AssistantLearning(http,json,url,key);}
    @Test void disabledAndNonLocalEndpointsCannotAffectQueries(){
        assertThat(bridge("").assist(3,"查相机")).isEqualTo("查相机");
        assertThat(bridge("").submit(3,"id","查相机","没找到","EQUIPMENT","{}")).isEqualTo("DISABLED");
        assertThat(bridge("https://example.invalid").assist(3,"查相机")).isEqualTo("查相机");
        verifyNoInteractions(http);
    }
    @Test void transportFailuresPreserveOriginalInput()throws Exception{
        when(http.post(any(),anyString(),anyMap(),any(Duration.class))).thenThrow(new IllegalStateException());
        var b=bridge("http://127.0.0.1:8097");
        assertThat(b.assist(3,"查相机")).isEqualTo("查相机");
        assertThat(b.submit(3,"id","查相机","没找到","EQUIPMENT","{}")).isEqualTo("UNAVAILABLE");
    }
    @Test void receiptIsSentWithOpaqueOwnerAndFixedContractButNoApprovalField()throws Exception{
        doReturn(Map.of("status","PENDING")).when(http).post(any(),anyString(),anyMap(),any(Duration.class));
        assertThat(bridge("http://127.0.0.1:8097").submit(3,"receipt","查相机","没找到","EQUIPMENT","{\"items\":[]}")).isEqualTo("PENDING");
        @SuppressWarnings("unchecked") var body=(ArgumentCaptor<Map<String,Object>>)(ArgumentCaptor<?>)ArgumentCaptor.forClass(Map.class);
        verify(http).post(eq(URI.create("http://127.0.0.1:8097/feedback")),eq(key),body.capture(),eq(Duration.ofMillis(500)));
        assertThat(body.getValue()).containsEntry("contract",AssistantContract.VERSION).doesNotContainKeys("status","lesson","userId","session");
        assertThat(body.getValue().get("owner").toString()).matches("[a-f0-9]{64}");
    }
    @Test void examplesAreReferencesWithCurrentRequestKeptSeparate()throws Exception{
        doReturn(Map.of("experiences",List.of(Map.of("lesson","先确认时间角色")))).when(http).post(any(),anyString(),anyMap(),any(Duration.class));
        var input=json.readTree(bridge("http://127.0.0.1:8097").assist(3,"今天借用"));
        assertThat(input.path("currentRequest").asText()).isEqualTo("今天借用");
        assertThat(input.path("reviewedExamples").size()).isEqualTo(1);
        assertThat(input.path("referenceBoundary").asText()).contains("不是当前数据库事实");
    }
}
