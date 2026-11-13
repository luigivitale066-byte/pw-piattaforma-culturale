package it.tesi.cultural.events;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final JdbcTemplate jdbcTemplate;

    public EventController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    List<Event> list(
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to) {
        // Respinge subito un intervallo incoerente, prima di interrogare il database.
        if (from != null && to != null && to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'to' must not be before 'from'");
        }
        return jdbcTemplate.query("""
                SELECT id, title, description, start_datetime, end_datetime, capacity, price, created_at,
                    capacity - (SELECT COALESCE(SUM(r.seats), 0) FROM reservations r
                        WHERE r.event_id = events.id AND r.status IN ('PENDING', 'CONFIRMED')) AS available_seats
                FROM events
                WHERE (CAST(? AS timestamptz) IS NULL OR start_datetime >= ?)
                  AND (CAST(? AS timestamptz) IS NULL OR start_datetime <= ?)
                ORDER BY start_datetime, id
                """, EVENT_ROW_MAPPER, from, from, to, to);
    }

    @GetMapping("/{id}")
    Event get(@PathVariable Integer id) {
        List<Event> events = jdbcTemplate.query("""
                SELECT id, title, description, start_datetime, end_datetime, capacity, price, created_at,
                    capacity - (SELECT COALESCE(SUM(r.seats), 0) FROM reservations r
                        WHERE r.event_id = events.id AND r.status IN ('PENDING', 'CONFIRMED')) AS available_seats
                FROM events WHERE id = ?
                """, EVENT_ROW_MAPPER, id);
        if (events.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found");
        }
        return events.get(0);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_OPERATOR')")
    Event create(@Valid @RequestBody EventRequest request) {
        // Il database applica lo stesso vincolo, ma qui l'errore diventa piu leggibile.
        if (request.endDatetime() != null && request.endDatetime().isBefore(request.startDatetime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "endDatetime must not be before startDatetime");
        }
        return jdbcTemplate.queryForObject("""
                INSERT INTO events (title, description, start_datetime, end_datetime, capacity, price)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id, title, description, start_datetime, end_datetime, capacity, price, created_at,
                    capacity AS available_seats
                """, EVENT_ROW_MAPPER, request.title(), request.description(), request.startDatetime(),
                request.endDatetime(), request.capacity(),
                request.price() == null ? BigDecimal.ZERO : request.price());
    }

    public record Event(
            Integer id, String title, String description, OffsetDateTime startDatetime,
            OffsetDateTime endDatetime, int capacity, BigDecimal price, OffsetDateTime createdAt,
            long availableSeats) {}

    public record EventRequest(
            @NotBlank @Size(max = 512) String title,
            String description,
            @NotNull
            OffsetDateTime startDatetime,
            OffsetDateTime endDatetime,
            @NotNull @PositiveOrZero Integer capacity,
            @PositiveOrZero @Digits(integer = 8, fraction = 2) BigDecimal price) {}

    private static final RowMapper<Event> EVENT_ROW_MAPPER = (resultSet, rowNum) ->
            new Event(
                    resultSet.getObject("id", Integer.class),
                    resultSet.getString("title"),
                    resultSet.getString("description"),
                    resultSet.getObject("start_datetime", OffsetDateTime.class),
                    resultSet.getObject("end_datetime", OffsetDateTime.class),
                    resultSet.getInt("capacity"),
                    resultSet.getBigDecimal("price"),
                    resultSet.getObject("created_at", OffsetDateTime.class),
                    resultSet.getLong("available_seats"));
}
