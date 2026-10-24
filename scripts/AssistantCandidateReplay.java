import cn.qingye.business.*;
import cn.qingye.db.*;
import cn.qingye.integration.*;
import cn.qingye.model.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;

/** Offline replay through the real decoder/service; stores are mocks and must never be read. */
public class AssistantCandidateReplay {
    public static void main(String[] args) throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var clock=Clock.fixed(Instant.parse("2026-10-21T02:00:00Z"),ZoneId.of("Asia/Shanghai"));
        var root=Path.of(args[1]);Files.createDirectories(root);
        Files.writeString(root.resolve("current-planner.txt"),LlmPlanner.instruction(clock),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        String rules=new org.springframework.core.io.ClassPathResource("assistant-planner.txt").getContentAsString(StandardCharsets.UTF_8);
        Files.writeString(root.resolve("review-contract.json"),json.writeValueAsString(Map.of("business",AssistantContract.locatorView(),"wireProtocol",AssistantLocatorProtocol.describe(),"interpretationRules",rules)),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        try(var out=Files.newBufferedWriter(root.resolve("native-projections.jsonl"),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)){
            for(String line:Files.readAllLines(Path.of(args[0]),StandardCharsets.UTF_8)){
                var row=json.readTree(line);var activities=mock(ActivityStore.class);var loans=mock(LoanStore.class);var capacity=mock(LoanService.class);
                var transport=mock(ExternalHttp.class);
                when(transport.post(any(),anyString(),anyMap())).thenReturn(json.convertValue(row.path("response"),Map.class));
                var planner=new LlmPlanner(transport,json,clock,"https://offline.invalid","offline-placeholder","offline");
                var service=new AssistantService(activities,loans,capacity,planner,clock);
                String question=row.path("payload").path("messages").get(1).path("content").asText();
                var observed=service.ask(new Actor(3,"测试用户",false),question,"offline");
                verifyNoInteractions(activities,loans,capacity);
                var projection=new LinkedHashMap<String,Object>();
                for(String key:List.of("status","reason","protocolFailure","plan","scope","question","missingConditions"))
                    if(observed.containsKey(key))projection.put(key,observed.get(key));
                projection.put("businessReadPerformed",false);
                projection.put("executionAuthorized",false);
                out.write(json.writeValueAsString(Map.of("identity",row.path("identity").asText(),"projection",projection)));out.newLine();
            }
        }
    }
}
