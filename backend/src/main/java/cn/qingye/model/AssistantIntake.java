package cn.qingye.model;

import java.util.List;

/** Gate 1. Unknown language remains eligible for interpretation; data is never an instruction. */
public final class AssistantIntake {
    private AssistantIntake() {}

    public static String reject(Actor actor, String session, List<String> turns, QueryPlan explicit) {
        if (actor == null || actor.id() <= 0 || session == null || session.isBlank()) return "SESSION_REQUIRED";
        if (turns == null || turns.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 300)
            || turns.stream().mapToInt(String::length).sum() > 8000) return "INVALID_REQUEST";
        if (explicit != null) {
            if (!"QUERY".equals(explicit.action()) && !"CLARIFY".equals(explicit.action())) return "WRITE_OPERATION";
            if (explicit.intent() != null && !"BATCH".equals(explicit.intent())
                && !AssistantContract.INTENTS.contains(explicit.intent())) return "UNSUPPORTED_CAPABILITY";
        } else if (turns.isEmpty()) return "INVALID_REQUEST";
        // Free text has no SQL, identity or operation authority. Do not classify an
        // unfamiliar business name as outside the domain using a keyword blacklist.
        return null;
    }
}
