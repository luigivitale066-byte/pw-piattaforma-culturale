package it.tesi.cultural;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_PASSWORD", matches = ".+")
class PostgresFlowTest {

    private static final String SCHEMA = "tesi_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String URL = System.getenv().getOrDefault(
            "TEST_DATABASE_URL", "jdbc:postgresql://localhost:5432/cultural_db");
    private static final String USER = System.getenv().getOrDefault("TEST_DATABASE_USERNAME", "postgres");
    private static final String PASSWORD = System.getenv("TEST_DATABASE_PASSWORD");

    @Autowired private TestRestTemplate http;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder encoder;
    private String admin;
    private String alice;
    private String bob;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
                // I test non toccano le tabelle locali: schema, chiave JWT e dati sono temporanei.
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("SET search_path TO " + SCHEMA + ", public");
            statement.execute(Files.readString(Path.of("..", "schema.sql")));
            statement.execute(Files.readString(Path.of("..", "schema.sql")));
        }
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        registry.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("app.jwt.secret-base64", () -> Base64.getEncoder().encodeToString(secret));
    }

    @AfterAll
    static void cleanup() throws Exception {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @BeforeEach
    void users() throws Exception {
                // Ogni scenario parte dagli stessi account e da sequenze azzerate.
        http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        jdbc.execute("TRUNCATE feedbacks, reservations, catalog_items, events, users RESTART IDENTITY");
        String hash = encoder.encode("TestPassword123!");
        for (String username : List.of("admin", "alice", "bob", "operator")) {
            jdbc.update("""
                    INSERT INTO users (username, email, password_hash, role)
                    VALUES (?, ?, ?, ?::role_enum)
                    """, username, username + "@example.test", hash,
                    username.equals("admin") ? "ROLE_ADMIN"
                            : username.equals("operator") ? "ROLE_OPERATOR" : "ROLE_USER");
        }
        admin = login("admin");
        alice = login("alice");
        bob = login("bob");
    }

    @Test
    void publicFrontendAndProtectedEndpoints() {
        assertThat(http.getForEntity("/", String.class).getBody()).contains("Trame", "/app.js");
        assertThat(http.getForEntity("/app.js", String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.getForEntity("/styles.css", String.class).getStatusCode().value()).isEqualTo(200);
        assertStatus(request(HttpMethod.GET, "/api/events", null, null), 200);
        assertStatus(request(HttpMethod.GET, "/api/catalog", null, null), 200);
        assertStatus(request(HttpMethod.GET, "/api/feedbacks", null, null), 200);
        assertStatus(request(HttpMethod.POST, "/api/events/" + 999999 + "/reservations",
                Map.of("seats", 1), null), 401);
        assertStatus(request(HttpMethod.GET, "/api/users", null, alice), 403);
        assertStatus(request(HttpMethod.GET, "/api/admin/stats", null, alice), 403);
        assertStatus(request(HttpMethod.GET, "/api/reservations", null, "invalid-token"), 401);
    }

    @Test
    void registrationLoginAndProfile() throws Exception {
        var registered = request(HttpMethod.POST, "/api/auth/register", Map.of(
                "username", "new-user", "email", "new@example.test", "password", "Password123!",
                "role", "ROLE_ADMIN"), null);
        assertStatus(registered, 201);
        assertThat(body(registered).get("id").isIntegralNumber()).isTrue();
        assertThat(body(registered).get("id").asInt()).isEqualTo(5);
        assertThat(body(registered).get("role").asText()).isEqualTo("ROLE_USER");
        assertThat(registered.getBody()).doesNotContain("password_hash", "Password123!");
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE username='new-user'", String.class))
                .startsWith("$2");
        String token = body(request(HttpMethod.POST, "/api/auth/login",
                Map.of("username", "new-user", "password", "Password123!"), null)).get("token").asText();
        assertStatus(request(HttpMethod.GET, "/api/users/me", null, token), 200);
        assertStatus(request(HttpMethod.POST, "/api/auth/login",
                Map.of("username", "alice", "password", "wrong"), null), 401);
        assertStatus(request(HttpMethod.POST, "/api/auth/register", Map.of(
                "username", "alice", "email", "another@example.test", "password", "Password123!"), null), 409);
        assertStatus(request(HttpMethod.POST, "/api/auth/register", Map.of(
                "username", "unicode", "email", "unicode@example.test", "password", "\u00e8".repeat(40)), null), 400);
    }

    @Test
    void catalogAndEventValidationAndRoles() throws Exception {
        var item = Map.of("title", "Opera di prova", "author", "Autore", "category", "Pittura",
                "mediaUrl", "https://example.test/opera");
        assertStatus(request(HttpMethod.POST, "/api/catalog", item, alice), 403);
        assertStatus(request(HttpMethod.POST, "/api/catalog", item, null), 401);
        var created = request(HttpMethod.POST, "/api/catalog", item, login("operator"));
        assertStatus(created, 201);
        assertThat(body(created).get("id").isIntegralNumber()).isTrue();
        assertThat(body(created).get("mediaUrl").asText()).isEqualTo("https://example.test/opera");
        assertThat(body(request(HttpMethod.GET, "/api/catalog?query=opera&category=Pittura", null, null)).size()).isEqualTo(1);
        assertStatus(request(HttpMethod.GET, "/api/catalog?page=-1", null, null), 400);
        assertStatus(request(HttpMethod.GET, "/api/events/not-a-number", null, null), 400);
        assertStatus(request(HttpMethod.POST, "/api/events", Map.of("title", "Missing fields"), admin), 400);
        assertStatus(request(HttpMethod.POST, "/api/events", Map.of(
                "title", "Wrong dates", "capacity", 3, "startDatetime", "2027-01-02T10:00:00Z",
                "endDatetime", "2027-01-01T10:00:00Z"), admin), 400);
        String event = event(3);
        assertThat(body(request(HttpMethod.GET, "/api/events/" + event, null, null)).get("availableSeats").asInt()).isEqualTo(3);
        assertStatus(request(HttpMethod.GET, "/api/events?from=invalid", null, null), 400);
    }

    @Test
    void reservationOwnershipCapacityCancellationAndRebooking() throws Exception {
        String event = event(2);
        var created = request(HttpMethod.POST, "/api/events/" + event + "/reservations", Map.of("seats", 2), alice);
        assertStatus(created, 201);
        assertThat(body(created).get("id").isIntegralNumber()).isTrue();
        assertThat(body(created).get("userId").isIntegralNumber()).isTrue();
        assertThat(body(created).get("eventId").isIntegralNumber()).isTrue();
        String id = body(created).get("id").asText();
        assertStatus(request(HttpMethod.POST, "/api/events/" + event + "/reservations", Map.of("seats", 1), bob), 409);
        assertStatus(request(HttpMethod.PUT, "/api/reservations/" + id + "/cancel", null, bob), 404);
        assertThat(body(request(HttpMethod.GET, "/api/reservations", null, bob)).size()).isZero();
        var adminView = body(request(HttpMethod.GET, "/api/reservations", null, admin));
        assertThat(adminView.size()).isEqualTo(1);
        assertThat(adminView.get(0).get("username").asText()).isEqualTo("alice");
        var operatorView = body(request(HttpMethod.GET, "/api/reservations", null, login("operator")));
        assertThat(operatorView.size()).isEqualTo(1);
        assertThat(operatorView.get(0).get("username").asText()).isEqualTo("alice");
        assertStatus(request(HttpMethod.PUT, "/api/reservations/" + id + "/cancel", null, alice), 200);
        assertThat(body(request(HttpMethod.GET, "/api/reservations", null, alice)).size()).isZero();
        assertThat(body(request(HttpMethod.GET, "/api/reservations", null, admin)).size()).isZero();
        assertStatus(request(HttpMethod.POST, "/api/events/" + event + "/reservations", Map.of("seats", 1), alice), 201);
        assertStatus(request(HttpMethod.POST, "/api/events/" + event + "/reservations", Map.of("seats", 1), alice), 409);
        assertStatus(request(HttpMethod.POST, "/api/events/" + event + "/reservations", Map.of("seats", 0), bob), 400);
    }

    @Test
    void concurrentReservationsNeverExceedCapacity() throws Exception {
                // Le due richieste partono insieme per contendersi l'unico posto disponibile.
        String event = event(1);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return request(HttpMethod.POST, "/api/events/" + event + "/reservations", Map.of("seats", 1), alice);
            });
            var second = executor.submit(() -> {
                start.await();
                return request(HttpMethod.POST, "/api/events/" + event + "/reservations", Map.of("seats", 1), bob);
            });
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS).getStatusCode().value(),
                    second.get(15, TimeUnit.SECONDS).getStatusCode().value())).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.queryForObject("SELECT SUM(seats) FROM reservations WHERE event_id = ?",
                Long.class, Integer.valueOf(event))).isEqualTo(1);
    }

    @Test
    void feedbackAndStatistics() throws Exception {
        String event = event(2);
        Integer aliceId = jdbc.queryForObject("SELECT id FROM users WHERE username='alice'", Integer.class);
        var created = request(HttpMethod.POST, "/api/feedbacks", Map.of(
                "eventId", event, "rating", 5, "comment", "<script>not executable</script>",
                "userId", 999999), alice);
        assertStatus(created, 201);
        assertThat(body(created).get("userId").asInt()).isEqualTo(aliceId);
        assertStatus(request(HttpMethod.POST, "/api/feedbacks", Map.of("rating", 5), alice), 400);
        assertStatus(request(HttpMethod.POST, "/api/feedbacks", Map.of("eventId", event, "rating", 6), alice), 400);
        assertThat(body(request(HttpMethod.GET, "/api/feedbacks?eventId=" + event, null, null)).size()).isEqualTo(1);
        assertStatus(request(HttpMethod.POST, "/api/events/" + event + "/reservations", Map.of("seats", 1), bob), 201);
        JsonNode stats = body(request(HttpMethod.GET, "/api/admin/stats", null, admin));
        assertThat(stats.get("totalUsers").asInt()).isEqualTo(4);
        assertThat(stats.get("totalReservations").asInt()).isEqualTo(1);
        assertThat(stats.get("eventsOccupancy").get(0).get("occupancyPct").asDouble()).isEqualTo(50);
        var users = request(HttpMethod.GET, "/api/users", null, admin);
        assertStatus(users, 200);
        assertThat(users.getBody()).doesNotContain("password");
        jdbc.update("DELETE FROM users WHERE id = ?", aliceId);
        assertThat(jdbc.queryForObject("SELECT user_id FROM feedbacks", Integer.class)).isNull();
        assertStatus(request(HttpMethod.GET, "/api/users/me", null, alice), 401);
    }

    @Test
    void onlyAdminsCanChangeOtherUsersRolesAndPermissionsUpdateImmediately() throws Exception {
        int aliceId = jdbc.queryForObject("SELECT id FROM users WHERE username='alice'", Integer.class);
        int adminId = jdbc.queryForObject("SELECT id FROM users WHERE username='admin'", Integer.class);
        String path = "/api/admin/users/" + aliceId + "/role";
        assertStatus(request(HttpMethod.PUT, path, Map.of("role", "ROLE_OPERATOR"), null), 401);
        assertStatus(request(HttpMethod.PUT, path, Map.of("role", "ROLE_ADMIN"), alice), 403);
        assertStatus(request(HttpMethod.PUT, path, Map.of("role", "ROLE_ADMIN"), login("operator")), 403);
        assertStatus(request(HttpMethod.PUT, path, Map.of(), admin), 400);
        assertStatus(request(HttpMethod.PUT, path, Map.of("role", "ROLE_UNKNOWN"), admin), 400);
        assertStatus(request(HttpMethod.PUT, path, "{\"role\":null}", admin), 400);
        assertStatus(request(HttpMethod.PUT, "/api/admin/users/999999/role",
                Map.of("role", "ROLE_OPERATOR"), admin), 404);
        assertStatus(request(HttpMethod.PUT, "/api/admin/users/" + adminId + "/role",
                Map.of("role", "ROLE_USER"), admin), 409);

        var promoted = request(HttpMethod.PUT, path, Map.of("role", "ROLE_OPERATOR"), admin);
        assertStatus(promoted, 200);
        assertThat(body(promoted).get("role").asText()).isEqualTo("ROLE_OPERATOR");
        assertThat(promoted.getBody()).doesNotContain("password");
        var event = Map.of("title", "Role test", "capacity", 5, "startDatetime", "2027-01-01T10:00:00Z");
        assertStatus(request(HttpMethod.POST, "/api/events", event, alice), 201);
        assertStatus(request(HttpMethod.PUT, path, Map.of("role", "ROLE_ADMIN"), admin), 200);
        assertStatus(request(HttpMethod.GET, "/api/users", null, alice), 200);
        assertStatus(request(HttpMethod.PUT, path, Map.of("role", "ROLE_USER"), admin), 200);
        assertStatus(request(HttpMethod.GET, "/api/users", null, alice), 403);
        assertStatus(request(HttpMethod.POST, "/api/events", event, alice), 403);
        assertThat(jdbc.queryForObject("SELECT role::text FROM users WHERE id=?", String.class, adminId))
                .isEqualTo("ROLE_ADMIN");
    }

    @Test
    void concurrentAdminsCannotRemoveEachOthersAccess() throws Exception {
                // Il lock sul cambio ruolo deve impedire che entrambi perdano i privilegi.
        int aliceId = jdbc.queryForObject("SELECT id FROM users WHERE username='alice'", Integer.class);
        int adminId = jdbc.queryForObject("SELECT id FROM users WHERE username='admin'", Integer.class);
        assertStatus(request(HttpMethod.PUT, "/api/admin/users/" + aliceId + "/role",
                Map.of("role", "ROLE_ADMIN"), admin), 200);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return request(HttpMethod.PUT, "/api/admin/users/" + aliceId + "/role",
                        Map.of("role", "ROLE_USER"), admin).getStatusCode().value();
            });
            var second = executor.submit(() -> {
                start.await();
                return request(HttpMethod.PUT, "/api/admin/users/" + adminId + "/role",
                        Map.of("role", "ROLE_USER"), alice).getStatusCode().value();
            });
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 403);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role='ROLE_ADMIN'", Integer.class))
                .isEqualTo(1);
    }

    private String event(int capacity) throws Exception {
        var response = request(HttpMethod.POST, "/api/events", Map.of(
                "title", "Visita di prova", "startDatetime", "2027-06-01T10:00:00Z",
                "capacity", capacity), admin);
        assertStatus(response, 201);
        return body(response).get("id").asText();
    }

    private String login(String username) throws Exception {
        var response = request(HttpMethod.POST, "/api/auth/login",
                Map.of("username", username, "password", "TestPassword123!"), null);
        assertStatus(response, 200);
        JsonNode payload = body(response);
        assertThat(payload.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(payload.get("expiresIn").asLong()).isEqualTo(3600);
        return payload.get("token").asText();
    }

    private ResponseEntity<String> request(HttpMethod method, String path, Object payload, String token) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return http.exchange(path, method, new HttpEntity<>(payload, headers), String.class);
    }

    private JsonNode body(ResponseEntity<String> response) throws Exception {
        return json.readTree(response.getBody());
    }

    private void assertStatus(ResponseEntity<String> response, int expected) {
        assertThat(response.getStatusCode().value()).withFailMessage("%s", response.getBody()).isEqualTo(expected);
    }
}
