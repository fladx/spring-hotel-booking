package dev.hotel.catalog;

import jakarta.persistence.*;

@Entity
@Table(name = "hotels")
public class Hotel {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(nullable = false)
  public String name;

  @Column(nullable = false)
  public String city;

  @Column(nullable = false, length = 500)
  public String address;

  @Column(length = 2000)
  public String description;

  public Hotel() {}
}
