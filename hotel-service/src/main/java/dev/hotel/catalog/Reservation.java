package dev.hotel.catalog;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(
    name = "room_reservations",
    indexes =
        @Index(
            name = "idx_reservation_interval",
            columnList = "room_id,start_date,end_date,released"),
    uniqueConstraints = @UniqueConstraint(columnNames = {"ownerId", "requestId"}))
class Reservation {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  @Column(nullable = false)
  String ownerId;

  @Column(nullable = false)
  String requestId;

  Long roomId;

  @Column(length = 1024)
  String fingerprint;

  LocalDate startDate;
  LocalDate endDate;
  boolean released;

  public Reservation() {}
}
