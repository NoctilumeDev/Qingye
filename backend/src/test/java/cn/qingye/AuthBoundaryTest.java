package cn.qingye;

import cn.qingye.business.AuthService;
import cn.qingye.business.Problem;
import cn.qingye.db.Sql;
import cn.qingye.db.UserStore;
import cn.qingye.integration.ExternalHttp;
import cn.qingye.model.Actor;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthBoundaryTest {
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC);
    // Public test-only material, not a deployed credential.
    private static final String KEY = "fixture-only-signing-material-32-characters";
    private final UserStore users = mock(UserStore.class);
    private final Sql sql = mock(Sql.class);
    private final ExternalHttp http = mock(ExternalHttp.class);

    private AuthService auth(boolean demo, String key, Clock clock) {
        return new AuthService(users, sql, http, clock, demo, key, "", "");
    }

    @Test void missingOrShortSigningKeyCannotStartNonDemoAuthentication() {
        for (String key : new String[]{"", " ", "x".repeat(31)}) {
            assertThatThrownBy(() -> auth(false, key, NOW)).isInstanceOf(IllegalStateException.class);
        }
        assertThatCode(() -> auth(false, KEY, NOW)).doesNotThrowAnyException();
        assertThatCode(() -> auth(true, "", NOW)).doesNotThrowAnyException();
        verifyNoInteractions(users, sql, http);
    }

    @Test void demoEntryCannotBeUsedWhenDisabled() {
        var service = auth(false, KEY, NOW);
        assertThat(service.demoUsers()).isEmpty();
        assertThatThrownBy(() -> service.demo(7)).isInstanceOf(Problem.class);
        verifyNoInteractions(users, sql, http);
    }

    @Test void expirationAndDifferentSigningKeyRejectBeforeUserLookup() {
        var actor = new Actor(7, "fixture", false);
        when(users.actor(7)).thenReturn(actor);
        when(users.profile(7)).thenReturn(Map.of("id", 7));
        when(sql.count(anyString(), eq(7L))).thenReturn(1L);
        String token = auth(true, KEY, NOW).demo(7).get("token").toString();
        assertThat(auth(false, KEY, Clock.offset(NOW, Duration.ofHours(8).minusSeconds(1))).authenticate(token)).isEqualTo(actor);
        clearInvocations(users);
        assertThatThrownBy(() -> auth(false, KEY, Clock.offset(NOW, Duration.ofHours(8))).authenticate(token))
                .isInstanceOf(Problem.class).hasMessageContaining("失效");
        assertThatThrownBy(() -> auth(false, KEY + "different", NOW).authenticate(token))
                .isInstanceOf(Problem.class).hasMessageContaining("失效");
        verifyNoInteractions(users, http);
    }

    @Test void malformedOrOversizedTokensNeverLookUpAnActor() {
        var service = auth(false, KEY, NOW);
        for (String token : new String[]{null, "", "bad", "bad.signature", "x".repeat(301)}) {
            assertThatThrownBy(() -> service.authenticate(token)).isInstanceOf(Problem.class);
        }
        verifyNoInteractions(users, sql, http);
    }
}
