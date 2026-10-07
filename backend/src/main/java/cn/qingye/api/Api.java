package cn.qingye.api;
import cn.qingye.business.*;
import cn.qingye.db.*;
import cn.qingye.integration.*;
import cn.qingye.model.Actor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class Api {
    private final AuthService auth;
    private final ClubService clubs;
    private final ActivityService activities;
    private final LoanService loans;
    private final UserStore users;
    private final MessageStore messages;
    private final Recommendation recommendation;
    private final AssistantService assistant;
    private final QueryCache cache;
    private final RabbitBridge rabbit;
    private final LlmPlanner planner;
    private final Clock clock;
    private final Access access;
    public Api(AuthService auth,ClubService clubs,ActivityService activities,LoanService loans,UserStore users,MessageStore messages,Recommendation recommendation,AssistantService assistant,QueryCache cache,RabbitBridge rabbit,LlmPlanner planner,Clock clock,Access access) {
        this.auth=auth;
        this.clubs=clubs;
        this.activities=activities;
        this.loans=loans;
        this.users=users;
        this.messages=messages;
        this.recommendation=recommendation;
        this.assistant=assistant;
        this.cache=cache;
        this.rabbit=rabbit;
        this.planner=planner;
        this.clock=clock;
        this.access=access;
    }
    private Map<String,Object> data(Object value) {
        return Map.of("code",0,"data",value);
    }
    private Map<String,Object> changed(Object value) {
        cache.invalidate();
        return data(value);
    }
    private Map<String,Object> ok() {
        return changed(Map.of("ok",true));
    }
    @GetMapping("/health") Object health() {
        return data(Map.of("name","青野","time",LocalDateTime.now(clock)));
    }
    @GetMapping("/auth/options") Object options() {
        return data(Map.of("demoUsers",auth.demoUsers()));
    }
    @PostMapping("/auth/demo") Object demo(@Valid @RequestBody Forms.DemoLogin input) {
        return data(auth.demo(input.userId()));
    }
    @PostMapping("/auth/wechat") Object wx(@Valid @RequestBody Forms.WxLogin input) {
        return data(auth.wechat(input.code()));
    }
    @GetMapping("/me") Object me(@RequestAttribute Actor actor) {
        return data(users.profile(actor.id()));
    }
    @PatchMapping("/me") Object profile(@RequestAttribute Actor actor,@Valid @RequestBody Forms.Profile input) {
        users.profile(actor.id(),input.name(),input.avatar());
        return ok();
    }
    @GetMapping("/users") Object users(@RequestAttribute Actor actor) {
        access.admin(actor);
        return data(users.users());
    }
    @GetMapping("/clubs") Object clubs(@RequestAttribute Actor actor) {
        return data(clubs.list(actor));
    }
    @PostMapping("/clubs") Object club(@RequestAttribute Actor actor,@Valid @RequestBody Forms.Club input) {
        return changed(Map.of("id",clubs.create(actor,input)));
    }
    @PutMapping("/clubs/{id}") Object club(@RequestAttribute Actor actor,@PathVariable long id,@Valid @RequestBody Forms.Club input) {
        clubs.edit(actor,id,input);
        return ok();
    }
    @PostMapping("/clubs/{id}/join") Object join(@RequestAttribute Actor actor,@PathVariable long id) {
        clubs.join(actor,id);
        return ok();
    }
    @PostMapping("/clubs/{id}/leave") Object leave(@RequestAttribute Actor actor,@PathVariable long id) {
        clubs.leave(actor,id);
        return ok();
    }
    @GetMapping("/clubs/{id}/members") Object members(@RequestAttribute Actor actor,@PathVariable long id) {
        return data(clubs.members(actor,id));
    }
    @PostMapping("/clubs/{id}/members/{user}/decision") Object member(@RequestAttribute Actor actor,@PathVariable long id,@PathVariable long user,@Valid @RequestBody Forms.Member input) {
        clubs.decide(actor,id,user,input);
        return ok();
    }
    @GetMapping("/activities") Object activities(@RequestAttribute Actor actor,@RequestParam(defaultValue="public") String scope,@RequestParam(required=false) String category,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="false") boolean upcoming) {
        if ((keyword!=null && keyword.length()>60) || (category!=null && category.length()>20)) throw Problem.bad("查询条件过长");
        if (!Set.of("public","mine","work").contains(scope) || page<0 || page>500 || (category!=null && !category.isBlank() && !Set.of("SPORT","ART","TECH","VOLUNTEER","OTHER").contains(category))) throw Problem.bad("查询范围、分类或页码无效");
        return data("public".equals(scope)?cache.publicList("activities:"+Objects.toString(category,"")+":"+Objects.toString(keyword,"")+":"+page+":"+upcoming,()->activities.list(actor,"public",category,keyword,page,upcoming)):activities.list(actor,scope,category,keyword,page,upcoming));
    }
    @GetMapping("/activities/{id}") Object activity(@RequestAttribute Actor actor,@PathVariable long id) {
        return data(activities.detail(actor,id));
    }
    @PostMapping("/activities") Object activity(@RequestAttribute Actor actor,@Valid @RequestBody Forms.Activity input) {
        return changed(Map.of("id",activities.create(actor,input)));
    }
    @PutMapping("/activities/{id}") Object activity(@RequestAttribute Actor actor,@PathVariable long id,@Valid @RequestBody Forms.Activity input) {
        activities.edit(actor,id,input);
        return ok();
    }
    @PostMapping("/activities/{id}/decision") Object activityDecision(@RequestAttribute Actor actor,@PathVariable long id,@Valid @RequestBody Forms.Decision input) {
        activities.decide(actor,id,input);
        return ok();
    }
    @PostMapping("/activities/{id}/cancel") Object cancelActivity(@RequestAttribute Actor actor,@PathVariable long id) {
        activities.cancel(actor,id);
        return ok();
    }
    @PostMapping("/activities/{id}/registration") Object registration(@RequestAttribute Actor actor,@PathVariable long id) {
        return changed(activities.register(actor,id));
    }
    @DeleteMapping("/activities/{id}/registration") Object cancelRegistration(@RequestAttribute Actor actor,@PathVariable long id) {
        activities.cancelRegistration(actor,id);
        return ok();
    }
    @GetMapping("/activities/{id}/participants") Object participants(@RequestAttribute Actor actor,@PathVariable long id) {
        return data(activities.participants(actor,id));
    }
    @GetMapping("/equipment") Object equipment() {
        return data(cache.publicList("equipment",loans::equipment));
    }
    @GetMapping("/equipment/{id}/availability") Object available(@PathVariable long id,@RequestParam LocalDateTime start,@RequestParam LocalDateTime end) {
        return data(loans.availability(id,start,end));
    }
    @PostMapping("/equipment") Object equipment(@RequestAttribute Actor actor,@Valid @RequestBody Forms.Equipment input) {
        return changed(Map.of("id",loans.createEquipment(actor,input)));
    }
    @PutMapping("/equipment/{id}") Object equipment(@RequestAttribute Actor actor,@PathVariable long id,@Valid @RequestBody Forms.Equipment input) {
        loans.editEquipment(actor,id,input);
        return ok();
    }
    @GetMapping("/loans") Object loans(@RequestAttribute Actor actor) {
        return data(loans.list(actor));
    }
    @PostMapping("/loans") Object loan(@RequestAttribute Actor actor,@Valid @RequestBody Forms.Loan input) {
        return changed(Map.of("id",loans.apply(actor,input)));
    }
    @PostMapping("/loans/{id}/decision") Object decideLoan(@RequestAttribute Actor actor,@PathVariable long id,@Valid @RequestBody Forms.Decision input) {
        loans.decide(actor,id,input);
        return ok();
    }
    @PostMapping("/loans/{id}/checkout") Object checkout(@RequestAttribute Actor actor,@PathVariable long id) {
        loans.checkout(actor,id);
        return ok();
    }
    @PostMapping("/loans/{id}/return") Object returned(@RequestAttribute Actor actor,@PathVariable long id) {
        loans.returned(actor,id);
        return ok();
    }
    @PostMapping("/loans/{id}/cancel") Object cancelLoan(@RequestAttribute Actor actor,@PathVariable long id) {
        loans.cancel(actor,id);
        return ok();
    }
    @GetMapping("/notifications") Object notifications(@RequestAttribute Actor actor) {
        return data(messages.list(actor.id()));
    }
    @PostMapping("/notifications/{id}/read") Object read(@RequestAttribute Actor actor,@PathVariable long id) {
        messages.read(id,actor.id(),LocalDateTime.now(clock));
        return ok();
    }
    @PostMapping("/notifications/read-all") Object readAll(@RequestAttribute Actor actor) {
        messages.readAll(actor.id(),LocalDateTime.now(clock));
        return data(Map.of("ok",true));
    }
    @PostMapping("/notifications/clear") Object clearNotifications(@RequestAttribute Actor actor,@Valid @RequestBody Forms.Messages input) {
        messages.clear(actor.id(),input.ids(),LocalDateTime.now(clock));
        return data(Map.of("ok",true));
    }
    @GetMapping("/notifications/trash") Object notificationTrash(@RequestAttribute Actor actor) {
        return data(Map.of("retentionDays",MessageStore.TRASH_DAYS,"items",messages.trash(actor.id(),LocalDateTime.now(clock))));
    }
    @PostMapping("/notifications/restore") Object restoreNotifications(@RequestAttribute Actor actor,@Valid @RequestBody Forms.Messages input) {
        return data(Map.of("restored",messages.restore(actor.id(),input.ids(),LocalDateTime.now(clock))));
    }
    @GetMapping("/recommendations") Object recommended(@RequestAttribute Actor actor) {
        return data(recommendation.forUser(actor));
    }
    @PostMapping("/assistant") Object assistant(@RequestAttribute Actor actor,@RequestHeader("Authorization") String session,@Valid @RequestBody Forms.Question input) {
        return data(assistant.ask(actor,input.question(),session));
    }
    @PostMapping("/assistant/confirm") Object confirmAssistant(@RequestAttribute Actor actor,@RequestHeader("Authorization") String session,@Valid @RequestBody Forms.AssistantConfirmation input) {
        return data(assistant.confirm(actor,input.token(),session));
    }
    @GetMapping("/workbench/status") Object status(@RequestAttribute Actor actor) {
        access.admin(actor);
        return data(Map.of("cache",cache.mode(),"mq",rabbit.mode(),"llmConfigured",planner.configured(),"tasks",messages.stats()));
    }
}
