package cn.qingye.model;

import java.util.*;

/** Directory location with optional explicit user selections, never an execution grant. */
public record AssistantHint(String action,String intent,String search,String reason,List<Selection> selections,String question) {
    public record Selection(String field,String value,String source) {}
    public AssistantHint {selections=selections==null?List.of():List.copyOf(selections);}
    public AssistantHint(String action,String intent,String search,String reason){this(action,intent,search,reason,List.of(),null);}
    public AssistantHint(String action,String intent,String search,String reason,List<Selection> selections){this(action,intent,search,reason,selections,null);}
    public static final Set<String> FIELDS=Set.copyOf(AssistantLocatorProtocol.FIELDS);
    public static AssistantHint locate(String intent,String search){return new AssistantHint("LOCATE",intent,search,null);}
    public static AssistantHint clarify(String intent,String search){return new AssistantHint("CLARIFY",intent,search,"MISSING_INFO");}
    public static AssistantHint clarify(String intent,String search,String question){return new AssistantHint("CLARIFY",intent,search,"MISSING_INFO",List.of(),question);}
    public static AssistantHint reject(String reason){return new AssistantHint("REJECT",null,null,reason);}
    public boolean valid(){
        if(selections.size()>AssistantLocatorProtocol.MAX_SELECTIONS||selections.stream().anyMatch(s->s==null||s.field()==null||s.value()==null||s.source()==null||s.source().isBlank()||s.source().length()>AssistantLocatorProtocol.MAX_SOURCE)||selections.stream().map(Selection::field).distinct().count()!=selections.size())return false;
        if(intent!=null&&!AssistantContract.INTENTS.contains(intent))return false;
        if(search!=null&&(search.isBlank()||search.length()>AssistantLocatorProtocol.MAX_SEARCH||!search.equals(search.strip())||search.codePoints().anyMatch(Character::isISOControl)))return false;
        if(question!=null&&!AssistantContract.clarificationQuestions().containsKey(question))return false;
        return switch(Objects.toString(action,"")){
            case "LOCATE" -> intent!=null&&reason==null&&question==null;
            case "CLARIFY" -> AssistantLocatorProtocol.CLARIFICATIONS.contains(Objects.toString(reason,""));
            case "REJECT" -> intent==null&&search==null&&question==null&&selections.isEmpty()&&AssistantLocatorProtocol.REJECTIONS.contains(Objects.toString(reason,""));
            default -> false;
        };
    }
}
