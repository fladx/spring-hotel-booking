package dev.hotel.booking;

import dev.hotel.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('USER','ADMIN')")
@RestController
@RequestMapping("/api/bookings")
class BookingController {
  record Create(
      @NotBlank @Pattern(regexp = "[a-zA-Z0-9_-]{1,64}") String requestId,
      Long roomId,
      boolean autoSelect,
      @Positive Long hotelId,
      @NotNull @FutureOrPresent LocalDate startDate,
      @NotNull LocalDate endDate) {}

  record View(
      Long id,
      String requestId,
      Long roomId,
      Long hotelId,
      LocalDate startDate,
      LocalDate endDate,
      String status,
      java.time.Instant createdAt,
      boolean compensationPending,
      int compensationAttempts,
      boolean ownerRetryRequired) {
    static View of(Booking b) {
      return new View(
          b.id,
          b.requestId,
          b.roomId,
          b.hotelId,
          b.startDate,
          b.endDate,
          b.status,
          b.createdAt,
          b.compensationPending,
          b.compensationAttempts,
          b.compensationPending && (!b.autoRetryEnabled || b.compensationAttempts >= 8));
    }
  }

  final BookingService service;
  final BookingRepository bookings;

  BookingController(BookingService s, BookingRepository b) {
    service = s;
    bookings = b;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  View create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Create c) {
    if (!c.endDate().isAfter(c.startDate()) || c.endDate().isAfter(c.startDate().plusDays(365)))
      throw new ApiException(HttpStatus.BAD_REQUEST, "Stay must be 1 to 365 nights");
    if (!c.autoSelect() && (c.roomId() == null || c.roomId() <= 0))
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "Specify a positive roomId or enable autoSelect");
    return View.of(service.create(jwt.getSubject(), jwt.getTokenValue(), c));
  }

  @GetMapping
  Page<View> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return bookings
        .findByUserId(Long.valueOf(jwt.getSubject()), Pages.of(page, size))
        .map(View::of);
  }

  @GetMapping("/{id}")
  View get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
    return View.of(service.own(id, jwt.getSubject()));
  }

  @DeleteMapping("/{id}")
  View cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
    return View.of(service.cancel(id, jwt.getSubject(), jwt.getTokenValue()));
  }
}
