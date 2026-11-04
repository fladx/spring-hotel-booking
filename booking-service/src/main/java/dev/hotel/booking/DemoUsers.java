package dev.hotel.booking;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@ConditionalOnProperty(name = "booking.demo-users", havingValue = "true")
class DemoUsers {
  @Bean
  ApplicationRunner seedUsers(UserRepository users, PasswordEncoder encoder) {
    return args -> {
      if (users.findByUsername("admin").isEmpty())
        users.save(new UserAccount("admin", encoder.encode("admin-demo-123"), "ADMIN"));
      if (users.findByUsername("demo").isEmpty())
        users.save(new UserAccount("demo", encoder.encode("user-demo-123"), "USER"));
    };
  }
}
