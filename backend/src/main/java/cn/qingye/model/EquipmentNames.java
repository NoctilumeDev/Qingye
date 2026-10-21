package cn.qingye.model;

import java.util.List;
import java.util.Map;

/** Finite, visible business name groups. No substring or fuzzy matching (especially accessories). */
public final class EquipmentNames {
    private static final Map<String,List<String>> GROUPS=Map.of(
        "相机",List.of("相机","单反相机"),
        "三脚架",List.of("三脚架","轻便三脚架"),
        "投影仪",List.of("投影仪","便携投影仪"));
    private EquipmentNames() {}
    public static List<String> resolve(String name) { return GROUPS.getOrDefault(name,List.of(name)); }
}
