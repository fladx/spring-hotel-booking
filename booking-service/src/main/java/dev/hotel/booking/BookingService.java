package dev.hotel.booking;

import dev.hotel.common.ApiException;
import java.time.*;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class BookingService {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(BookingService.class);
  final BookingRepository bookings;
  final HotelClient hotel;
  final TransactionTemplate tx;

  // Bounded, reentrant locks serialize one owner/request saga without holding a DB transaction.
  // Hash collisions may serialize unrelated bookings; no lock is ever removed while waiters exist.
  private final ReentrantLock[] operationLocks = new ReentrantLock[256];

  record Credential(String token, Instant expires) {}

  final ConcurrentHashMap<Long, Credential> credentials = new ConcurrentHashMap<>();
  final JwtDecoder decoder;
  final UserRepository users;

  BookingService(
      BookingRepository b,
      HotelClient h,
      PlatformTransactionManager manager,
      JwtDecoder decoder,
      UserRepository users) {
    this.users = users;
    this.decoder = decoder;
    bookings = b;
    hotel = h;
    tx = new TransactionTemplate(manager);
    java.util.Arrays.setAll(operationLocks, i -> new ReentrantLock());
  }

  // Only local state transitions are transactional. HTTP never runs inside these transactions.
  Booking create(String user, String token, BookingController.Create c) {
    Long ownerId = Long.valueOf(user);
    return withBookingLock(ownerId, c.requestId(), () -> createLocked(ownerId, token, c));
  }

  private <T> T withBookingLock(Long ownerId, String requestId, Supplier<T> operation) {
    var lock =
        operationLocks[Math.floorMod(Objects.hash(ownerId, requestId), operationLocks.length)];
    lock.lock();
    try {
      return operation.get();
    } finally {
      lock.unlock();
    }
  }

  private Booking createLocked(Long ownerId, String token, BookingController.Create c) {
    if (!users.existsById(ownerId))
      throw new ApiException(HttpStatus.UNAUTHORIZED, "User account no longer exists");
    var old = bookings.findByUserIdAndRequestId(ownerId, c.requestId());
    if (old.isPresent()) return same(old.get(), c);
    Booking b = new Booking();
    b.userId = ownerId;
    b.requestId = c.requestId();
    b.requestedRoomId = c.autoSelect() ? null : c.roomId();
    b.autoSelect = c.autoSelect();
    b.hotelId = c.hotelId();
    b.startDate = c.startDate();
    b.endDate = c.endDate();
    b.status = "PENDING";
    try {
      Booking initial = b;
      b = tx.execute(s -> bookings.saveAndFlush(initial));
    } catch (DataIntegrityViolationException e) {
      return same(
          bookings
              .findByUserIdAndRequestId(ownerId, c.requestId())
              .orElseThrow(
                  () ->
                      new ApiException(
                          HttpStatus.CONFLICT, "Account changed while creating booking")),
          c);
    }
    final Long id = b.id;
    log.info("bookingId={} requestId={} state=PENDING", id, b.requestId);
    remember(id, token);
    try {
      Long room = hotel.reserve(b, token);
      return tx.execute(
          s -> {
            var current = bookings.lockById(id).orElseThrow();
            if (!"PENDING".equals(current.status))
              throw new ApiException(
                  HttpStatus.CONFLICT, "Booking was cancelled before confirmation");
            current.roomId = room;
            current.status = "CONFIRMED";
            log.info("bookingId={} requestId={} state=CONFIRMED", id, current.requestId);
            credentials.remove(id);
            return bookings.saveAndFlush(current);
          });
    } catch (RuntimeException failure) {
      Booking cancelled = markCancelled(id, token);
      attempt(cancelled);
      HttpStatus status =
          (failure instanceof org.springframework.web.client.HttpClientErrorException
                  || failure instanceof ApiException)
              ? HttpStatus.CONFLICT
              : HttpStatus.SERVICE_UNAVAILABLE;
      throw new ApiException(
          status, "Reservation failed; booking cancelled and release scheduled if needed");
    }
  }

  private void remember(Long id, String token) {
    try {
      var credential = new Credential(token, decoder.decode(token).getExpiresAt());
      // Admission must be atomic across different booking stripes to preserve the hard limit.
      synchronized (credentials) {
        credentials.entrySet().removeIf(e -> e.getValue().expires().isBefore(Instant.now()));
        if (credentials.size() >= 10000 && !credentials.containsKey(id)) return;
        credentials.put(id, credential);
      }
    } catch (org.springframework.security.oauth2.jwt.JwtException expired) {
      credentials.remove(id);
    }
  }

  Booking same(Booking b, BookingController.Create c) {
    if (!Objects.equals(b.requestedRoomId, c.autoSelect() ? null : c.roomId())
        || b.autoSelect != c.autoSelect()
        || !Objects.equals(b.hotelId, c.hotelId())
        || !b.startDate.equals(c.startDate())
        || !b.endDate.equals(c.endDate()))
      throw new ApiException(HttpStatus.CONFLICT, "requestId already used with different payload");
    return b;
  }

  Booking own(Long id, String user) {
    return bookings
        .findById(id)
        .filter(b -> b.userId.equals(Long.valueOf(user)))
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Booking not found"));
  }

  Booking cancel(Long id, String user, String token) {
    var snapshot = own(id, user);
    return withBookingLock(
        snapshot.userId,
        snapshot.requestId,
        () -> {
          var b = markCancelled(id, token);
          attempt(b);
          return own(id, user);
        });
  }

  private Booking markCancelled(Long id, String token) {
    if (token != null) remember(id, token);
    return tx.execute(
        s -> {
          var b = bookings.lockById(id).orElseThrow();
          b.status = "CANCELLED";
          log.info(
              "bookingId={} requestId={} state=CANCELLED compensationPending=true",
              id,
              b.requestId);
          b.compensationPending = true;
          b.autoRetryEnabled = true;
          b.compensationAttempts = 0;
          b.nextAttempt = Instant.now();
          return bookings.saveAndFlush(b);
        });
  }

  void attempt(Booking snapshot) {
    withBookingLock(
        snapshot.userId,
        snapshot.requestId,
        () -> {
          attemptLocked(snapshot.id);
          return null;
        });
  }

  private void attemptLocked(Long id) {
    // Scheduler snapshots may be stale by the time this stripe becomes available.
    var b = bookings.findById(id).orElse(null);
    if (b == null
        || !"CANCELLED".equals(b.status)
        || !b.compensationPending
        || !b.autoRetryEnabled
        || b.compensationAttempts >= 8
        || (b.nextAttempt != null && b.nextAttempt.isAfter(Instant.now()))) return;
    boolean released = false;
    try {
      var credential = credentials.get(b.id);
      if (credential == null || credential.expires().isBefore(Instant.now())) {
        credentials.remove(b.id);
        tx.executeWithoutResult(
            s -> {
              var current = bookings.lockById(b.id).orElseThrow();
              current.autoRetryEnabled = false;
              bookings.save(current);
            });
        log.warn(
            "bookingId={} requestId={} compensation requires fresh owner authentication",
            b.id,
            b.requestId);
        return;
      }
      hotel.release(b, credential.token());
      released = true;
    } catch (RuntimeException failure) {
      log.warn(
          "bookingId={} requestId={} compensation release failed type={}",
          b.id,
          b.requestId,
          failure.getClass().getSimpleName());
    }
    final boolean success = released;
    tx.executeWithoutResult(
        s -> {
          var current = bookings.lockById(b.id).orElseThrow();
          current.compensationAttempts++;
          current.nextAttempt =
              Instant.now().plusSeconds(Math.min(60, 5L * current.compensationAttempts));
          if (success) {
            current.compensationPending = false;
            credentials.remove(b.id);
          }
          log.info(
              "bookingId={} requestId={} compensationReleased={} attempts={}",
              b.id,
              b.requestId,
              success,
              current.compensationAttempts);
          bookings.save(current);
        });
  }

  @Scheduled(fixedDelayString = "${booking.compensation-delay-ms:5000}")
  void retry() {
    bookings
        .findTop50ByCompensationPendingTrueAndAutoRetryEnabledTrueAndCompensationAttemptsLessThan(8)
        .stream()
        .filter(b -> b.nextAttempt == null || !b.nextAttempt.isAfter(Instant.now()))
        .forEach(this::attempt);
  }

  @EventListener(ApplicationReadyEvent.class)
  void recover() {
    for (var snapshot : bookings.findByStatus("PENDING")) {
      withBookingLock(
          snapshot.userId,
          snapshot.requestId,
          () -> {
            // A live create may have completed while recovery waited for its stripe.
            var current = bookings.findById(snapshot.id).orElse(null);
            if (current != null && "PENDING".equals(current.status))
              attempt(markCancelled(current.id, null));
            return null;
          });
    }
  }
}
