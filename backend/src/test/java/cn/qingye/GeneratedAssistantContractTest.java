package cn.qingye;

import cn.qingye.model.AssistantContract;
import cn.qingye.model.AssistantLocatorProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class GeneratedAssistantContractTest {
    @Test void generatedValuesExactlyMatchBothCanonicalResources() throws Exception {
        var json=new ObjectMapper();
        try(var business=getClass().getResourceAsStream("/assistant-contract.json");
            var locator=getClass().getResourceAsStream("/assistant-locator-protocol.json")) {
            assertThat(json.<com.fasterxml.jackson.databind.JsonNode>valueToTree(AssistantContract.describe())).isEqualTo(json.readTree(business));
            assertThat(json.<com.fasterxml.jackson.databind.JsonNode>valueToTree(AssistantLocatorProtocol.describe())).isEqualTo(json.readTree(locator));
        }
        assertThat(AssistantContract.MAX_QUERIES).isEqualTo(4);
        assertThat(AssistantContract.MAX_CLARIFICATIONS).isEqualTo(5);
    }

    @Test @SuppressWarnings("unchecked") void callersCannotChangeNestedCapabilitiesOrProtocol() {
        var spec=AssistantContract.describe();
        assertThatThrownBy(()->spec.put("maxQueries",100)).isInstanceOf(UnsupportedOperationException.class);
        var states=(Map<String,List<String>>)spec.get("states");
        assertThatThrownBy(()->states.get("MY_LOANS").add("ADMIN")).isInstanceOf(UnsupportedOperationException.class);
        var protocol=AssistantLocatorProtocol.describe();
        var examples=(List<Map<String,Object>>)protocol.get("examples");
        assertThatThrownBy(()->examples.get(0).put("action","WRITE")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void requestAndClarificationCollectionsAreSnapshotsWithoutHidingInvalidNulls() {
        var mutable=new java.util.ArrayList<cn.qingye.api.Forms.AssistantQuery>();
        mutable.add(null);
        var request=new cn.qingye.api.Forms.AssistantQueries(mutable);
        mutable.clear();
        assertThat(request.queries()).containsExactly((cn.qingye.api.Forms.AssistantQuery)null);
        assertThatThrownBy(()->request.queries().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new cn.qingye.api.Forms.AssistantQueries(null).queries()).isNull();
        var choices=new java.util.ArrayList<>(List.of("ACTIVITIES"));
        var missing=new cn.qingye.model.AssistantQualification.MissingCondition(1,"intent","查询对象",choices);
        choices.add("WRITE");
        assertThat(missing.choices()).containsExactly("ACTIVITIES");
        assertThatThrownBy(()->missing.choices().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
