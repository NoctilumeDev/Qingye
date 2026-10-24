package cn.qingye.model;

import java.util.*;

/** Wire vocabulary only; does not change business capabilities or infer user intent. */
public final class AssistantLocatorProtocol {
    private static final Map<String,Object> SPEC=AssistantContractData.LOCATOR;
    public static final List<String> FIELDS=list("fields"), SELECTION_FIELDS=list("selectionFields");
    public static final Set<String> CLARIFICATIONS=Set.copyOf(list("clarificationReasons")),REJECTIONS=Set.copyOf(list("rejectionReasons"));
    public static final int MAX_SELECTIONS=(Integer)SPEC.get("maxSelections"),MAX_SEARCH=(Integer)SPEC.get("maxSearchLength"),MAX_SOURCE=(Integer)SPEC.get("maxSourceLength");
    private AssistantLocatorProtocol(){}
    @SuppressWarnings("unchecked") private static List<String> list(String key){return (List<String>)SPEC.get(key);}
    public static Map<String,Object> describe(){return Collections.unmodifiableMap(SPEC);}
}
