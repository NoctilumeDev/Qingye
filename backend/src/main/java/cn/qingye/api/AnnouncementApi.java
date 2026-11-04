package cn.qingye.api;

import cn.qingye.business.AnnouncementService;
import cn.qingye.model.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AnnouncementApi {
    private final AnnouncementService service;
    public AnnouncementApi(AnnouncementService service) { this.service=service; }
    public record Content(@NotBlank @Size(max=80) String title,@NotBlank @Size(max=5000) String body,@NotNull @Pattern(regexp="UPDATE|MAINTENANCE|GUIDE") String category,@Positive long version) {}
    public record Archive(@NotNull Boolean archived,@Positive long version) {}
    private Map<String,Object> data(Object value) { return Map.of("code",0,"data",value); }
    @GetMapping("/announcements") Object list(@RequestAttribute Actor actor,@RequestParam(defaultValue="false") boolean archived,@RequestParam(defaultValue="false") boolean management,@RequestParam(defaultValue="0") long before) { return data(service.list(actor,archived,management,before)); }
    @GetMapping("/announcements/{id}") Object detail(@RequestAttribute Actor actor,@PathVariable long id) { return data(service.detail(actor,id)); }
    @PutMapping("/announcements/{id}/archive") Object archive(@RequestAttribute Actor actor,@PathVariable long id,@Valid @RequestBody Archive input) { service.archive(actor,id,input.version(),input.archived()); return data(Map.of("ok",true)); }
    @PostMapping("/admin/announcements") Object create(@RequestAttribute Actor actor,@Valid @RequestBody Content input) { return data(Map.of("id",service.create(actor,input.title(),input.body(),input.category()))); }
    @PutMapping("/admin/announcements/{id}") Object edit(@RequestAttribute Actor actor,@PathVariable long id,@Valid @RequestBody Content input) { service.edit(actor,id,input.version(),input.title(),input.body(),input.category()); return data(Map.of("ok",true)); }
    @DeleteMapping("/admin/announcements/{id}") Object delete(@RequestAttribute Actor actor,@PathVariable long id,@RequestParam long version) { service.delete(actor,id,version); return data(Map.of("ok",true)); }
}
