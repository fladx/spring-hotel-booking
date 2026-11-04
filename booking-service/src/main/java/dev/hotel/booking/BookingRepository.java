package dev.hotel.booking;

import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

interface BookingRepository extends JpaRepository<Booking, Long> {
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query("select b from Booking b where b.id = :id")
  Optional<Booking> lockById(@org.springframework.data.repository.query.Param("id") Long id);

  Optional<Booking> findByUserIdAndRequestId(Long userId, String requestId);

  Page<Booking> findByUserId(Long userId, Pageable pageable);

  List<Booking>
      findTop50ByCompensationPendingTrueAndAutoRetryEnabledTrueAndCompensationAttemptsLessThan(
          int limit);

  List<Booking> findByStatus(String status);
}
