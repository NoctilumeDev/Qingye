package cn.qingye.api;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
public final class Forms {
    private Forms() {
    }
    public record Club(@NotBlank @Size(max=60) String name,@NotNull @Size(max=1000) String description,@NotNull @Pattern(regexp="green|blue|orange|red") String color,@Positive long managerId) {
    }
    public record Member(boolean approve,@Pattern(regexp="MEMBER|MANAGER") String role) {
    }
    public record Activity(@Positive long clubId,@NotBlank @Size(max=80) String title,@NotNull @Size(max=2000) String description,@NotNull @Pattern(regexp="SPORT|ART|TECH|VOLUNTEER|OTHER") String category,@NotBlank @Size(max=100) String location,@NotNull @Size(max=512) String poster,@NotNull LocalDateTime startTime,@NotNull LocalDateTime endTime,@NotNull LocalDateTime signupDeadline,@Min(1) @Max(500) int capacity) {
    }
    public record Decision(boolean approve,@NotNull @Size(max=200) String note) {
    }
    public record Equipment(@NotBlank @Size(max=60) String name,@NotBlank @Size(max=20) String category,@NotNull @Size(max=1000) String description,@NotNull @Size(max=512) String image,@Min(0) @Max(500) int totalQuantity,boolean enabled) {
    }
    public record Loan(@Positive long activityId,@Positive long equipmentId,@Min(1) @Max(500) int quantity,@NotNull LocalDateTime plannedStart,@NotNull LocalDateTime plannedEnd,@NotNull @Size(max=300) String reason,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,64}") String requestKey) {
    }
    public record Profile(@NotBlank @Size(max=40) String name,@NotNull @Size(max=512) String avatar) {
    }
    public record WxLogin(@NotBlank @Size(max=200) String code) {
    }
    public record DemoLogin(@Positive long userId) {
    }
    public record Question(@NotBlank @Size(max=300) String question) {
    }
}
