package dev.hotel.catalog;

import static dev.hotel.catalog.CatalogService.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class CatalogController {
  final CatalogService service;

  public CatalogController(CatalogService s) {
    service = s;
  }

  @GetMapping("/api/hotels")
  public Page<Hotel> hotels(@PageableDefault(size = 20, sort = "id") Pageable page) {
    validatePage(page, Set.of("id", "name", "city", "address"));
    return service.listHotels(page);
  }

  @GetMapping("/api/hotels/{id}")
  public Hotel hotel(@PathVariable Long id) {
    return service.hotel(id);
  }

  @PostMapping("/api/hotels")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ADMIN')")
  public Hotel createHotel(@Valid @RequestBody HotelInput input) {
    return service.saveHotel(null, input);
  }

  @PutMapping("/api/hotels/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public Hotel updateHotel(@PathVariable Long id, @Valid @RequestBody HotelInput input) {
    return service.saveHotel(id, input);
  }

  @DeleteMapping("/api/hotels/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('ADMIN')")
  public void deleteHotel(@PathVariable Long id) {
    service.deleteHotel(id);
  }

  @GetMapping({"/api/rooms", "/api/rooms/search"})
  public Page<Room> rooms(
      @RequestParam(required = false) Long hotelId,
      @RequestParam(required = false) String city,
      @RequestParam(required = false) BigDecimal minPrice,
      @RequestParam(required = false) BigDecimal maxPrice,
      @RequestParam(required = false) Integer minCapacity,
      @RequestParam(defaultValue = "true") Boolean available,
      @RequestParam(required = false) LocalDate startDate,
      @RequestParam(required = false) LocalDate endDate,
      @PageableDefault(size = 20, sort = "id") Pageable page) {
    return service.search(
        hotelId, city, minPrice, maxPrice, minCapacity, available, startDate, endDate, page);
  }

  @GetMapping("/api/rooms/recommend")
  public Page<Room> recommend(
      @RequestParam(required = false) Long hotelId,
      @RequestParam(required = false) String city,
      @RequestParam(required = false) BigDecimal minPrice,
      @RequestParam(required = false) BigDecimal maxPrice,
      @RequestParam(required = false) Integer minCapacity,
      @RequestParam(required = false) LocalDate startDate,
      @RequestParam(required = false) LocalDate endDate,
      @PageableDefault(
              size = 20,
              sort = {"timesBooked", "id"})
          Pageable page) {
    return service.search(
        hotelId, city, minPrice, maxPrice, minCapacity, true, startDate, endDate, page);
  }

  @GetMapping("/api/rooms/{id}")
  public Room room(@PathVariable Long id) {
    return service.room(id);
  }

  @PostMapping("/api/rooms")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ADMIN')")
  public Room createRoom(@Valid @RequestBody RoomInput input) {
    return service.saveRoom(null, input);
  }

  @PutMapping("/api/rooms/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public Room updateRoom(@PathVariable Long id, @Valid @RequestBody RoomInput input) {
    return service.saveRoom(id, input);
  }

  @DeleteMapping("/api/rooms/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('ADMIN')")
  public void deleteRoom(@PathVariable Long id) {
    service.deleteRoom(id);
  }

  @GetMapping("/api/hotels/statistics")
  @PreAuthorize("hasRole('ADMIN')")
  public Map<String, Object> statistics() {
    return service.statistics();
  }

  @PostMapping("/internal/rooms/reserve")
  public ReservedRoom reserve(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReserveInput input) {
    return service.reserve(jwt.getSubject(), input);
  }

  @PostMapping("/internal/rooms/release")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void release(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReleaseInput input) {
    service.release(jwt.getSubject(), input.requestId());
  }
}
