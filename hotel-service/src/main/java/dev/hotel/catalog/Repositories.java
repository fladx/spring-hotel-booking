package dev.hotel.catalog;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.jpa.repository.Query;

interface Hotels extends JpaRepository<Hotel, Long> {}

interface Rooms extends JpaRepository<Room, Long>, JpaSpecificationExecutor<Room> {
  List<Room> findByHotelId(Long id);
}

interface Reservations extends JpaRepository<Reservation, Long> {
  Optional<Reservation> findByOwnerIdAndRequestId(String ownerId, String requestId);

  @Query(
      "select count(r) from Reservation r where r.roomId=:roomId and r.released=false and r.startDate<:endDate and r.endDate>:startDate")
  long overlaps(Long roomId, LocalDate startDate, LocalDate endDate);

  boolean existsByRoomIdAndReleasedFalse(Long roomId);
}

@Entity
@Table(name = "allocation_lock")
class AllocationLock {
  @Id Long id;

  public AllocationLock() {}

  AllocationLock(Long id) {
    this.id = id;
  }
}

interface AllocationLocks extends JpaRepository<AllocationLock, Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select l from AllocationLock l where l.id=1")
  AllocationLock acquire();
}
