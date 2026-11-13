package it.tesi.cultural.reservations;

import it.tesi.cultural.reservations.ReservationController.Reservation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReservationService {

    private final JdbcTemplate jdbcTemplate;

    public ReservationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Reservation create(Integer eventId, int seats, String username) {
        Integer capacity;
        try {
            capacity = jdbcTemplate.queryForObject(
                    "SELECT capacity FROM events WHERE id = ? FOR UPDATE",
                    Integer.class, eventId);
        } catch (org.springframework.dao.EmptyResultDataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found");
        }

        Integer userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username);
        if (userCount == null || userCount == 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user no longer exists");
        }

        Long reserved = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(seats), 0)
                FROM reservations
                WHERE event_id = ? AND status IN ('PENDING', 'CONFIRMED')
                """, Long.class, eventId);
        if (reserved + seats > capacity) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Not enough seats available");
        }

        try {
            return jdbcTemplate.queryForObject("""
                    WITH r AS (
                        INSERT INTO reservations (user_id, event_id, seats, status)
                        SELECT id, ?, ?, 'CONFIRMED'::reservation_status
                        FROM users
                        WHERE username = ?
                        RETURNING id, user_id, event_id, seats, status::text AS status, created_at
                    )
                    SELECT r.*, u.username FROM r JOIN users u ON u.id = r.user_id
                    """, RESERVATION_ROW_MAPPER, eventId, seats, username);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "An active reservation already exists for this event");
        }
    }

    @Transactional(readOnly = true)
    public List<Reservation> list(String username, boolean isStaff) {
        String sql = """
                SELECT r.id, r.user_id, u.username, r.event_id, r.seats, r.status::text AS status, r.created_at
                FROM reservations r
                JOIN users u ON u.id = r.user_id
                WHERE (? = TRUE OR u.username = ?)
                  AND r.status <> 'CANCELLED'
                ORDER BY r.created_at DESC
                """;
        return jdbcTemplate.query(sql, RESERVATION_ROW_MAPPER, isStaff, username);
    }

    @Transactional
    public Reservation cancel(Integer reservationId, String username, boolean isAdmin) {
        List<Reservation> reservations = jdbcTemplate.query("""
                UPDATE reservations r
                SET status = 'CANCELLED'
                FROM users u
                WHERE r.id = ?
                  AND r.user_id = u.id
                  AND (? = TRUE OR u.username = ?)
                  AND r.status IN ('PENDING', 'CONFIRMED')
                RETURNING r.id, r.user_id, u.username, r.event_id, r.seats,
                          r.status::text AS status, r.created_at
                """, RESERVATION_ROW_MAPPER, reservationId, isAdmin, username);
        if (reservations.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Active reservation not found for this user");
        }
        return reservations.get(0);
    }

    private static final RowMapper<Reservation> RESERVATION_ROW_MAPPER =
            (resultSet, rowNum) -> mapReservation(resultSet);

    private static Reservation mapReservation(ResultSet resultSet) throws SQLException {
        return new Reservation(
                resultSet.getObject("id", Integer.class),
                resultSet.getObject("user_id", Integer.class),
                resultSet.getString("username"),
                resultSet.getObject("event_id", Integer.class),
                resultSet.getInt("seats"),
                resultSet.getString("status"),
                resultSet.getObject("created_at", java.time.OffsetDateTime.class));
    }
}
