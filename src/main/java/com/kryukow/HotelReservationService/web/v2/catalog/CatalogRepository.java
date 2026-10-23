package com.kryukow.HotelReservationService.web.v2.catalog;

import com.kryukow.HotelReservationService.model.Hotel;
import com.kryukow.HotelReservationService.model.Room;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Database-side eligibility, grouped price minima, stable ordering and bounded photo fetches. */
@Repository
public class CatalogRepository {
    @PersistenceContext private EntityManager em;
    private static final String HOTEL = "h.published = true and h.demo = true and h.slug is not null " +
            "and h.timeZone is not null and h.cityCode is not null and h.description is not null";
    private static final String ROOM = "r.published = true and r.price > 0 and r.price <= 9999999999.99 " +
            "and r.capacity between 1 and 100";
    private static final String OVERLAP = "select b.id from Booking b where b.room.id = r.id " +
            "and b.status = 'CONFIRMED' and b.checkInDate < :checkOut and b.checkOutDate > :checkIn";

    public record CityZone(String code, String name, String zone) {}
    public record HotelMatch(Hotel hotel, BigDecimal minimumPrice, long roomCount) {}
    public record Selection<T>(List<T> items, long total) {}

    public List<CityZone> cityZones(String city) {
        var query = em.createQuery("select distinct h.cityCode, h.city, h.timeZone from Hotel h where " + HOTEL +
                (city == null ? "" : " and h.cityCode = :city") + " order by h.cityCode, h.city, h.timeZone", Object[].class);
        if (city != null) query.setParameter("city", city);
        return query.getResultList().stream().map(row -> new CityZone((String) row[0], (String) row[1], (String) row[2])).toList();
    }

    public Optional<Hotel> hotel(String slug) {
        return em.createQuery("select h from Hotel h where " + HOTEL + " and h.slug = :slug", Hotel.class)
                .setParameter("slug", slug).getResultStream().findFirst();
    }

    public Optional<Room> room(long id) {
        return em.createQuery("select r from Room r join fetch r.hotel h where " + HOTEL + " and " + ROOM + " and r.id = :id", Room.class)
                .setParameter("id", id).getResultStream().findFirst();
    }

    public Selection<HotelMatch> hotels(CatalogQuery input, Collection<String> zones) {
        if (zones.isEmpty()) return new Selection<>(List.of(), 0);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("zones", zones);
        String where = HOTEL + " and " + ROOM + " and h.timeZone in :zones";
        if (input.city() != null) { where += " and h.cityCode = :city"; params.put("city", input.city()); }
        where += roomFilters(input, params);
        if (input.dated()) {
            where += " and not exists (" + OVERLAP + ")";
            dates(input, params);
        }
        String base = " from Room r join r.hotel h where " + where;
        long total = bind(em.createQuery("select count(distinct h.id)" + base, Long.class), params).getSingleResult();
        if (input.offset() >= total || input.offset() > Integer.MAX_VALUE) return new Selection<>(List.of(), total);
        String order = switch (input.sort()) {
            case "priceDesc" -> "min(r.price) desc, h.id asc";
            case "idAsc" -> "h.id asc";
            default -> "min(r.price) asc, h.id asc";
        };
        List<HotelMatch> items = bind(em.createQuery("select h, min(r.price), count(r.id)" + base +
                        " group by h order by " + order, Object[].class), params)
                .setFirstResult((int) input.offset()).setMaxResults(input.size()).getResultList().stream()
                .map(row -> new HotelMatch((Hotel) row[0], (BigDecimal) row[1], ((Number) row[2]).longValue())).toList();
        hotelPhotos(items.stream().map(item -> item.hotel().getId()).toList());
        return new Selection<>(items, total);
    }

    public Selection<Room> rooms(long hotelId, CatalogQuery input) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("hotelId", hotelId);
        String base = " from Room r join r.hotel h where " + HOTEL + " and " + ROOM + " and h.id = :hotelId" + roomFilters(input, params);
        long total = bind(em.createQuery("select count(r.id)" + base, Long.class), params).getSingleResult();
        if (input.offset() >= total || input.offset() > Integer.MAX_VALUE) return new Selection<>(List.of(), total);
        String order = switch (input.sort()) {
            case "priceDesc" -> "r.price desc, r.id asc";
            case "idAsc" -> "r.id asc";
            default -> "r.price asc, r.id asc";
        };
        List<Room> rooms = bind(em.createQuery("select r" + base.replace("join r.hotel h", "join fetch r.hotel h") +
                        " order by " + order, Room.class), params)
                .setFirstResult((int) input.offset()).setMaxResults(input.size()).getResultList();
        roomPhotos(rooms.stream().map(Room::getId).toList());
        return new Selection<>(rooms, total);
    }

    /** Occupancy disclosure is a boolean per requested room, never a date/booking DTO. */
    public List<Long> occupied(Collection<Long> ids, CatalogQuery input) {
        if (ids.isEmpty() || !input.dated()) return List.of();
        return em.createQuery("select distinct b.room.id from Booking b where b.room.id in :ids and b.status = 'CONFIRMED' " +
                        "and b.checkInDate < :checkOut and b.checkOutDate > :checkIn", Long.class)
                .setParameter("ids", ids).setParameter("checkIn", input.checkIn()).setParameter("checkOut", input.checkOut()).getResultList();
    }

    public void hotelPhotos(Collection<Long> ids) {
        if (!ids.isEmpty()) em.createQuery("select distinct h from Hotel h left join fetch h.photos where h.id in :ids", Hotel.class)
                .setParameter("ids", ids).getResultList();
    }

    public void roomPhotos(Collection<Long> ids) {
        if (!ids.isEmpty()) em.createQuery("select distinct r from Room r left join fetch r.photos where r.id in :ids", Room.class)
                .setParameter("ids", ids).getResultList();
    }

    private String roomFilters(CatalogQuery input, Map<String, Object> params) {
        String where = " and r.capacity >= :guests";
        params.put("guests", input.guests());
        if (input.priceMin() != null) { where += " and r.price >= :priceMin"; params.put("priceMin", input.priceMin()); }
        if (input.priceMax() != null) { where += " and r.price <= :priceMax"; params.put("priceMax", input.priceMax()); }
        return where;
    }

    private void dates(CatalogQuery input, Map<String, Object> params) {
        params.put("checkIn", input.checkIn()); params.put("checkOut", input.checkOut());
    }

    private <T> TypedQuery<T> bind(TypedQuery<T> query, Map<String, Object> params) {
        params.forEach(query::setParameter);
        return query;
    }
}
