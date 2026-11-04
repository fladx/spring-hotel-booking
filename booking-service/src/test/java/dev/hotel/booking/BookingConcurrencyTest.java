package dev.hotel.booking;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.hotel.common.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "eureka.client.enabled=false",
      "spring.datasource.url=jdbc:h2:mem:booking-concurrency;DB_CLOSE_DELAY=-1",
      "booking.compensation-delay-ms=3600000"
    })
class BookingConcurrencyTest {
  @Autowired BookingService service;
  @Autowired BookingRepository bookings;
  @Autowired UserRepository users;
  @MockitoBean HotelClient hotel;
  @MockitoBean JwtDecoder decoder;

  ExecutorService executor;
  Long ownerId;
  final String token = "owner-token";

  @BeforeEach
  void setup() {
    executor = Executors.newFixedThreadPool(4);
    bookings.deleteAll();
    service.credentials.clear();
    ownerId = users.saveAndFlush(new UserAccount("u" + UUID.randomUUID(), "hash", "USER")).id;
    when(decoder.decode(anyString()))
        .thenAnswer(
            invocation ->
                Jwt.withTokenValue(invocation.getArgument(0))
                    .header("alg", "test")
                    .subject(ownerId.toString())
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build());
    when(hotel.reserve(any(), anyString()))
        .thenAnswer(
            invocation -> {
              assertNoTransaction();
              return 1L;
            });
    doAnswer(
            invocation -> {
              assertNoTransaction();
              return null;
            })
        .when(hotel)
        .release(any(), anyString());
  }

