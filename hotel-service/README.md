# Hotel service

Spring MVC/JPA/H2 application, port 8082 (loopback by default), registered as `hotel-service`.
Set `EUREKA_ENABLED=false` to run independently. Share `JWT_SECRET` with booking-service and
gateway. The built-in secret is public tutorial data, never a production credential.

## Catalog API

All catalog requests require a bearer JWT. ADMIN is required for POST/PUT/DELETE and statistics;
method authorization enforces this even independently of the gateway.

* `GET /api/hotels?page=0&size=20&sort=name,asc`
* `GET /api/hotels/{id}`
* `POST /api/hotels`, `PUT /api/hotels/{id}`:
  `{"name":"Harbor House","city":"Lisbon","address":"12 Harbor Street","description":"Waterfront"}`
* `DELETE /api/hotels/{id}` (409 while rooms exist)
* `GET /api/rooms` or `/api/rooms/search`: optional `hotelId`, `city` (case insensitive exact),
  `minPrice`, `maxPrice`, `minCapacity`, `available`, `startDate`, `endDate`.
  `available` defaults to true. Both dates must be supplied together. Supports page/size (max100) and whitelisted sort fields
  `id,hotelId,number,price,capacity,timesBooked,available`.
* `GET /api/rooms/recommend`: same search filters, operational rooms only; default sort `timesBooked,id`.
* `GET /api/rooms/{id}`
* `POST /api/rooms`, `PUT /api/rooms/{id}`:
  `{"hotelId":1,"number":"101","description":"Double room","price":100.00,"capacity":2,"available":true}`
* `DELETE /api/rooms/{id}` (409 while unreleased reservations exist)
* `GET /api/hotels/statistics`: hotel/room counts, operational room count, historical total
  `timesBooked`, and per-room statistics.

`available` is an operational admin flag, **not** a cached date availability value. Date
search additionally excludes unreleased overlapping intervals. Adjacent stays are allowed:
all dates use `[startDate,endDate)`. `timesBooked` is a monotonic historical allocation count;
canceling does not decrement it. A replay never increments it.

## Internal allocation contract

These endpoints are deliberately not gateway-routed, but still authenticate the original
user JWT. They are meant for booking-service, not public frontend access.

`POST /internal/rooms/reserve`:
```
{"requestId":"opaque-key","roomId":1,"autoSelect":false,"hotelId":1,
 "startDate":"2030-06-10","endDate":"2030-06-15"}
```
requestId accepts 1–64 ASCII letters, digits, hyphens, or underscores (UUIDs work).
Returns `{"roomId":1}`. For autoSelect, roomId is ignored, hotelId optionally restricts
selection, and the eligible room with lowest timesBooked then lowest id wins. No eligible
room or an overlapping explicit room returns409. Bad date/selection parameters return400.

`POST /internal/rooms/release` with `{"requestId":"opaque-key"}` returns204, including
repeats and unknown keys. Unknown keys create tombstones to reject delayed reservations.

A singleton persisted lock row is acquired with `PESSIMISTIC_WRITE` inside the transaction,
serializing allocation/selection/release and catalog mutations. This is deliberately simple
and safe for the tutorial; a production system can partition locks by hotel with a consistent
locking protocol. The guarantee is shared-database scoped: distinct databases cannot coordinate.
The reservation ledger has a database unique constraint on `(ownerId,requestId)`. Replays
must match the semantic payload; mismatches or released keys return409. Other users' keys
cannot release or replay the owner's allocation. Release tombstones are intentionally not pruned.

H2 is in-memory in the default profile: it demonstrates transactions, not restart durability.
Use a persistent datasource and migrations before relying on the ledger across restarts.

OpenAPI: `/v3/api-docs`, Swagger UI: `/swagger-ui.html`.
Seed: two hotels (Lisbon, Porto), six operational rooms. Integration tests use real H2,
MockMvc, cryptographically signed JWTs, and synchronized multi-thread allocation races.
