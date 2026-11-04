package dev.hotel.catalog;

import dev.hotel.common.ApiException;
import jakarta.persistence.criteria.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CatalogService {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(CatalogService.class);
  private final Hotels hotels;
  private final Rooms rooms;
  private final Reservations reservations;
  private final AllocationLocks locks;

  public CatalogService(Hotels h, Rooms r, Reservations x, AllocationLocks l) {
    hotels = h;
    rooms = r;
    reservations = x;
    locks = l;
  }

  public record HotelInput(
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Size(max = 200) String city,
      @NotBlank @Size(max = 500) String address,
      @Size(max = 2000) String description) {}

  public record RoomInput(
      @NotNull @Positive Long hotelId,
      @NotBlank @Size(max = 50) String number,
      @Size(max = 2000) String description,
      @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal price,
      @Min(1) @Max(100) int capacity,
      boolean available) {}

  public record ReserveInput(
      @NotBlank @Pattern(regexp = "[a-zA-Z0-9_-]{1,64}") String requestId,
      Long roomId,
      boolean autoSelect,
      Long hotelId,
      @NotNull LocalDate startDate,
      @NotNull LocalDate endDate) {}

  public record ReleaseInput(@NotBlank @Pattern(regexp = "[a-zA-Z0-9_-]{1,64}") String requestId) {}

  public record ReservedRoom(Long roomId) {}

  static ApiException bad(String m) {
    return new ApiException(HttpStatus.BAD_REQUEST, m);
  }

  static ApiException conflict(String m) {
    return new ApiException(HttpStatus.CONFLICT, m);
  }

  public Hotel hotel(Long id) {
    return hotels
        .findById(id)
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Hotel not found"));
  }

  public Room room(Long id) {
    return rooms
        .findById(id)
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Room not found"));
  }

  @Transactional(readOnly = true)
  public Page<Hotel> listHotels(Pageable page) {
    validatePage(page, Set.of("id", "name", "city", "address"));
    return hotels.findAll(page);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> statistics() {
    var all = rooms.findAll();
    return Map.of(
        "hotels",
        hotels.count(),
        "rooms",
        all.size(),
        "operationalRooms",
        all.stream().filter(r -> r.available).count(),
        "timesBooked",
        all.stream().mapToLong(r -> r.timesBooked).sum(),
        "roomStatistics",
        all);
  }

  public Hotel saveHotel(Long id, HotelInput in) {
    locks.acquire();
    Hotel h = id == null ? new Hotel() : hotel(id);
    h.name = in.name();
    h.city = in.city();
    h.address = in.address();
    h.description = in.description();
    return hotels.save(h);
  }

  public Room saveRoom(Long id, RoomInput in) {
    locks.acquire();
    hotel(in.hotelId());
    Room r = id == null ? new Room() : room(id);
    if (id != null
        && !r.hotelId.equals(in.hotelId())
        && reservations.existsByRoomIdAndReleasedFalse(id))
      throw conflict("Cannot move a reserved room");
    r.hotelId = in.hotelId();
    r.number = in.number();
    r.description = in.description();
    r.price = in.price();
    r.capacity = in.capacity();
    r.available = in.available();
    return rooms.saveAndFlush(r);
  }

  public void deleteRoom(Long id) {
    locks.acquire();
    Room r = room(id);
    if (reservations.existsByRoomIdAndReleasedFalse(id)) throw conflict("Room has reservations");
    rooms.delete(r);
  }

  public void deleteHotel(Long id) {
    locks.acquire();
    Hotel h = hotel(id);
    if (!rooms.findByHotelId(id).isEmpty()) throw conflict("Delete hotel rooms first");
    hotels.delete(h);
  }

  static void dates(LocalDate start, LocalDate end) {
    if (start == null || end == null || !start.isBefore(end))
      throw bad("startDate must be before endDate");
  }

  public ReservedRoom reserve(String owner, ReserveInput in) {
    dates(in.startDate(), in.endDate());
    if (!in.autoSelect() && (in.roomId() == null || in.roomId() < 1))
      throw bad("roomId is required unless autoSelect is true");
    if (in.hotelId() != null && in.hotelId() < 1) throw bad("hotelId must be positive");
    // Explicitly exclude ignored roomId from auto-selection's semantic fingerprint.
    String fingerprint =
        in.autoSelect()
            + ":"
            + (in.autoSelect() ? null : in.roomId())
            + ":"
            + in.hotelId()
            + ":"
            + in.startDate()
            + ":"
            + in.endDate();
    locks.acquire();
    var prior = reservations.findByOwnerIdAndRequestId(owner, in.requestId());
    if (prior.isPresent()) {
      var r = prior.get();
      if (r.released) throw conflict("Reservation request was released");
      if (!fingerprint.equals(r.fingerprint))
        throw conflict("requestId was used with a different payload");
      return new ReservedRoom(r.roomId);
    }
    Room selected;
    if (in.autoSelect()) {
      if (in.hotelId() != null) hotel(in.hotelId());
      selected =
          rooms.findAll(Sort.by("timesBooked").ascending().and(Sort.by("id").ascending())).stream()
              .filter(r -> r.available && (in.hotelId() == null || r.hotelId.equals(in.hotelId())))
              .filter(r -> reservations.overlaps(r.id, in.startDate(), in.endDate()) == 0)
              .findFirst()
              .orElseThrow(() -> conflict("No room available for requested dates"));
    } else {
      selected = room(in.roomId());
      if (in.hotelId() != null && !selected.hotelId.equals(in.hotelId()))
        throw bad("roomId does not belong to hotelId");
      if (!selected.available
          || reservations.overlaps(selected.id, in.startDate(), in.endDate()) > 0)
        throw conflict("Room unavailable for requested dates");
    }
    Reservation r = new Reservation();
    r.ownerId = owner;
    r.requestId = in.requestId();
    r.roomId = selected.id;
    r.fingerprint = fingerprint;
    r.startDate = in.startDate();
    r.endDate = in.endDate();
    reservations.save(r);
    selected.timesBooked++;
    rooms.save(selected);
    log.info("event=room_reserved roomId={} requestId={}", selected.id, in.requestId());
    return new ReservedRoom(selected.id);
  }

  public void release(String owner, String requestId) {
    locks.acquire();
    var r =
        reservations
            .findByOwnerIdAndRequestId(owner, requestId)
            .orElseGet(
                () -> {
                  var x = new Reservation();
                  x.ownerId = owner;
                  x.requestId = requestId;
                  return x;
                });
    r.released = true;
    reservations.save(r);
    log.info("event=room_released roomId={} requestId={}", r.roomId, requestId);
  }

  @Transactional(readOnly = true)
  public Page<Room> search(
      Long hotelId,
      String city,
      BigDecimal minPrice,
      BigDecimal maxPrice,
      Integer minCapacity,
      Boolean available,
      LocalDate start,
      LocalDate end,
      Pageable pageable) {
    if (start != null || end != null) dates(start, end);
    if (minPrice != null && minPrice.signum() < 0
        || maxPrice != null && maxPrice.signum() < 0
        || minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0)
      throw bad("Invalid price range");
    if (minCapacity != null && minCapacity < 1) throw bad("minCapacity must be positive");
    validatePage(
        pageable,
        Set.of("id", "hotelId", "number", "price", "capacity", "timesBooked", "available"));
    Specification<Room> spec =
        (root, q, cb) -> {
          List<Predicate> p = new ArrayList<>();
          if (hotelId != null) p.add(cb.equal(root.get("hotelId"), hotelId));
          if (city != null && !city.isBlank()) {
            Subquery<Long> h = q.subquery(Long.class);
            Root<Hotel> hotel = h.from(Hotel.class);
            h.select(hotel.get("id"))
                .where(cb.equal(cb.lower(hotel.get("city")), city.toLowerCase(Locale.ROOT)));
            p.add(root.get("hotelId").in(h));
          }
          if (minPrice != null) p.add(cb.ge(root.get("price"), minPrice));
          if (maxPrice != null) p.add(cb.le(root.get("price"), maxPrice));
          if (minCapacity != null) p.add(cb.ge(root.get("capacity"), minCapacity));
          if (available != null) p.add(cb.equal(root.get("available"), available));
          if (start != null) {
            p.add(cb.isTrue(root.get("available")));
            Subquery<Long> s = q.subquery(Long.class);
            Root<Reservation> r = s.from(Reservation.class);
            s.select(r.get("roomId"))
                .where(
                    cb.isFalse(r.get("released")),
                    cb.lessThan(r.get("startDate"), end),
                    cb.greaterThan(r.get("endDate"), start));
            p.add(cb.not(root.get("id").in(s)));
          }
          return cb.and(p.toArray(Predicate[]::new));
        };
    return rooms.findAll(spec, pageable);
  }

  static void validatePage(Pageable p, Set<String> fields) {
    if (p.getPageSize() > 100) throw bad("Page size must be at most 100");
    for (Sort.Order o : p.getSort())
      if (!fields.contains(o.getProperty()))
        throw bad("Unsupported sort field: " + o.getProperty());
  }
}
