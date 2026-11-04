package dev.hotel.catalog;

import dev.hotel.common.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({ErrorAdvice.class, TraceFilter.class})
public class HotelApplication {
  public static void main(String[] args) {
    SpringApplication.run(HotelApplication.class, args);
  }
}
