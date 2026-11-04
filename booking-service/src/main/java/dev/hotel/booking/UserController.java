package dev.hotel.booking;

import dev.hotel.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/users")
class UserController {
  record View(Long id, String username, String role) {
    static View of(UserAccount u) {
      return new View(u.id, u.username, u.role);
    }
  }

  record Write(
      @NotBlank @Size(min = 3, max = 80) String username,
      @NotBlank @Size(min = 8, max = 72) String password,
      @Pattern(regexp = "USER|ADMIN") @NotNull String role) {}

  final UserRepository users;
  final PasswordEncoder passwords;

  UserController(UserRepository u, PasswordEncoder p) {
    users = u;
    passwords = p;
  }

  @GetMapping
  Page<View> list(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return users.findAll(Pages.of(page, size)).map(View::of);
  }

  @GetMapping("/{id}")
  View get(@PathVariable Long id) {
    return View.of(find(id));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  View create(@Valid @RequestBody Write w) {
    return save(new UserAccount(), w);
  }

  @PutMapping("/{id}")
  View update(@PathVariable Long id, @Valid @RequestBody Write w) {
    return save(find(id), w);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable Long id) {
    try {
      users.delete(find(id));
      users.flush();
    } catch (DataIntegrityViolationException e) {
      throw new ApiException(HttpStatus.CONFLICT, "User has booking history and cannot be deleted");
    }
  }

  UserAccount find(Long id) {
    return users
        .findById(id)
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
  }

  View save(UserAccount u, Write w) {
    AuthController.validatePassword(w.password());
    u.username = w.username().trim().toLowerCase(Locale.ROOT);
    u.passwordHash = passwords.encode(w.password());
    u.role = w.role();
    try {
      return View.of(users.saveAndFlush(u));
    } catch (DataIntegrityViolationException e) {
      throw new ApiException(HttpStatus.CONFLICT, "Username already exists");
    }
  }
}

class Pages {
  static Pageable of(int page, int size) {
    if (page < 0 || size < 1 || size > 100)
      throw new ApiException(HttpStatus.BAD_REQUEST, "Page must be >=0 and size between 1 and 100");
    return PageRequest.of(page, size, Sort.by("id").ascending());
  }
}
