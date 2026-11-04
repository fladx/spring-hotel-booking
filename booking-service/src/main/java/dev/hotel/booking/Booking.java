package dev.hotel.booking;

import jakarta.persistence.*;
import java.time.*;

@Entity
@Table(
    name = "bookings",
    uniqueConstraints = @UniqueConstraint(columnNames = {"userId", "requestId"}))
class Booking {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  @Version Long version;

  @Column(nullable = false)
  Long userId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "userId",
      insertable = false,
      updatable = false,
      foreignKey = @ForeignKey(name = "fk_booking_user"))
  UserAccount owner;

  @Column(nullable = false, length = 64)
  String requestId;

  Long requestedRoomId;
  Long roomId;
  Long hotelId;
  boolean autoSelect;
  LocalDate startDate;
  LocalDate endDate;

  @Column(nullable = false, updatable = false)
  Instant createdAt = Instant.now();

  @Column(nullable = false)
  String status;

  boolean autoRetryEnabled = true;
  boolean compensationPending;
  int compensationAttempts;
  Instant nextAttempt;

  protected Booking() {}
}
