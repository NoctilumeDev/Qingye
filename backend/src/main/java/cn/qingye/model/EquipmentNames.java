package cn.qingye.model;

import java.util.List;
import java.util.Map;

/** Finite, visible business name groups. No substring or fuzzy matching (especially accessories). */
public final class EquipmentNames {
    private static final Map<String,List<String>> GROUPS=AssistantContract.equipmentNameGroups();
    private EquipmentNames() {}
    public static List<String> resolve(String name) { return GROUPS.getOrDefault(name,List.of(name)); }
}