  @AfterEach
  void stopWorkers() throws Exception {
    executor.shutdownNow();
    assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void unrelatedBookingsProceedWhileOneReserveIsBlocked() throws Exception {
    var entered = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    blockReserve("parallel-1", entered, proceed);
    var blocked = executor.submit(() -> create("parallel-1"));
    try {
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      var independent = executor.submit(() -> create("parallel-2"));
      assertThat(independent.get(5, TimeUnit.SECONDS).status).isEqualTo("CONFIRMED");
      assertThat(blocked.isDone()).isFalse();
    } finally {
      proceed.countDown();
    }
    assertThat(blocked.get(10, TimeUnit.SECONDS).status).isEqualTo("CONFIRMED");
    verify(hotel, times(2)).reserve(any(), eq(token));
  }

  @Test
  void concurrentDuplicateWaitsForSameFinalBookingAndMismatchConflicts() throws Exception {
    var entered = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    blockReserve("duplicate", entered, proceed);
    var first = executor.submit(() -> create("duplicate"));
    Future<Booking> duplicate;
    Future<Booking> mismatch;
    try {
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      duplicate = executor.submit(() -> create("duplicate"));
      mismatch =
          executor.submit(
              () -> service.create(ownerId.toString(), token, request("duplicate", 2L)));
      await().atMost(Duration.ofSeconds(5)).until(() -> lockFor("duplicate").getQueueLength() == 2);
    } finally {
      proceed.countDown();
    }
    var created = first.get(10, TimeUnit.SECONDS);
    var replayed = duplicate.get(10, TimeUnit.SECONDS);
    assertThat(replayed.id).isEqualTo(created.id);
    assertThat(replayed.status).isEqualTo("CONFIRMED");
    assertThatThrownBy(() -> mismatch.get(10, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .cause()
        .isInstanceOfSatisfying(
            ApiException.class,
            failure -> assertThat(failure.status()).isEqualTo(HttpStatus.CONFLICT));
    verify(hotel).reserve(any(), eq(token));
    assertThat(bookings.count()).isEqualTo(1);
  }

  @Test
  void sameRequestIdForDifferentOwnersIsIndependent() {
    var other = users.saveAndFlush(new UserAccount("u" + UUID.randomUUID(), "hash", "USER"));
    var first = create("owner-scoped");
    var second = service.create(other.id.toString(), token, request("owner-scoped", 1L));
    assertThat(second.id).isNotEqualTo(first.id);
    verify(hotel, times(2)).reserve(any(), eq(token));
  }

  @Test
  void staleRetrySnapshotDoesNotReleaseAlreadyCompletedCompensation() throws Exception {
    var booking = pendingCompensation("stale-retry", 0);
    var lock = lockFor(booking.requestId);
    lock.lock();
    Future<?> retry;
    try {
      retry = executor.submit(service::retry);
      await().atMost(Duration.ofSeconds(5)).until(lock::hasQueuedThreads);
      service.tx.executeWithoutResult(
          tx -> {
            var current = bookings.lockById(booking.id).orElseThrow();
            current.compensationPending = false;
            bookings.saveAndFlush(current);
          });
      service.credentials.remove(booking.id);
    } finally {
      lock.unlock();
    }
    retry.get(10, TimeUnit.SECONDS);
    var current = bookings.findById(booking.id).orElseThrow();
    assertThat(current.compensationPending).isFalse();
    assertThat(current.autoRetryEnabled).isTrue();
    assertThat(current.compensationAttempts).isZero();
    verify(hotel, never()).release(any(), anyString());
  }

  @Test
  void recoveryRechecksStateAfterWaitingForLiveCreation() throws Exception {
    var entered = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    blockReserve("recovery", entered, proceed);
    var creating = executor.submit(() -> create("recovery"));
    Future<?> recovery;
    try {
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      recovery = executor.submit(service::recover);
      await().atMost(Duration.ofSeconds(5)).until(lockFor("recovery")::hasQueuedThreads);
    } finally {
      proceed.countDown();
    }
    var created = creating.get(10, TimeUnit.SECONDS);
    recovery.get(10, TimeUnit.SECONDS);
    assertThat(bookings.findById(created.id).orElseThrow().status).isEqualTo("CONFIRMED");
    verify(hotel, never()).release(any(), anyString());
  }

  @Test
  void freshOwnerCancellationWaitsForOldRetryAndResetsItsBudget() throws Exception {
    var booking = pendingCompensation("fresh-owner", 7);
    var entered = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              assertNoTransaction();
              entered.countDown();
              assertThat(proceed.await(10, TimeUnit.SECONDS)).isTrue();
              throw new IllegalStateException("Old release failed");
            })
        .when(hotel)
        .release(any(), eq(token));
    var oldRetry = executor.submit(() -> service.attempt(booking));
    Future<Booking> cancellation;
    try {
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      cancellation =
          executor.submit(() -> service.cancel(booking.id, ownerId.toString(), "fresh-token"));
      await().atMost(Duration.ofSeconds(5)).until(lockFor(booking.requestId)::hasQueuedThreads);
    } finally {
      proceed.countDown();
    }
    oldRetry.get(10, TimeUnit.SECONDS);
    var cancelled = cancellation.get(10, TimeUnit.SECONDS);
    assertThat(cancelled.status).isEqualTo("CANCELLED");
    assertThat(cancelled.compensationPending).isFalse();
    assertThat(cancelled.compensationAttempts).isEqualTo(1);
    assertThat(service.credentials).doesNotContainKey(booking.id);
    verify(hotel).release(any(), eq(token));
    verify(hotel).release(any(), eq("fresh-token"));
  }

  @Test
  void concurrentRetriesOnlySpendTheLastAvailableAttemptOnce() throws Exception {
    var booking = pendingCompensation("last-attempt", 7);
    var entered = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              assertNoTransaction();
              entered.countDown();
              assertThat(proceed.await(10, TimeUnit.SECONDS)).isTrue();
              throw new IllegalStateException("Release unavailable");
            })
        .when(hotel)
        .release(any(), eq(token));
    var first = executor.submit(() -> service.attempt(booking));
    Future<?> duplicate;
    try {
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      duplicate = executor.submit(() -> service.attempt(booking));
      await().atMost(Duration.ofSeconds(5)).until(lockFor(booking.requestId)::hasQueuedThreads);
    } finally {
      proceed.countDown();
    }
    first.get(10, TimeUnit.SECONDS);
    duplicate.get(10, TimeUnit.SECONDS);
    service.tx.executeWithoutResult(
        tx -> {
          var current = bookings.lockById(booking.id).orElseThrow();
          current.nextAttempt = Instant.EPOCH;
          bookings.saveAndFlush(current);
        });
    service.attempt(booking);
    assertThat(bookings.findById(booking.id).orElseThrow().compensationAttempts).isEqualTo(8);
    verify(hotel).release(any(), eq(token));
  }

  @Test
  void parallelCredentialAdmissionRespectsHardLimit() throws Exception {
    var credential = new BookingService.Credential(token, Instant.now().plusSeconds(3600));
    for (long id = -9999; id < 0; id++) service.credentials.put(id, credential);
    var entered = new CountDownLatch(2);
    var proceed = new CountDownLatch(1);
    when(hotel.reserve(any(), eq(token)))
        .thenAnswer(
            invocation -> {
              assertNoTransaction();
              entered.countDown();
              assertThat(proceed.await(10, TimeUnit.SECONDS)).isTrue();
              return 1L;
            });
    var first = executor.submit(() -> create("capacity-1"));
    var second = executor.submit(() -> create("capacity-2"));
    try {
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(service.credentials).hasSize(10000);
    } finally {
      proceed.countDown();
    }
    first.get(10, TimeUnit.SECONDS);
    second.get(10, TimeUnit.SECONDS);
    assertThat(service.credentials).hasSize(9999);
  }

  private Booking create(String key) {
    return service.create(ownerId.toString(), token, request(key, 1L));
  }

  private BookingController.Create request(String key, Long roomId) {
    return new BookingController.Create(
        key, roomId, false, null, LocalDate.now().plusDays(1), LocalDate.now().plusDays(2));
  }

  private Booking pendingCompensation(String key, int attempts) {
    var booking = new Booking();
    booking.userId = ownerId;
    booking.requestId = key;
    booking.requestedRoomId = 1L;
    booking.startDate = LocalDate.now().plusDays(1);
    booking.endDate = LocalDate.now().plusDays(2);
    booking.status = "CANCELLED";
    booking.compensationPending = true;
    booking.compensationAttempts = attempts;
    booking.nextAttempt = Instant.EPOCH;
    booking = bookings.saveAndFlush(booking);
    service.credentials.put(
        booking.id, new BookingService.Credential(token, Instant.now().plusSeconds(3600)));
    return booking;
  }

  private void blockReserve(String key, CountDownLatch entered, CountDownLatch proceed) {
    when(hotel.reserve(any(), eq(token)))
        .thenAnswer(
            invocation -> {
              assertNoTransaction();
              Booking booking = invocation.getArgument(0);
              if (booking.requestId.equals(key)) {
                assertThat(bookings.findById(booking.id).orElseThrow().status).isEqualTo("PENDING");
                entered.countDown();
                assertThat(proceed.await(10, TimeUnit.SECONDS)).isTrue();
              }
              return 1L;
            });
  }

  private ReentrantLock lockFor(String key) {
    var locks = (ReentrantLock[]) ReflectionTestUtils.getField(service, "operationLocks");
    return locks[Math.floorMod(Objects.hash(ownerId, key), locks.length)];
  }

  private static void assertNoTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
        .as("Remote calls must not hold local database transactions")
        .isFalse();
  }
}
