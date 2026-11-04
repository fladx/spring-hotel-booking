package dev.hotel.catalog;

import java.math.BigDecimal;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class DemoData implements ApplicationRunner {
  final Hotels hotels;
  final Rooms rooms;
  final AllocationLocks locks;

  DemoData(Hotels h, Rooms r, AllocationLocks l) {
    hotels = h;
    rooms = r;
    locks = l;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (!locks.existsById(1L)) locks.saveAndFlush(new AllocationLock(1L));
    locks.acquire();
    if (hotels.count() > 0) return;
    Hotel a = new Hotel();
    a.name = "Harbor House";
    a.city = "Lisbon";
    a.address = "12 Harbor Street";
    a.description = "Demo waterfront hotel";
    hotels.save(a);
    Hotel b = new Hotel();
    b.name = "Garden Inn";
    b.city = "Porto";
    b.address = "8 Garden Avenue";
    b.description = "Demo garden hotel";
    hotels.save(b);
    for (int i = 0; i < 6; i++) {
      Room r = new Room();
      r.hotelId = i < 3 ? a.id : b.id;
      r.number = "" + (101 + i % 3);
      r.description = i % 3 == 2 ? "Family suite" : "Double room";
      r.price = BigDecimal.valueOf(80 + 20 * (i % 3));
      r.capacity = i % 3 == 2 ? 4 : 2;
      r.available = true;
      rooms.save(r);
    }
  }
}
