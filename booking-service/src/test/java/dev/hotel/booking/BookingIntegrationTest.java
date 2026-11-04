package dev.hotel.booking;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "eureka.client.enabled=false",
      "hotel.timeout-ms=150",
      "booking.compensation-delay-ms=3600000",
      "booking.demo-users=true"
    })
class BookingIntegrationTest {
  static final ExecutorService stubExecutor = Executors.newCachedThreadPool();
  static HttpServer stub;
  static AtomicInteger reserves = new AtomicInteger(), releases = new AtomicInteger();
  static volatile int reserveStatus = 200, releaseStatus = 204;
  static volatile boolean slow;
  static volatile boolean slowBefore;
  static volatile CountDownLatch lateDone;
  static final AtomicInteger inFlight = new AtomicInteger();
  static Set<String> allocated = ConcurrentHashMap.newKeySet(),
      tombstones = ConcurrentHashMap.newKeySet();
  static volatile String propagated;
  static volatile Runnable beforeReserveResponse;

  static {
    try {
      stub = HttpServer.create(new InetSocketAddress(0), 0);
      stub.setExecutor(stubExecutor);
      stub.createContext(
          "/internal/rooms/reserve",
          x -> {
            final int responseStatus = reserveStatus;
            final boolean delayBefore = slowBefore, delayAfter = slow;
            final Runnable callback = beforeReserveResponse;
            final CountDownLatch latch = lateDone;
            inFlight.incrementAndGet();
            reserves.incrementAndGet();
            propagated = x.getRequestHeaders().getFirst("Authorization");
            String key = new ObjectMapper().readTree(x.getRequestBody()).get("requestId").asText();
            if (delayBefore)
              try {
                Thread.sleep(400);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            if (responseStatus == 200 && !tombstones.contains(key)) allocated.add(key);
            if (delayAfter)
              try {
                Thread.sleep(400);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            if (callback != null) callback.run();
            byte[] response = "{\"roomId\":1}".getBytes();
            try {
              x.getResponseHeaders().set("Content-Type", "application/json");
              x.sendResponseHeaders(responseStatus, response.length);
              x.getResponseBody().write(response);
            } finally {
              x.close();
              if (latch != null) latch.countDown();
              inFlight.decrementAndGet();
            }
          });
      stub.createContext(
          "/internal/rooms/release",
          x -> {
            releases.incrementAndGet();
            String key = new ObjectMapper().readTree(x.getRequestBody()).get("requestId").asText();
            if (releaseStatus == 204) {
              tombstones.add(key);
              allocated.remove(key);
            }
            x.sendResponseHeaders(releaseStatus, -1);
            x.close();
          });
      stub.start();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("hotel.base-url", () -> "http://localhost:" + stub.getAddress().getPort());
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired BookingRepository bookings;
  @Autowired BookingService service;
  @Autowired HotelClient hotel;
  HttpClient http = HttpClient.newHttpClient();
  String token;
  String username;

  @BeforeEach
  void setup() throws Exception {
    reserves.set(0);
    releases.set(0);
    reserveStatus = 200;
    releaseStatus = 204;
    slow = false;
    slowBefore = false;
    lateDone = null;
    beforeReserveResponse = null;
    allocated.clear();
    tombstones.clear();
    bookings.deleteAll();
    service.credentials.clear();
    hotel.breaker.reset();
    token = register();
  }

  String register() throws Exception {
    username = "u" + UUID.randomUUID();
    return json.readTree(
            call(
                    "POST",
                    "/api/auth/register",
                    null,
                    json.writeValueAsString(
                        Map.of("username", username, "password", "password123")))
                .body())
        .get("accessToken")
        .asText();
  }

  String loginAgain() throws Exception {
    return json.readTree(
            call(
                    "POST",
                    "/api/auth/login",
                    null,
                    json.writeValueAsString(
                        Map.of("username", username, "password", "password123")))
                .body())
        .get("accessToken")
        .asText();
  }

  HttpResponse<String> call(String method, String path, String auth, String body) throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json");
    if (auth != null) b.header("Authorization", "Bearer " + auth);
    return http.send(
        b.method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  String payload(String key) {
    return "{\"requestId\":\""
        + key
        + "\",\"roomId\":1,\"startDate\":\""
        + LocalDate.now().plusDays(2)
        + "\",\"endDate\":\""
        + LocalDate.now().plusDays(4)
        + "\"}";
  }

  @Test
  void confirmedIdempotencyAndPayloadMismatch() throws Exception {
    var a = call("POST", "/api/bookings", token, payload("once"));
    assertThat(a.statusCode()).isEqualTo(201);
    assertThat(json.readTree(a.body()).get("status").asText()).isEqualTo("CONFIRMED");
    assertThat(call("POST", "/api/bookings", token, payload("once")).body()).isEqualTo(a.body());
    assertThat(reserves.get()).isEqualTo(1);
    assertThat(propagated).isEqualTo("Bearer " + token);
    assertThat(
            call(
                    "POST",
                    "/api/bookings",
                    token,
                    payload("once").replace("\"roomId\":1", "\"roomId\":2"))
                .statusCode())
        .isEqualTo(409);
  }

  @Test
  void ownAccessAndUserScopedKeys() throws Exception {
    var a = call("POST", "/api/bookings", token, payload("shared"));
    long id = json.readTree(a.body()).get("id").asLong();
    String other = register();
    assertThat(call("GET", "/api/bookings/" + id, other, null).statusCode()).isEqualTo(404);
    assertThat(call("DELETE", "/api/bookings/" + id, other, null).statusCode()).isEqualTo(404);
    assertThat(call("POST", "/api/bookings", other, payload("shared")).statusCode()).isEqualTo(201);
    assertThat(reserves.get()).isEqualTo(2);
  }

  @Test
  void conflictCompensates() throws Exception {
    reserveStatus = 409;
    assertThat(call("POST", "/api/bookings", token, payload("conflict")).statusCode())
        .isEqualTo(409);
    assertThat(bookings.findAll().get(0).status).isEqualTo("CANCELLED");
    assertThat(bookings.findAll().get(0).compensationPending).isFalse();
    assertThat(releases.get()).isEqualTo(1);
  }

  @Test
  void timeoutAfterAllocationCompensates() throws Exception {
    slow = true;
    assertThat(call("POST", "/api/bookings", token, payload("slow")).statusCode()).isEqualTo(503);
    assertThat(allocated).isEmpty();
    assertThat(tombstones).contains("slow");
    assertThat(bookings.findAll().get(0).status).isEqualTo("CANCELLED");
  }

  @Test
  void failedReleasePersistsPendingAndFreshOwnerRetryRecovers() throws Exception {
    reserveStatus = 409;
    releaseStatus = 503;
    call("POST", "/api/bookings", token, payload("recover"));
    var b = bookings.findAll().get(0);
    assertThat(b.compensationPending).isTrue();
    service.credentials.clear();
    token = loginAgain();
    releaseStatus = 204;
    hotel.breaker.reset();
    assertThat(call("DELETE", "/api/bookings/" + b.id, token, null).statusCode()).isEqualTo(200);
    assertThat(bookings.findById(b.id).orElseThrow().compensationPending).isFalse();
  }

  @Test
  void invalidDatesPagingAndSecurity() throws Exception {
    assertThat(call("POST", "/api/bookings", null, payload("x")).statusCode()).isEqualTo(401);
    assertThat(call("GET", "/api/users", token, null).statusCode()).isEqualTo(403);
    assertThat(call("GET", "/api/bookings?size=101", token, null).statusCode()).isEqualTo(400);
    assertThat(
            call(
                    "POST",
                    "/api/bookings",
                    token,
                    payload("bad")
                        .replace(
                            LocalDate.now().plusDays(4).toString(),
                            LocalDate.now().plusDays(1).toString()))
                .statusCode())
        .isEqualTo(400);
    assertThat(
            call("POST", "/api/bookings", token, payload("bad").replace("\"roomId\":1,", ""))
                .statusCode())
        .isEqualTo(400);
    assertThat(reserves.get()).isZero();
  }

  @Test
  void concurrentDuplicateOnlyReservesOnce() throws Exception {
    var executor = Executors.newFixedThreadPool(2);
    try {
      var a = executor.submit(() -> call("POST", "/api/bookings", token, payload("race")));
      var b = executor.submit(() -> call("POST", "/api/bookings", token, payload("race")));
      assertThat(a.get().statusCode()).isEqualTo(201);
      assertThat(b.get().statusCode()).isEqualTo(201);
      assertThat(reserves.get()).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void adminCrudAndBadCredentials() throws Exception {
    assertThat(
            call(
                    "POST",
                    "/api/auth/login",
                    null,
                    "{\"username\":\"admin\",\"password\":\"wrong-pass\"}")
                .statusCode())
        .isEqualTo(401);
    String admin =
        json.readTree(
                call(
                        "POST",
                        "/api/auth/login",
                        null,
                        "{\"username\":\"admin\",\"password\":\"admin-demo-123\"}")
                    .body())
            .get("accessToken")
            .asText();
    String name = "created" + UUID.randomUUID();
    var r =
        call(
            "POST",
            "/api/users",
            admin,
            "{\"username\":\"" + name + "\",\"password\":\"password123\",\"role\":\"USER\"}");
    assertThat(r.statusCode()).isEqualTo(201);
    long id = json.readTree(r.body()).get("id").asLong();
    assertThat(call("GET", "/api/users/" + id, admin, null).statusCode()).isEqualTo(200);
    assertThat(call("DELETE", "/api/users/" + id, admin, null).statusCode()).isEqualTo(204);
  }

  @Test
  void autoSelectIgnoresRoomIdForIdempotency() throws Exception {
    String body =
        payload("auto")
            .replace("\"roomId\":1,", "\"roomId\":99,\"autoSelect\":true,\"hotelId\":1,");
    assertThat(call("POST", "/api/bookings", token, body).statusCode()).isEqualTo(201);
    assertThat(
            call("POST", "/api/bookings", token, body.replace("\"roomId\":99", "\"roomId\":-1"))
                .statusCode())
        .isEqualTo(201);
    assertThat(reserves.get()).isEqualTo(1);
  }

  @Test
  void serverFailuresHaveBoundedRetriesAndCleanupBypassesOpenCircuit() throws Exception {
    reserveStatus = 503;
    assertThat(call("POST", "/api/bookings", token, payload("outage1")).statusCode())
        .isEqualTo(503);
    assertThat(reserves.get()).isEqualTo(2);
    assertThat(call("POST", "/api/bookings", token, payload("outage2")).statusCode())
        .isEqualTo(503);
    assertThat(reserves.get()).isEqualTo(3);
    assertThat(hotel.breaker.getState())
        .isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);
    assertThat(releases.get()).isEqualTo(2);
    assertThat(bookings.findAll()).allMatch(b -> !b.compensationPending);
  }

  @Test
  void businessConflictsDoNotOpenCircuit() throws Exception {
    reserveStatus = 409;
    for (int i = 0; i < 4; i++)
      assertThat(call("POST", "/api/bookings", token, payload("conflict" + i)).statusCode())
          .isEqualTo(409);
    assertThat(reserves.get()).isEqualTo(4);
    assertThat(hotel.breaker.getState())
        .isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED);
  }

  @Test
  void autoSelectAcrossHotelsAllowsNoHotelId() throws Exception {
    String body = payload("autoAll").replace("\"roomId\":1,", "\"roomId\":-1,\"autoSelect\":true,");
    assertThat(call("POST", "/api/bookings", token, body).statusCode()).isEqualTo(201);
  }

  @Test
  void cancellationByAnotherInstanceCannotBeRevivedByLateConfirmation() throws Exception {
    var observed = new AtomicReference<String>();
    beforeReserveResponse =
        () ->
            service.tx.executeWithoutResult(
                tx -> {
                  var b = bookings.findAll().get(0);
                  observed.set(b.status);
                  b.status = "CANCELLED";
                  b.compensationPending = true;
                  bookings.saveAndFlush(b);
                });
    assertThat(call("POST", "/api/bookings", token, payload("cancelRace")).statusCode())
        .isEqualTo(409);
    assertThat(observed.get()).isEqualTo("PENDING");
    var b = bookings.findAll().get(0);
    assertThat(b.status).isEqualTo("CANCELLED");
    assertThat(b.compensationPending).isFalse();
    assertThat(allocated).isEmpty();
    assertThat(releases.get()).isEqualTo(1);
  }

  @Test
  void delayedAllocationHonorsReleaseTombstoneAfterTimeout() throws Exception {
    slowBefore = true;
    lateDone = new CountDownLatch(2);
    assertThat(call("POST", "/api/bookings", token, payload("lateAllocation")).statusCode())
        .isEqualTo(503);
    assertThat(lateDone.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(allocated).isEmpty();
    assertThat(tombstones).contains("lateAllocation");
  }

  @Test
  void expiredCompensationCredentialRequiresOwnerRetry() throws Exception {
    reserveStatus = 409;
    releaseStatus = 503;
    call("POST", "/api/bookings", token, payload("expired"));
    var b = bookings.findAll().get(0);
    service.credentials.put(b.id, new BookingService.Credential(token, java.time.Instant.EPOCH));
    service.tx.executeWithoutResult(
        tx -> {
          var row = bookings.lockById(b.id).orElseThrow();
          row.nextAttempt = java.time.Instant.EPOCH;
          bookings.save(row);
        });
    int previous = releases.get();
    service.retry();
    var current = bookings.findById(b.id).orElseThrow();
    assertThat(current.compensationPending).isTrue();
    assertThat(current.autoRetryEnabled).isFalse();
    assertThat(releases.get()).isEqualTo(previous);
  }

  @AfterEach
  void awaitInFlightHandlers() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (inFlight.get() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
    assertThat(inFlight.get())
        .as("All HTTP stub handlers must finish before the next test")
        .isZero();
  }

  @AfterAll
  static void stop() {
    stub.stop(0);
    stubExecutor.shutdownNow();
  }
}
