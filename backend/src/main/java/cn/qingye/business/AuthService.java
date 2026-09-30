package cn.qingye.business;
import cn.qingye.db.*;
import cn.qingye.integration.ExternalHttp;
import cn.qingye.model.Actor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.net.*;
import java.time.Clock;
import java.util.*;
import static cn.qingye.db.Rows.*;

@Service
public class AuthService {
    private final UserStore users;
    private final Sql sql;
    private final ExternalHttp http;
    private final Clock clock;
    private final boolean demo;
    private final byte[] key;
    private final String appid,secret;
    public AuthService(UserStore users,Sql sql,ExternalHttp http,Clock clock,@Value("${qingye.demo-enabled}") boolean demo,@Value("${qingye.token-secret}") String tokenSecret,@Value("${qingye.wx-appid}") String appid,@Value("${qingye.wx-secret}") String secret) {
        this.users=users;
        this.sql=sql;
        this.http=http;
        this.clock=clock;
        this.demo=demo;
        this.appid=appid;
        this.secret=secret;
        if (tokenSecret.isBlank()) {
            if (!demo) throw new IllegalStateException("请设置至少32字符的 QINGYE_TOKEN_SECRET，或仅在本地启用 QINGYE_DEMO");
            key=new byte[32];
            new SecureRandom().nextBytes(key);
        }
        else {
            if (tokenSecret.length()<32) throw new IllegalStateException("QINGYE_TOKEN_SECRET 至少32字符");
            key=tokenSecret.getBytes(StandardCharsets.UTF_8);
        }
    }
    public List<Map<String,Object>> demoUsers() {
        return demo?sql.list("SELECT id,name,admin FROM app_user WHERE openid LIKE 'demo:%' AND enabled=TRUE ORDER BY id"):List.of();
    }
    public Map<String,Object> demo(long id) {
        if (!demo) throw Problem.forbidden();
        if (sql.count("SELECT COUNT(*) FROM app_user WHERE id=? AND openid LIKE 'demo:%' AND enabled=TRUE",id)==0) throw Problem.forbidden();
        return session(id);
    }
    public Map<String,Object> wechat(String code) {
        if (appid.isBlank() || secret.isBlank()) throw new Problem(503,"尚未配置微信 AppID 和服务端密钥，请使用本地演示入口");
        try {
            var result=http.get(URI.create("https://api.weixin.qq.com/sns/jscode2session?appid="+URLEncoder.encode(appid,StandardCharsets.UTF_8)+"&secret="+URLEncoder.encode(secret,StandardCharsets.UTF_8)+"&js_code="+URLEncoder.encode(code,StandardCharsets.UTF_8)+"&grant_type=authorization_code"));
            Object openid=result.get("openid");
            if (!(openid instanceof String identity) || identity.isBlank()) throw new Problem(401,"微信登录凭证无效，请重试");
            var user=users.byOpenid(identity);
            if (user==null) {
                try {
                    users.create(identity,"青野同学");
                }
                catch(org.springframework.dao.DuplicateKeyException ignored) {
                }
                user=users.byOpenid(identity);
            }
            if (!flag(user,"enabled")) throw Problem.forbidden();
            return session(id(user,"id"));
        }
        catch(Problem e) {
            throw e;
        }
        catch(Exception e) {
            throw new Problem(503,"微信登录暂不可用，请稍后重试");
        }
    }
    private Map<String,Object> session(long id) {
        users.actor(id);
        long expires=clock.instant().getEpochSecond()+8*3600;
        String payload=id+":"+expires+":"+UUID.randomUUID();
        String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return Map.of("token",encoded+"."+sign(encoded),"user",users.profile(id));
    }
    private String sign(String value) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key,"HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch(GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
    public Actor authenticate(String token) {
        try {
            if (token==null || token.length()>300) throw new IllegalArgumentException();
            String[] parts=token.split("\\.");
            if (parts.length!=2 || !MessageDigest.isEqual(sign(parts[0]).getBytes(StandardCharsets.UTF_8),parts[1].getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException();
            String[] data=new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8).split(":");
            if (data.length!=3 || Long.parseLong(data[1])<=clock.instant().getEpochSecond()) throw new IllegalArgumentException();
            return users.actor(Long.parseLong(data[0]));
        }
        catch(Exception e) {
            throw new Problem(401,"登录已失效，请重新登录");
        }
    }
}
