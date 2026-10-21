package cn.qingye.business;

import cn.qingye.model.QueryPlan;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/** Short-lived, session-bound consent to exactly one immutable plan. No database or model access. */
final class AssistantConfirmations {
    record Offer(long user,String sessionHash,String requestId,QueryPlan plan,AssistantTime period,String mode,int offset,Instant expiresAt) {}
    record Issued(String token,Instant expiresAt) {}
    record Dialogue(long user,String sessionHash,String requestId,List<String> turns,int replies,boolean retry,LocalDate day,Instant expiresAt) {}
    private record Owner(long user,String sessionHash) {}
    private record Request(String id,Instant expiresAt) {}
    private final Clock clock;
    private final Map<String,Offer> offers=new HashMap<>();
    private final Map<Owner,Request> requests=new HashMap<>();
    private final Map<String,Dialogue> dialogues=new HashMap<>();
    AssistantConfirmations(Clock clock) { this.clock=clock; }

    private void purgeExpired() {
        offers.values().removeIf(offer->!clock.instant().isBefore(offer.expiresAt()));
        requests.values().removeIf(request->!clock.instant().isBefore(request.expiresAt()));
        dialogues.values().removeIf(d->!clock.instant().isBefore(d.expiresAt()));
    }
    synchronized String begin(long user,String session) {
        purgeExpired();
        if (session==null || session.isBlank()) return null;
        var owner=new Owner(user,hash(session));
        offers.values().removeIf(offer->offer.user()==user && offer.sessionHash().equals(owner.sessionHash()));
        dialogues.values().removeIf(d->d.user()==user&&d.sessionHash().equals(owner.sessionHash()));
        if (requests.size()>=512 && !requests.containsKey(owner)) return null;
        var request=new Request(UUID.randomUUID().toString(),clock.instant().plusSeconds(300));
        requests.put(owner,request);
        return request.id();
    }
    synchronized Optional<Issued> clarify(long user,String session,String requestId,List<String> turns,int replies,boolean retry) {
        purgeExpired();
        if(session==null||requestId==null||dialogues.size()>=512)return Optional.empty();
        var owner=new Owner(user,hash(session));var current=requests.get(owner);
        if(current==null||!current.id().equals(requestId))return Optional.empty();
        String token=UUID.randomUUID().toString();var expires=current.expiresAt();
        dialogues.put(token,new Dialogue(user,owner.sessionHash(),requestId,List.copyOf(turns),replies,retry,LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))),expires));
        return Optional.of(new Issued(token,expires));
    }
    synchronized Optional<Dialogue> reply(long user,String session,String token,boolean retry) {
        var d=dialogues.get(token);
        if(d==null||session==null||d.user()!=user||!d.sessionHash().equals(hash(session))||d.retry()!=retry)return Optional.empty();
        dialogues.remove(token);
        var current=requests.get(new Owner(user,d.sessionHash()));
        if(!clock.instant().isBefore(d.expiresAt())||!d.day().equals(LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))))
            ||current==null||!current.id().equals(d.requestId()))return Optional.empty();
        // A new interpretation must not leave consent to the previous interpretation alive.
        offers.values().removeIf(o->o.user()==user&&o.sessionHash().equals(d.sessionHash())&&o.requestId().equals(d.requestId()));
        dialogues.values().removeIf(o->o.user()==user&&o.sessionHash().equals(d.sessionHash())&&o.requestId().equals(d.requestId()));
        return Optional.of(d);
    }
    synchronized Optional<Issued> issue(long user,String session,String requestId,QueryPlan plan,AssistantTime period,String mode,int offset) {
        purgeExpired();
        if (session==null || requestId==null) return Optional.empty();
        var owner=new Owner(user,hash(session));
        var current=requests.get(owner);
        if (current==null || !current.id().equals(requestId)) return Optional.empty();
        if (offers.size()>=512) return Optional.empty();
        String token=UUID.randomUUID().toString();
        Instant expires=current.expiresAt();
        offers.put(token,new Offer(user,owner.sessionHash(),requestId,plan,period,mode,offset,expires));
        return Optional.of(new Issued(token,expires));
    }
    synchronized Optional<Offer> consume(long user,String session,String token,boolean more) {
        var offer=offers.get(token);
        if (offer==null || session==null || offer.user()!=user || !offer.sessionHash().equals(hash(session)) || (offer.offset()>0)!=more) return Optional.empty();
        offers.remove(token);
        return clock.instant().isBefore(offer.expiresAt())?Optional.of(offer):Optional.empty();
    }
    private static String hash(String session) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(session.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
