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
import java.net.URI;

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

    private AuthService wechat() {
        return new AuthService(users, sql, http, NOW, false, KEY, "fixture-appid", "fixture-app-secret");
    }

    @Test void loginOptionsExposeAvailabilityButNoProviderSecrets() {
        assertThat(wechat().options()).containsEntry("wechatEnabled", true).containsEntry("demoEnabled", false)
                .containsOnlyKeys("wechatEnabled", "demoEnabled", "demoUsers");
        var unconfigured = auth(false, KEY, NOW);
        assertThat(unconfigured.options()).containsEntry("wechatEnabled", false);
        assertThatThrownBy(() -> unconfigured.wechat("fixture-code")).isInstanceOf(Problem.class);
        verifyNoInteractions(http, users, sql);
    }

    @Test void providerErrorsCannotCreateUsersEvenIfTheyContainAnOpenid() throws Exception {
        for (var entry : Map.of(40029,401,45011,429,40226,403,-1,503,40013,503).entrySet()) {
            doReturn(Map.of("errcode",entry.getKey(),"openid","ignored-user","errmsg","private-provider-detail"))
                    .when(http).get(any(URI.class));
            assertThatThrownBy(() -> wechat().wechat("fixture-code")).isInstanceOfSatisfying(Problem.class,
                    problem -> { assertThat(problem.status()).isEqualTo(entry.getValue()); assertThat(problem.getMessage()).doesNotContain("private-provider-detail"); });
        }
        verifyNoInteractions(users, sql);
    }

    @Test void missingIdentityAndNetworkFailuresDoNotEstablishSessions() throws Exception {
        doReturn(Map.of("session_key","fixture-provider-session")).when(http).get(any(URI.class));
        assertThatThrownBy(() -> wechat().wechat("fixture-code")).isInstanceOfSatisfying(Problem.class, p -> assertThat(p.status()).isEqualTo(503));
        doThrow(new java.io.IOException("fixture-app-secret")).when(http).get(any(URI.class));
        assertThatThrownBy(() -> wechat().wechat("fixture-code")).isInstanceOfSatisfying(Problem.class, p -> {
            assertThat(p.status()).isEqualTo(503); assertThat(p.getMessage()).doesNotContain("fixture-app-secret");
        });
        verifyNoInteractions(users, sql);
    }

    @Test void disabledWechatAccountCannotReceiveSessionOrBeRecreated() throws Exception {
        doReturn(Map.of("openid","fixture-disabled")).when(http).get(any(URI.class));
        when(users.byOpenid("fixture-disabled")).thenReturn(Map.of("id",7L,"enabled",false));
        assertThatThrownBy(() -> wechat().wechat("fixture-code")).isInstanceOfSatisfying(Problem.class, p -> assertThat(p.status()).isEqualTo(403));
        verify(users,never()).create(anyString(),anyString()); verify(users,never()).profile(anyLong());
    }
}
