package cn.qingye.api;
import cn.qingye.business.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Map;

@Component
public class AuthFilter extends OncePerRequestFilter {
    private final AuthService auth;
    private final ObjectMapper json;
    public AuthFilter(AuthService auth,ObjectMapper json) {
        this.auth=auth;
        this.json=json;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String path=request.getRequestURI();
        if (!path.startsWith("/api/") || path.startsWith("/api/auth/") || path.equals("/api/health")) {
            chain.doFilter(request,response);
            return;
        }
        try {
            String header=request.getHeader("Authorization");
            request.setAttribute("actor",auth.authenticate(header!=null && header.startsWith("Bearer ")?header.substring(7):null));
        }
        catch(Problem e) {
            response.setStatus(e.status());
            response.setContentType("application/json;charset=UTF-8");
            json.writeValue(response.getWriter(),Map.of("code",e.status(),"message",e.getMessage()));
            return;
        }
        chain.doFilter(request,response);
    }
}
