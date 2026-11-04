package dev.hotel.catalog;

import static dev.hotel.catalog.CatalogService.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hotel.common.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "eureka.client.enabled=false",
      "spring.datasource.url=jdbc:h2:mem:hoteltests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000"
    })
@AutoConfigureMockMvc
class HotelIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired CatalogService service;
  @Autowired Hotels hotels;
  @Autowired Rooms rooms;
  @Autowired Reservations reservations;

  @Value("${app.jwt.secret}")
  String secret;

  Long hotelId, roomId, secondId;
  final LocalDate start = LocalDate.of(2030, 6, 10), end = LocalDate.of(2030, 6, 15);

  @BeforeEach
  void setup() {
    reservations.deleteAll();
    rooms.deleteAll();
    hotels.deleteAll();
    hotelId = service.saveHotel(null, new HotelInput("Hotel", "Lisbon", "Street", null)).id;
    roomId =
        service.saveRoom(
                null, new RoomInput(hotelId, "101", null, new BigDecimal("100.00"), 2, true))
            .id;
    secondId =
        service.saveRoom(
                null, new RoomInput(hotelId, "102", null, new BigDecimal("120.00"), 4, true))
            .id;
  }

  String token(String subject, String role) {
    return token(subject, role, Instant.now().plusSeconds(3600));
  }

  String token(String subject, String role, Instant expiration) {
    return new JwtSupport(secret)
        .encoder()
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                    .issuer("hotel-booking")
                    .subject(subject)
                    .issuedAt(Instant.now().minusSeconds(1000))
                    .expiresAt(expiration)
                    .claim("roles", List.of(role))
                    .build()))
        .getTokenValue();
  }

  ReserveInput req(String id, Long room) {
    return new ReserveInput(id, room, false, hotelId, start, end);
  }

  @Test
  void authenticationAndAdminSecurity() throws Exception {
    mvc.perform(get("/api/hotels")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/hotels").header("Authorization", "Bearer broken"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/hotels")
                .header(
                    "Authorization",
                    "Bearer " + token("a", "USER", Instant.now().minusSeconds(120))))
        .andExpect(status().isUnauthorized());
    String body = mapper.writeValueAsString(new HotelInput("New", "Porto", "Street", null));
    mvc.perform(
            post("/api/hotels")
                .header("Authorization", "Bearer " + token("a", "USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/hotels")
                .header("Authorization", "Bearer " + token("admin", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated());
    mvc.perform(
            get("/api/hotels/statistics").header("Authorization", "Bearer " + token("a", "USER")))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/hotels/statistics")
                .header("Authorization", "Bearer " + token("admin", "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms").value(2));
  }

  @Test
  void atomicConcurrentReservationOnlyOneWins() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(8);
    CountDownLatch gate = new CountDownLatch(1);
    try {
      List<Future<Integer>> calls = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        int n = i;
        calls.add(
            pool.submit(
                () -> {
                  gate.await();
                  return mvc.perform(
                          post("/internal/rooms/reserve")
                              .header("Authorization", "Bearer " + token("user" + n, "USER"))
                              .contentType(MediaType.APPLICATION_JSON)
                              .content(mapper.writeValueAsString(req("parallel-" + n, roomId))))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                }));
      }
      gate.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (var c : calls) statuses.add(c.get(30, TimeUnit.SECONDS));
      assertThat(statuses).containsOnly(200, 409);
      assertThat(statuses.stream().filter(x -> x == 200).count()).isEqualTo(1);
      assertThat(service.room(roomId).timesBooked).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentIdempotentRetriesAllocateOnce() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(6);
    CountDownLatch gate = new CountDownLatch(1);
    try {
      List<Future<Long>> calls = new ArrayList<>();
      for (int i = 0; i < 6; i++)
        calls.add(
            pool.submit(
                () -> {
                  gate.await();
                  return service.reserve("same-user", req("same-key", roomId)).roomId();
                }));
      gate.countDown();
      for (var c : calls) assertThat(c.get(30, TimeUnit.SECONDS)).isEqualTo(roomId);
      assertThat(service.room(roomId).timesBooked).isEqualTo(1);
      assertThat(reservations.count()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentAutoSelectionDoesNotAllocateSameRoom() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(4);
    CountDownLatch gate = new CountDownLatch(1);
    try {
      List<Future<Long>> calls = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        int n = i;
        calls.add(
            pool.submit(
                () -> {
                  gate.await();
                  try {
                    return service
                        .reserve(
                            "auto-user" + n,
                            new ReserveInput("auto" + n, roomId, true, hotelId, start, end))
                        .roomId();
                  } catch (ApiException e) {
                    assertThat(e.status().value()).isEqualTo(409);
                    return -1L;
                  }
                }));
      }
      gate.countDown();
      List<Long> ids = new ArrayList<>();
      for (var c : calls) ids.add(c.get(30, TimeUnit.SECONDS));
      assertThat(ids).containsExactlyInAnyOrder(roomId, secondId, -1L, -1L);
      assertThat(service.room(roomId).timesBooked).isEqualTo(1);
      assertThat(service.room(secondId).timesBooked).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void idempotencyPayloadMismatchAndOwnerIsolation() {
    assertThat(service.reserve("a", req("same", roomId)).roomId()).isEqualTo(roomId);
    assertThat(service.reserve("a", req("same", roomId)).roomId()).isEqualTo(roomId);
    assertThat(service.room(roomId).timesBooked).isEqualTo(1);
    assertThatThrownBy(() -> service.reserve("a", req("same", secondId)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("different payload");
    service.release("b", "same"); // must not release a's hold
    assertThatThrownBy(() -> service.reserve("b", req("other", roomId)))
        .isInstanceOf(ApiException.class);
    assertThat(service.reserve("b", req("independent", secondId)).roomId()).isEqualTo(secondId);
  }

  @Test
  void tombstoneBlocksLateRequestAndReleaseIsIdempotent() {
    service.release("a", "late");
    service.release("a", "late");
    assertThatThrownBy(() -> service.reserve("a", req("late", roomId)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("released");
    service.reserve("a", req("live", roomId));
    service.release("a", "live");
    service.release("a", "live");
    assertThat(service.reserve("b", req("next", roomId)).roomId()).isEqualTo(roomId);
    assertThat(service.room(roomId).timesBooked).isEqualTo(2);
  }

  @Test
  void halfOpenIntervalsAndOperationalFlag() {
    service.reserve("a", req("first", roomId));
    assertThat(
            service
                .reserve(
                    "b", new ReserveInput("adjacent", roomId, false, hotelId, end, end.plusDays(2)))
                .roomId())
        .isEqualTo(roomId);
    assertThatThrownBy(
            () ->
                service.reserve(
                    "b",
                    new ReserveInput(
                        "overlap", roomId, false, hotelId, start.minusDays(1), start.plusDays(1))))
        .isInstanceOf(ApiException.class);
    service.saveRoom(secondId, new RoomInput(hotelId, "102", null, BigDecimal.TEN, 4, false));
    assertThatThrownBy(() -> service.reserve("a", req("disabled", secondId)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void autoSelectIgnoresRoomIdAndUsesLeastBookedThenId() {
    var a = service.reserve("a", new ReserveInput("auto", 999999L, true, hotelId, start, end));
    assertThat(a.roomId()).isEqualTo(roomId);
    assertThat(service.reserve("a", new ReserveInput("auto", -99L, true, hotelId, start, end)))
        .isEqualTo(a);
    service.release("a", "auto");
    assertThat(
            service
                .reserve("a", new ReserveInput("next", roomId, true, hotelId, start, end))
                .roomId())
        .isEqualTo(secondId);
  }

  @Test
  void searchHasDatePriceCityCapacitySortAndPagination() throws Exception {
    service.reserve("a", req("held", roomId));
    mvc.perform(
            get("/api/rooms")
                .header("Authorization", "Bearer " + token("a", "USER"))
                .param("startDate", start.toString())
                .param("endDate", end.toString())
                .param("city", "lisbon")
                .param("minPrice", "110")
                .param("minCapacity", "3")
                .param("sort", "price,desc")
                .param("size", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(secondId));
  }

  @Test
  void negativeValidationAndTrace() throws Exception {
    mvc.perform(
            post("/internal/rooms/reserve")
                .header("Authorization", "Bearer " + token("a", "USER"))
                .header("X-Request-Id", "test-trace")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        new ReserveInput("bad", roomId, false, hotelId, end, start))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.traceId").value("test-trace"));
    mvc.perform(
            get("/api/rooms")
                .header("Authorization", "Bearer " + token("a", "USER"))
                .param("sort", "password,asc"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/internal/rooms/reserve")
                .header("Authorization", "Bearer " + token("a", "USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void maximumHotelAndRoomTextLengthsArePersisted() throws Exception {
    String admin = "Bearer " + token("admin", "ADMIN");
    String address = "a".repeat(500);
    String description = "d".repeat(2000);
    var createdHotel =
        mvc.perform(
                post("/api/hotels")
                    .header("Authorization", admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsString(
                            new HotelInput("Long text", "Lisbon", address, description))))
            .andExpect(status().isCreated())
            .andReturn();
    long createdHotelId =
        mapper.readTree(createdHotel.getResponse().getContentAsString()).get("id").asLong();
    Hotel savedHotel = service.hotel(createdHotelId);
    assertThat(savedHotel.address).isEqualTo(address);
    assertThat(savedHotel.description).isEqualTo(description);

    var createdRoom =
        mvc.perform(
                post("/api/rooms")
                    .header("Authorization", admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsString(
                            new RoomInput(
                                createdHotelId, "201", description, BigDecimal.TEN, 2, true))))
            .andExpect(status().isCreated())
            .andReturn();
    long createdRoomId =
        mapper.readTree(createdRoom.getResponse().getContentAsString()).get("id").asLong();
    assertThat(service.room(createdRoomId).description).isEqualTo(description);
  }

  @Test
  void textBeyondDocumentedLimitsIsRejectedBeforePersistence() throws Exception {
    String admin = "Bearer " + token("admin", "ADMIN");
    for (HotelInput input :
        List.of(
            new HotelInput("Hotel", "Lisbon", "a".repeat(501), null),
            new HotelInput("Hotel", "Lisbon", "Street", "d".repeat(2001)))) {
      mvc.perform(
              post("/api/hotels")
                  .header("Authorization", admin)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(mapper.writeValueAsString(input)))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(
            post("/api/rooms")
                .header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        new RoomInput(hotelId, "201", "d".repeat(2001), BigDecimal.TEN, 2, true))))
        .andExpect(status().isBadRequest());
    assertThat(hotels.count()).isEqualTo(1);
    assertThat(rooms.count()).isEqualTo(2);
  }

  @Test
  void duplicateRoomReturnsAccurateConflictMessage() throws Exception {
    mvc.perform(
            post("/api/rooms")
                .header("Authorization", "Bearer " + token("admin", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        new RoomInput(hotelId, "101", null, BigDecimal.TEN, 2, true))))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.message")
                .value("The record conflicts with existing data or database constraints"));
  }

  @Test
  void missingRouteKeepsNotFoundStatusAndTrace() throws Exception {
    mvc.perform(
            get("/api/unknown")
                .header("Authorization", "Bearer " + token("a", "USER"))
                .header("X-Request-Id", "missing-route"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.path").value("/api/unknown"))
        .andExpect(jsonPath("$.traceId").value("missing-route"));
  }

  @Test
  void unsupportedMethodKeepsStatusAndAllowHeader() throws Exception {
    mvc.perform(patch("/api/hotels").header("Authorization", "Bearer " + token("admin", "ADMIN")))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")))
        .andExpect(jsonPath("$.status").value(405));
  }

  @Test
  void unsupportedMediaTypeKeepsItsStatus() throws Exception {
    mvc.perform(
            post("/api/hotels")
                .header("Authorization", "Bearer " + token("admin", "ADMIN"))
                .contentType(MediaType.TEXT_PLAIN)
                .content("hotel"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(
            header().string("Accept", org.hamcrest.Matchers.containsString("application/json")))
        .andExpect(jsonPath("$.status").value(415));
  }

  @Test
  void deletionCannotOrphanReservation() {
    service.reserve("a", req("held", roomId));
    assertThatThrownBy(() -> service.deleteRoom(roomId)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.deleteHotel(hotelId)).isInstanceOf(ApiException.class);
    service.release("a", "held");
    service.deleteRoom(roomId);
    assertThat(rooms.existsById(roomId)).isFalse();
  }
}
