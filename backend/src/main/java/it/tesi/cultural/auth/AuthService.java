package it.tesi.cultural.auth;

import it.tesi.cultural.auth.AuthController.AuthResponse;
import it.tesi.cultural.auth.AuthController.LoginRequest;
import it.tesi.cultural.auth.AuthController.RegisterRequest;
import it.tesi.cultural.auth.AuthController.UserResponse;
import it.tesi.cultural.security.JwtService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            JdbcTemplate jdbcTemplate,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public UserResponse register(RegisterRequest request) {
        // BCrypt considera al massimo 72 byte: il limite va misurato sulla codifica UTF-8.
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password exceeds 72 UTF-8 bytes");
        }
        try {
            return jdbcTemplate.queryForObject("""
                    INSERT INTO users (username, email, password_hash)
                    VALUES (?, ?, ?)
                    RETURNING id, username, email, role::text AS role, created_at
                    """, USER_ROW_MAPPER, request.username(), request.email(),
                    passwordEncoder.encode(request.password()));
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Username or email is already registered");
        }
    }

    public AuthResponse login(LoginRequest request) {
        // La stessa verifica preliminare evita comportamenti diversi da quelli di BCrypt.
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        Optional<Credentials> credentials;
        try {
            credentials = Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT username, password_hash, role::text AS role
                    FROM users
                    WHERE username = ?
                    """, CREDENTIALS_ROW_MAPPER, request.username()));
        } catch (EmptyResultDataAccessException exception) {
            credentials = Optional.empty();
        }

        // La risposta d'errore resta identica se manca l'utente o la password non coincide.
        Credentials user = credentials
                .filter(value -> passwordEncoder.matches(request.password(), value.passwordHash()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Invalid username or password"));
        return new AuthResponse(
                jwtService.createToken(user.username(), user.role()),
                "Bearer",
                jwtService.getExpirationSeconds());
    }

    private record Credentials(String username, String passwordHash, String role) {}

    private static final RowMapper<UserResponse> USER_ROW_MAPPER = (resultSet, rowNum) ->
            new UserResponse(
                    resultSet.getObject("id", Integer.class),
                    resultSet.getString("username"),
                    resultSet.getString("email"),
                    resultSet.getString("role"),
                    resultSet.getObject("created_at", java.time.OffsetDateTime.class));

    private static final RowMapper<Credentials> CREDENTIALS_ROW_MAPPER =
            (resultSet, rowNum) -> mapCredentials(resultSet);

    private static Credentials mapCredentials(ResultSet resultSet) throws SQLException {
        return new Credentials(
                resultSet.getString("username"),
                resultSet.getString("password_hash"),
                resultSet.getString("role"));
    }
}
