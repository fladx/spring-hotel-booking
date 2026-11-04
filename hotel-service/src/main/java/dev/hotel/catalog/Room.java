package dev.hotel.catalog;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(
    name = "rooms",
    uniqueConstraints = @UniqueConstraint(columnNames = {"hotel_id", "number"}),
    indexes = @Index(name = "idx_room_hotel", columnList = "hotel_id"))
public class Room {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "hotel_id", nullable = false)
  public Long hotelId;

  @com.fasterxml.jackson.annotation.JsonIgnore
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "hotel_id",
      insertable = false,
      updatable = false,
      foreignKey = @ForeignKey(name = "fk_room_hotel"))
  private Hotel hotel;

  @Column(nullable = false)
  public String number;

  @Column(length = 2000)
  public String description;

  @Column(nullable = false, precision = 12, scale = 2)
  public BigDecimal price;

  public int capacity;
  public boolean available = true;
  public long timesBooked;

  public Room() {}
}
