package it.tesi.cultural.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final JdbcTemplate jdbcTemplate;

    public JwtAuthenticationFilter(JwtService jwtService, JdbcTemplate jdbcTemplate) {
        this.jwtService = jwtService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                if (!authorization.startsWith("Bearer ")) {
                    throw new IllegalArgumentException("Unsupported authorization scheme");
                }
                Claims claims = jwtService.parseToken(authorization.substring(7));
                String username = claims.getSubject();
                // Il ruolo viene letto dal DB: un token gia emesso non conserva permessi revocati.
                List<String> roles = jdbcTemplate.query(
                        "SELECT role::text FROM users WHERE username = ?",
                        (row, index) -> row.getString(1), username);
                if (username == null || roles.size() != 1) {
                    throw new IllegalArgumentException("User no longer exists");
                }
                var authorities = roles.stream().map(SimpleGrantedAuthority::new).toList();
                var authentication = new UsernamePasswordAuthenticationToken(username, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException exception) {
                SecurityContextHolder.clearContext();
                response.setStatus(401);
                response.setContentType("application/json");
                response.getWriter().write("{\"message\":\"Invalid or expired token\"}");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
