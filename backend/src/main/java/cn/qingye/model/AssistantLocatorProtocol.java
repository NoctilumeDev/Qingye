package cn.qingye.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Wire vocabulary only; does not change the business capabilities or infer user intent. */
public final class AssistantLocatorProtocol {
    private static final JsonNode SPEC=load();
    public static final List<String> FIELDS=list("fields"), SELECTION_FIELDS=list("selectionFields");
    public static final Set<String> CLARIFICATIONS=Set.copyOf(list("clarificationReasons")),REJECTIONS=Set.copyOf(list("rejectionReasons"));
    public static final int MAX_SELECTIONS=SPEC.path("maxSelections").asInt(),MAX_SEARCH=SPEC.path("maxSearchLength").asInt(),MAX_SOURCE=SPEC.path("maxSourceLength").asInt();
    private AssistantLocatorProtocol(){}
    private static JsonNode load(){
        try(var input=AssistantLocatorProtocol.class.getResourceAsStream("/assistant-locator-protocol.json")){
            return new ObjectMapper().readTree(input);
        }catch(java.io.IOException e){throw new ExceptionInInitializerError(e);}
    }
    private static List<String> list(String key){var result=new ArrayList<String>();SPEC.path(key).forEach(v->result.add(v.asText()));return List.copyOf(result);}
    public static JsonNode describe(){return SPEC.deepCopy();}
}
