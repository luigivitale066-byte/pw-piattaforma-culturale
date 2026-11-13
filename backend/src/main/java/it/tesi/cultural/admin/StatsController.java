package it.tesi.cultural.admin;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatsController {

    private final JdbcTemplate jdbc;

    public StatsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/admin/stats")
    Stats stats() {
        long users = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        long reservations = jdbc.queryForObject("SELECT COUNT(*) FROM reservations", Long.class);
        List<Occupancy> occupancy = jdbc.query("""
                SELECT e.id, CASE WHEN e.capacity = 0 THEN 0
                    ELSE ROUND(100.0 * COALESCE(SUM(r.seats), 0) / e.capacity, 2)
                    END AS occupancy
                FROM events e LEFT JOIN reservations r
                  ON r.event_id = e.id AND r.status IN ('PENDING', 'CONFIRMED')
                GROUP BY e.id, e.capacity
                ORDER BY e.start_datetime, e.id
                """, (row, index) -> new Occupancy(row.getObject("id", Integer.class),
                        row.getBigDecimal("occupancy")));
        return new Stats(users, reservations, occupancy);
    }

    public record Stats(long totalUsers, long totalReservations, List<Occupancy> eventsOccupancy) {}
    public record Occupancy(Integer eventId, BigDecimal occupancyPct) {}
}
