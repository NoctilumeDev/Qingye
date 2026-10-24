package cn.qingye.integration;

import cn.qingye.model.AssistantHint;
import cn.qingye.model.AssistantLocatorProtocol;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Strict, side-effect-free model-wire decoding. No natural-language correction or execution authority. */
public final class AssistantHintDecoder {
    private AssistantHintDecoder() {}
    public enum Failure {
        INVALID_RESPONSE_ENVELOPE, TRUNCATED_OUTPUT, DUPLICATE_JSON_MEMBER, INVALID_JSON,
        INVALID_FIELD_SET, INVALID_FIELD_TYPE, INVALID_SELECTION, DUPLICATE_SELECTION_FIELD, INVALID_HINT_VALUE
    }
    public static final class ProtocolViolation extends RuntimeException {
        private static final long serialVersionUID=1L;
        private final Failure category;
        public ProtocolViolation(Failure category){super(category.name());this.category=category;}
        public Failure category(){return category;}
    }
    private static ProtocolViolation invalid(Failure failure){return new ProtocolViolation(failure);}

    public static AssistantHint decode(ObjectMapper json,Map<?,?> response){
        if(response==null||!(response.get("choices") instanceof List<?> choices)||choices.isEmpty()
            ||!(choices.get(0) instanceof Map<?,?> choice))throw invalid(Failure.INVALID_RESPONSE_ENVELOPE);
        if("length".equals(choice.get("finish_reason")))throw invalid(Failure.TRUNCATED_OUTPUT);
        if(!(choice.get("message") instanceof Map<?,?> message)||!(message.get("content") instanceof String content))throw invalid(Failure.INVALID_RESPONSE_ENVELOPE);
        return decodeContent(json,content);
    }

    public static AssistantHint decodeContent(ObjectMapper json,String content){
        final JsonNode tree;
        try{
            tree=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(content);
        }catch(JsonParseException failure){
            // Jackson's duplicate detector supplies a fixed diagnostic, never a business-language rule.
            throw invalid(failure.getOriginalMessage().startsWith("Duplicate field ")?Failure.DUPLICATE_JSON_MEMBER:Failure.INVALID_JSON);
        }catch(JsonProcessingException failure){throw invalid(Failure.INVALID_JSON);}
        if(tree==null||!tree.isObject()||tree.size()!=AssistantHint.FIELDS.size())throw invalid(Failure.INVALID_FIELD_SET);
        for(String field:AssistantHint.FIELDS)if(!tree.has(field))throw invalid(Failure.INVALID_FIELD_SET);
        for(String field:AssistantLocatorProtocol.FIELDS.stream().filter(f->!f.equals("selections")).toList())
            if(!tree.get(field).isNull()&&!tree.get(field).isTextual())throw invalid(Failure.INVALID_FIELD_TYPE);
        if(!tree.get("selections").isArray()||tree.get("selections").size()>AssistantLocatorProtocol.MAX_SELECTIONS)throw invalid(Failure.INVALID_SELECTION);
        var fields=new HashSet<String>();
        for(var selection:tree.get("selections")){
            if(!selection.isObject()||selection.size()!=AssistantLocatorProtocol.SELECTION_FIELDS.size())throw invalid(Failure.INVALID_SELECTION);
            for(String key:AssistantLocatorProtocol.SELECTION_FIELDS)
                if(!selection.has(key)||!selection.get(key).isTextual())throw invalid(Failure.INVALID_SELECTION);
            if(!fields.add(selection.get("field").asText()))throw invalid(Failure.DUPLICATE_SELECTION_FIELD);
        }
        try{
            var hint=json.treeToValue(tree,AssistantHint.class);
            if(!hint.valid())throw invalid(Failure.INVALID_HINT_VALUE);
            return hint;
        }catch(JsonProcessingException failure){throw invalid(Failure.INVALID_HINT_VALUE);}
    }
}
