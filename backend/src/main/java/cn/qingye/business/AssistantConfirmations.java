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
    private final Clock clock;
    private final Map<String,Offer> offers=new HashMap<>();
    AssistantConfirmations(Clock clock) { this.clock=clock; }

    synchronized void invalidate(long user,String session) {
        offers.values().removeIf(offer->!clock.instant().isBefore(offer.expiresAt()));
        if (session!=null) offers.values().removeIf(offer->offer.user()==user && offer.sessionHash().equals(hash(session)));
    }
    synchronized Optional<Issued> issue(long user,String session,QueryPlan plan,String mode) {
        if (session==null || session.isBlank()) return Optional.empty();
        invalidate(user,session);
        if (offers.size()>=512) return Optional.empty();
        String token=UUID.randomUUID().toString();
        Instant expires=clock.instant().plusSeconds(300);
        offers.put(token,new Offer(user,hash(session),plan,mode,expires));
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
