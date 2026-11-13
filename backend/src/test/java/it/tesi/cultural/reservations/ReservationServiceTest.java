package it.tesi.cultural.reservations;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import it.tesi.cultural.reservations.ReservationController.Reservation;
import org.mockito.ArgumentMatchers;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private ReservationService reservationService;

    @Test
    void rejectsReservationWhenEventCapacityIsAlreadyFull() {
        Integer eventId = 1;
        when(jdbcTemplate.queryForObject(
                anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(5, 1);
        when(jdbcTemplate.queryForObject(
                anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(5L);

        assertThatThrownBy(() -> reservationService.create(eventId, 1, "alice"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException statusException = (ResponseStatusException) exception;
                    org.assertj.core.api.Assertions.assertThat(statusException.getStatusCode())
                            .isEqualTo(HttpStatus.CONFLICT);
                });

        verify(jdbcTemplate, never()).queryForObject(
                startsWith("INSERT INTO reservations"),
                ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Reservation>>any(),
                any(Object[].class));
    }
}
