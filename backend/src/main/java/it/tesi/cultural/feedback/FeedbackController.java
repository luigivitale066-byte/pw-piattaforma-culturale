package it.tesi.cultural.feedback;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/feedbacks")
public class FeedbackController {

    private final JdbcTemplate jdbc;

    public FeedbackController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    List<Feedback> list(
            @RequestParam(required = false) Integer itemId,
            @RequestParam(required = false) Integer eventId) {
        if (itemId != null && eventId != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose either itemId or eventId");
        }
        return jdbc.query("""
                SELECT id, user_id, item_id, event_id, rating, comment, created_at FROM feedbacks
                WHERE (CAST(? AS integer) IS NULL OR item_id = ?)
                  AND (CAST(? AS integer) IS NULL OR event_id = ?)
                ORDER BY created_at DESC, id
                """, FEEDBACK_MAPPER, itemId, itemId, eventId, eventId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Feedback create(@Valid @RequestBody FeedbackRequest request, Authentication authentication) {
        if ((request.itemId() == null) == (request.eventId() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose exactly one item or event");
        }
        String table = request.itemId() != null ? "catalog_items" : "events";
        Integer target = request.itemId() != null ? request.itemId() : request.eventId();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = ?", Long.class, target) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Feedback target not found");
        }
        return jdbc.queryForObject("""
                INSERT INTO feedbacks (user_id, item_id, event_id, rating, comment)
                SELECT id, ?, ?, ?, ? FROM users WHERE username = ?
                RETURNING id, user_id, item_id, event_id, rating, comment, created_at
                """, FEEDBACK_MAPPER, request.itemId(), request.eventId(), request.rating(),
                request.comment(), authentication.getName());
    }

    public record FeedbackRequest(
            Integer itemId, Integer eventId, @NotNull @Min(1) @Max(5) Integer rating,
            @Size(max = 5000) String comment) {}

    public record Feedback(
            Integer id, Integer userId, Integer itemId, Integer eventId, Integer rating,
            String comment, OffsetDateTime createdAt) {}

    private static final RowMapper<Feedback> FEEDBACK_MAPPER = (row, index) -> new Feedback(
            row.getObject("id", Integer.class), row.getObject("user_id", Integer.class),
            row.getObject("item_id", Integer.class), row.getObject("event_id", Integer.class),
            row.getObject("rating", Integer.class), row.getString("comment"),
            row.getObject("created_at", OffsetDateTime.class));
}
