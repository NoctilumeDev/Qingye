package cn.qingye.db;
import java.time.LocalDateTime;
import java.util.Map;
public final class Rows {
    private Rows() {
    }
    public static long id(Map<String,Object> row, String key) {
        return ((Number)row.get(key)).longValue();
    }
    public static int integer(Map<String,Object> row, String key) {
        return ((Number)row.get(key)).intValue();
    }
    public static String text(Map<String,Object> row, String key) {
        return String.valueOf(row.get(key));
    }
    public static boolean flag(Map<String,Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Boolean b ? b : ((Number)value).intValue() != 0;
    }
    public static LocalDateTime time(Map<String,Object> row, String key) {
        return LocalDateTime.parse(text(row,key));
    }
}
