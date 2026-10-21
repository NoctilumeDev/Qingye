package cn.qingye.model;

import java.util.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** One capability source for validation, prompt metadata and generated mini-program selectors. */
public final class AssistantContract {
    private static final Map<String,Object> SPEC=load();
    public static final String VERSION=(String)SPEC.get("version");
    public static final int MAX_QUERIES=4, MAX_CLARIFICATIONS=2;
    public static final List<String> INTENTS=list("intents"),TIMES=list("times"),CATEGORIES=list("categories");
    public static final List<String> REGISTRATION_STATES=states("MY_REGISTRATIONS"),LOAN_STATES=states("MY_LOANS");
    public static final Set<String> CLARIFICATIONS=Set.copyOf(list("clarifications")),REJECTIONS=Set.copyOf(list("rejections"));
    private AssistantContract() {}
    @SuppressWarnings("unchecked") private static Map<String,Object> load() {
        try(var input=AssistantContract.class.getResourceAsStream("/assistant-contract.json")) {
            var spec=new ObjectMapper().readValue(input,new TypeReference<Map<String,Object>>(){});
            if(!Objects.equals(spec.get("maxQueries"),4)||!Objects.equals(spec.get("maxClarifications"),2))throw new IllegalStateException("Unsupported protocol limits");
            return (Map<String,Object>)freeze(spec);
        }catch(java.io.IOException e){throw new ExceptionInInitializerError(e);}
    }
    private static Object freeze(Object value) {
        if(value instanceof Map<?,?> m){var copy=new LinkedHashMap<String,Object>();m.forEach((k,v)->copy.put(k.toString(),freeze(v)));return Collections.unmodifiableMap(copy);}
        if(value instanceof List<?> l)return l.stream().map(AssistantContract::freeze).toList();return value;
    }
    @SuppressWarnings("unchecked") private static List<String> list(String key){return (List<String>)SPEC.get(key);}
    @SuppressWarnings("unchecked") public static List<String> states(String intent){return ((Map<String,List<String>>)SPEC.get("states")).getOrDefault(intent,List.of("ANY"));}
    @SuppressWarnings("unchecked") public static List<String> times(String intent){return ((Map<String,List<String>>)SPEC.get("timeOptionsByIntent")).getOrDefault(intent,TIMES);}
    @SuppressWarnings("unchecked") public static Map<String,List<String>> equipmentNameGroups(){return (Map<String,List<String>>)SPEC.get("equipmentNameGroups");}
    public static Map<String,Object> describe(){return SPEC;}
}
