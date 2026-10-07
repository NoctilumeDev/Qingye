package cn.qingye.business;

import cn.qingye.model.QueryPlan;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/** Short-lived, session-bound consent to exactly one immutable plan. No database or model access. */
final class AssistantConfirmations {
    record Offer(long user,String sessionHash,QueryPlan plan,String mode,Instant expiresAt) {}
    record Issued(String token,Instant expiresAt) {}
    private record Owner(long user,String sessionHash) {}
    private record Request(String id,Instant expiresAt) {}
    private final Clock clock;
    private final Map<String,Offer> offers=new HashMap<>();
    private final Map<Owner,Request> requests=new HashMap<>();
    AssistantConfirmations(Clock clock) { this.clock=clock; }

    private void purgeExpired() {
        offers.values().removeIf(offer->!clock.instant().isBefore(offer.expiresAt()));
        requests.values().removeIf(request->!clock.instant().isBefore(request.expiresAt()));
    }
    synchronized String begin(long user,String session) {
        purgeExpired();
        if (session==null || session.isBlank()) return null;
        var owner=new Owner(user,hash(session));
        offers.values().removeIf(offer->offer.user()==user && offer.sessionHash().equals(owner.sessionHash()));
        if (requests.size()>=512 && !requests.containsKey(owner)) return null;
        var request=new Request(UUID.randomUUID().toString(),clock.instant().plusSeconds(300));
        requests.put(owner,request);
        return request.id();
    }
    synchronized Optional<Issued> issue(long user,String session,String requestId,QueryPlan plan,String mode) {
        purgeExpired();
        if (session==null || requestId==null) return Optional.empty();
        var owner=new Owner(user,hash(session));
        var current=requests.get(owner);
        if (current==null || !current.id().equals(requestId)) return Optional.empty();
        if (offers.size()>=512) return Optional.empty();
        String token=UUID.randomUUID().toString();
        Instant expires=clock.instant().plusSeconds(300);
        offers.put(token,new Offer(user,owner.sessionHash(),plan,mode,expires));
        return Optional.of(new Issued(token,expires));
    }
    synchronized Optional<Offer> consume(long user,String session,String token) {
        var offer=offers.get(token);
        if (offer==null || session==null || offer.user()!=user || !offer.sessionHash().equals(hash(session))) return Optional.empty();
        offers.remove(token);
        return clock.instant().isBefore(offer.expiresAt())?Optional.of(offer):Optional.empty();
    }
    private static String hash(String session) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(session.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
