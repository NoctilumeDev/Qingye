package cn.qingye.business;
public class Problem extends RuntimeException {
    private final int status;
    public Problem(int status, String message) {
        super(message);
        this.status = status;
    }
    public int status() {
        return status;
    }
    public static Problem bad(String message) {
        return new Problem(400, message);
    }
    public static Problem forbidden() {
        return new Problem(403, "没有这项操作权限");
    }
    public static Problem conflict(String message) {
        return new Problem(409, message);
    }
    public static Problem missing() {
        return new Problem(404, "记录不存在");
    }
}
