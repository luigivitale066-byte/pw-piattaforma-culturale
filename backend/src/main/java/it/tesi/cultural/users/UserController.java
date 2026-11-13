package it.tesi.cultural.users;

import it.tesi.cultural.auth.AuthController.UserResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class UserController {

    private final JdbcTemplate jdbc;

    public UserController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/users/me")
    UserResponse current(Authentication authentication) {
        return jdbc.queryForObject("""
                SELECT id, username, email, role::text AS role, created_at
                FROM users WHERE username = ?
                """, USER_MAPPER, authentication.getName());
    }

    @GetMapping("/users")
    List<UserResponse> list() {
        return jdbc.query("""
                SELECT id, username, email, role::text AS role, created_at
                FROM users ORDER BY created_at DESC, id
                """, USER_MAPPER);
    }

    @PutMapping("/admin/users/{id}/role")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Transactional
    UserResponse changeRole(@PathVariable Integer id, @Valid @RequestBody RoleRequest request,
            Authentication authentication) {

        // La serializzazione evita che due amministratori si rimuovano i permessi a vicenda.
        jdbc.execute("LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE");
        var caller = jdbc.query("""
                SELECT id, username, email, role::text AS role, created_at
                FROM users WHERE username = ?
                """, USER_MAPPER, authentication.getName());
        if (caller.isEmpty() || !"ROLE_ADMIN".equals(caller.get(0).role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        if (caller.get(0).id().equals(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You cannot change your own role");
        }
        var updated = jdbc.query("""
                UPDATE users SET role = ?::role_enum WHERE id = ?
                RETURNING id, username, email, role::text AS role, created_at
                """, USER_MAPPER, request.role().name(), id);
        if (updated.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        return updated.get(0);
    }

    public enum Role { ROLE_USER, ROLE_OPERATOR, ROLE_ADMIN }

    public record RoleRequest(@NotNull Role role) {}

    private static final RowMapper<UserResponse> USER_MAPPER = (row, index) -> new UserResponse(
            row.getObject("id", Integer.class), row.getString("username"), row.getString("email"),
            row.getString("role"), row.getObject("created_at", OffsetDateTime.class));
}
