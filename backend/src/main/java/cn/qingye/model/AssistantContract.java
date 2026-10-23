package cn.qingye.model;

import java.util.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** One capability source for validation, prompt metadata and generated mini-program selectors. */
public final class AssistantContract {
    private static final Map<String,Object> SPEC=load();
    public static final String VERSION=(String)SPEC.get("version");
    public static final int MAX_QUERIES=(Integer)SPEC.get("maxQueries"), MAX_CLARIFICATIONS=(Integer)SPEC.get("maxClarifications");
    public static final List<String> INTENTS=list("intents"),TIMES=list("times"),CATEGORIES=list("categories");
    public static final List<String> REGISTRATION_STATES=states("MY_REGISTRATIONS"),LOAN_STATES=states("MY_LOANS");
    public static final Set<String> CLARIFICATIONS=Set.copyOf(list("clarifications"));
    private AssistantContract() {}
    @SuppressWarnings("unchecked") private static Map<String,Object> load() {
        try(var input=AssistantContract.class.getResourceAsStream("/assistant-contract.json")) {
            var spec=new ObjectMapper().readValue(input,new TypeReference<Map<String,Object>>(){});
            if(!Objects.equals(spec.get("maxQueries"),4)||!Objects.equals(spec.get("maxClarifications"),5))throw new IllegalStateException("Unsupported protocol limits");
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
    @SuppressWarnings("unchecked") public static List<String> timeLabels(String intent){
        var overrides=((Map<String,Map<String,String>>)SPEC.get("timeLabelsByIntent")).getOrDefault(intent,Map.of());var labels=list("timeLabels");
        return times(intent).stream().map(key->overrides.getOrDefault(key,labels.get(TIMES.indexOf(key)))).toList();
    }
    public static List<String> intentLabels(){return list("intentLabels");}
    @SuppressWarnings("unchecked") public static List<String> requiredFields(String intent){return ((Map<String,List<String>>)SPEC.get("requiredFields")).getOrDefault(intent,List.of());}
    @SuppressWarnings("unchecked") public static List<String> stateLabels(String intent){var labels=(Map<String,String>)SPEC.get("stateLabels");return states(intent).stream().map(labels::get).toList();}
    @SuppressWarnings("unchecked") public static Optional<QueryPlan> entryCommand(String text){
        for(var entry:(List<Map<String,String>>)SPEC.get("entryCommands"))if(entry.get("label").equals(text))
            return Optional.of(QueryPlan.query(entry.get("intent"),entry.get("intent").equals("EQUIPMENT")?"ALL":null,null,entry.get("timeOption")));
        return Optional.empty();
    }
    public enum DirectoryFailure { INVALID_HINT, SEARCH_SOURCE_MISMATCH, SELECTION_SOURCE_MISMATCH, UNSUPPORTED_SELECTION, CONFLICTING_TIME, INVALID_QUERY }
    public record DirectoryCompilation(QueryPlan query,DirectoryFailure failure) {
        public DirectoryCompilation {
            if((query==null)==(failure==null))throw new IllegalArgumentException("exactly one directory result is required");
        }
        public static DirectoryCompilation accepted(QueryPlan query){return new DirectoryCompilation(query,null);}
        public static DirectoryCompilation failed(DirectoryFailure failure){return new DirectoryCompilation(null,failure);}
        public boolean accepted(){return query!=null;}
    }
    /** Compile only existing directory operations; absent filters keep business defaults. */
    @SuppressWarnings("unchecked") public static DirectoryCompilation directoryQuery(AssistantHint hint,String originalAndReplies){
        if(!hint.valid()||!"LOCATE".equals(hint.action()))return DirectoryCompilation.failed(DirectoryFailure.INVALID_HINT);
        // A locator may retain a user-provided name fragment, but it cannot invent a new
        // narrowing term. Unknown names are ordinary business data and still pass here.
        if(hint.search()!=null&&!originalAndReplies.contains(hint.search()))return DirectoryCompilation.failed(DirectoryFailure.SEARCH_SOURCE_MISMATCH);
        String time=hint.intent().equals("EQUIPMENT")?"CURRENT":"ANY",date=null,state="ANY",category=null,location=null;
        var choices=(Map<String,Map<String,List<String>>>)SPEC.get("explicitSelections");
        for(var selection:hint.selections()){
            String field=selection.field(),value=selection.value(),source=selection.source();
            if(!originalAndReplies.contains(source))return DirectoryCompilation.failed(DirectoryFailure.SELECTION_SOURCE_MISMATCH);
            if(field.equals("DATE")){
                if(!sameDate(value,source))return DirectoryCompilation.failed(DirectoryFailure.UNSUPPORTED_SELECTION);time="DATE";date=value;
            }else if(field.equals("LOCATION")){
                if(!value.equals(source)||!Set.of("ACTIVITIES","MY_REGISTRATIONS").contains(hint.intent()))return DirectoryCompilation.failed(DirectoryFailure.UNSUPPORTED_SELECTION);location=value;
            }else{
                var options=choices.getOrDefault(field,Map.of());
                if(!options.containsKey(value)){
                    // Normalize an exact, unique contract label, not a phrase from the raw request.
                    String label=value;var matches=options.entrySet().stream().filter(e->e.getValue().contains(label)).map(Map.Entry::getKey).toList();
                    if(matches.size()!=1)return DirectoryCompilation.failed(DirectoryFailure.UNSUPPORTED_SELECTION);value=matches.get(0);
                }
                var allowed=options.getOrDefault(value,List.of());
                // A model quotes the user's phrase, not a server-owned canonical label.
                // Accept a phrase only when it contains a cue for this finite value and
                // no cue for a competing value. Default-valued selections are inert and
                // may be omitted by the compiler after the literal-source check above.
                boolean inertDefault=field.equals("STATE")&&value.equals("ANY")
                    ||field.equals("TIME")&&value.equals(hint.intent().equals("EQUIPMENT")?"CURRENT":"ANY");
                if(!inertDefault&&!matchesUniqueCue(options,value,source))return DirectoryCompilation.failed(DirectoryFailure.UNSUPPORTED_SELECTION);
                switch(field){case "TIME"->time=value;case "STATE"->state=value;case "CATEGORY"->category=value;default->{return DirectoryCompilation.failed(DirectoryFailure.UNSUPPORTED_SELECTION);}}
            }
        }
        if(date!=null&&hint.selections().stream().anyMatch(s->s.field().equals("TIME")))return DirectoryCompilation.failed(DirectoryFailure.CONFLICTING_TIME);
        var query=new QueryPlan("QUERY",hint.intent(),hint.search()==null&&hint.intent().equals("EQUIPMENT")?"ALL":hint.search(),category,time,date,state,location,null);
        return query.valid()?DirectoryCompilation.accepted(query):DirectoryCompilation.failed(DirectoryFailure.INVALID_QUERY);
    }
    private static boolean matchesUniqueCue(Map<String,List<String>> options,String value,String source){
        record Match(String value,String cue){}
        var matches=options.entrySet().stream()
            .flatMap(e->e.getValue().stream().filter(source::contains).map(cue->new Match(e.getKey(),cue)))
            .toList();
        int longest=matches.stream().mapToInt(m->m.cue().length()).max().orElse(0);
        return longest>0&&matches.stream().filter(m->m.cue().length()==longest).allMatch(m->m.value().equals(value));
    }
    private static boolean sameDate(String value,String source){
        try{
            var expected=LocalDate.parse(value);
            if(source.equals(value))return true;
            return expected.equals(LocalDate.parse(source,DateTimeFormatter.ofPattern("yyyy年M月d日")));
        }catch(DateTimeParseException ignored){return false;}
    }
    public static Object locatorSelections(){return SPEC.get("explicitSelections");}
    @SuppressWarnings("unchecked") public static Map<String,String> clarificationQuestions(){return (Map<String,String>)SPEC.get("clarificationQuestions");}
    public static Map<String,Object> describe(){return SPEC;}
}
