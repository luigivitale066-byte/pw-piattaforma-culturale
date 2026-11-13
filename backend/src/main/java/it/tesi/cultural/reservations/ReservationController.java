package it.tesi.cultural.reservations;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/events/{id}/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    Reservation create(
            @PathVariable Integer id,
            @Valid @RequestBody ReservationRequest request,
            Authentication authentication) {
        return reservationService.create(id, request.seats(), authentication.getName());
    }

    @GetMapping("/reservations")
    List<Reservation> list(Authentication authentication) {
        boolean isStaff = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN")
                        || authority.getAuthority().equals("ROLE_OPERATOR"));
        return reservationService.list(authentication.getName(), isStaff);
    }

    @PutMapping("/reservations/{id}/cancel")
    Reservation cancel(@PathVariable Integer id, Authentication authentication) {
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
        return reservationService.cancel(id, authentication.getName(), isAdmin);
    }

    public record ReservationRequest(@Positive int seats) {}

    public record Reservation(
            Integer id, Integer userId, String username, Integer eventId, int seats, String status, OffsetDateTime createdAt) {}
}
