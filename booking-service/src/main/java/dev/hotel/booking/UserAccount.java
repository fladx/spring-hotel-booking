package dev.hotel.booking;

import jakarta.persistence.*;

@Entity
@Table(name = "user_accounts", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
class UserAccount {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  @Column(nullable = false, length = 80)
  String username;

  @Column(nullable = false)
  String passwordHash;

  @Column(nullable = false)
  String role;

  protected UserAccount() {}

  UserAccount(String name, String hash, String role) {
    this.username = name;
    this.passwordHash = hash;
    this.role = role;
  }
}
