package cn.qingye.db;

import java.time.LocalDateTime;
import java.util.List;

/** Only server-owned SQL fragments; user strings are always bound values. */
final class AssistantSql {
    private AssistantSql() {}
    static String time(String option,String startColumn,String endColumn,LocalDateTime start,LocalDateTime end,LocalDateTime anchor,List<Object> args) {
        return switch(option) {
            case "PAST" -> { args.add(anchor); yield " AND "+endColumn+"<=?"; }
            case "CURRENT" -> { args.add(anchor);args.add(anchor);yield " AND "+startColumn+"<=? AND "+endColumn+">?"; }
            case "FUTURE" -> { args.add(anchor);yield " AND "+startColumn+">?"; }
            default -> {
                String filter="";
                if(start!=null) { filter+=" AND "+endColumn+">?";args.add(start); }
                if(end!=null) { filter+=" AND "+startColumn+"<?";args.add(end); }
                yield filter;
            }
        };
    }
    static String literalContains(String value) { return "%"+value.replace("!","!!").replace("%","!%").replace("_","!_")+"%"; }
    static String equipmentName(String name,List<Object> args) {
        args.add(name);args.add(name);
        return " AND (e.name=? OR EXISTS (SELECT 1 FROM equipment_alias ea WHERE ea.equipment_id=e.id AND ea.alias=?))";
    }
}
