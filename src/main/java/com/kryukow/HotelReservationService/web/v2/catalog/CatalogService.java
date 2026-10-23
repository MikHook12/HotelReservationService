package com.kryukow.HotelReservationService.web.v2.catalog;

import com.kryukow.HotelReservationService.model.Hotel;
import com.kryukow.HotelReservationService.model.Room;
import com.kryukow.HotelReservationService.web.v2.catalog.model.CatalogPhoto;
import com.kryukow.HotelReservationService.web.v2.common.V2ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static com.kryukow.HotelReservationService.web.v2.catalog.CatalogDtos.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CatalogService {
    private final CatalogRepository repository;
    private final Clock clock;
    private static final Pattern ASSET = Pattern.compile("/media/[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*\\.(jpg|jpeg|png|webp|avif)");

    public CityList cities() {
        Instant at = clock.instant();
        Map<String, List<CatalogRepository.CityZone>> grouped = new LinkedHashMap<>();
        configuredZones(null).forEach(zone -> grouped.computeIfAbsent(zone.code(), key -> new ArrayList<>()).add(zone));
        List<City> items = grouped.entrySet().stream().map(entry -> {
            List<String> zones = entry.getValue().stream().map(CatalogRepository.CityZone::zone).distinct().sorted().toList();
            return new City(entry.getKey(), entry.getValue().get(0).name(), zones, zones.stream().map(zone -> limits(zone, at)).toList());
        }).toList();
        return new CityList(items, at);
    }

    public HotelPage hotels(CatalogQuery input) {
        Instant at = clock.instant();
        List<CatalogRepository.CityZone> candidates = configuredZones(input.city());
        if (input.city() != null && candidates.isEmpty())
            throw CatalogQuery.invalid("city", "OUT_OF_RANGE", "Город отсутствует в каталоге.");
        Set<String> zones = new TreeSet<>();
        Set<String> excluded = new TreeSet<>();
        candidates.forEach(candidate -> {
            if (!input.dated() || localDatesValid(input, limits(candidate.zone(), at))) zones.add(candidate.zone());
            else excluded.add(candidate.zone());
        });
        if (input.dated() && !candidates.isEmpty() && zones.isEmpty())
            throw dateError(candidates.stream().map(CatalogRepository.CityZone::zone).distinct().sorted().map(zone -> limits(zone, at)).toList());
        var selection = repository.hotels(input, zones);
        var items = selection.items().stream().map(match -> {
            Hotel hotel = match.hotel();
            return new HotelCard(id(hotel.getId()), hotel.getSlug(), hotel.getName(), hotel.getCityCode(), hotel.getCity(),
                    hotel.getTimeZone(), true, photos(hotel.getPhotos()), pricing(match.minimumPrice(), input.nights()),
                    input.dated(), match.roomCount());
        }).toList();
        List<SearchWarning> warnings = excluded.isEmpty() ? List.of() :
                List.of(new SearchWarning("DATE_OUT_OF_RANGE_FOR_SOME_HOTELS", List.copyOf(excluded)));
        return new HotelPage(items, input.page(), input.size(), selection.total(), pages(selection.total(), input.size()), input.sort(), at, warnings);
    }

    public HotelDetail hotel(String slug) {
        Instant at = clock.instant();
        Hotel hotel = findHotel(slug);
        repository.hotelPhotos(List.of(hotel.getId()));
        DemoLocation location = hotel.getLatitude() == null || hotel.getLongitude() == null ? null :
                new DemoLocation(hotel.getLatitude(), hotel.getLongitude(), true);
        return new HotelDetail(id(hotel.getId()), hotel.getSlug(), hotel.getName(), hotel.getCityCode(), hotel.getCity(),
                hotel.getTimeZone(), true, photos(hotel.getPhotos()), hotel.getDescription(), hotel.getAddress(), location, limits(hotel.getTimeZone(), at));
    }

    public RoomPage rooms(String slug, CatalogQuery input) {
        Instant at = clock.instant();
        Hotel hotel = findHotel(slug);
        DateLimits limits = limits(hotel.getTimeZone(), at);
        requireLocalDates(input, limits);
        var selection = repository.rooms(hotel.getId(), input);
        Set<Long> occupied = Set.copyOf(repository.occupied(selection.items().stream().map(Room::getId).toList(), input));
        var items = selection.items().stream().map(room -> roomView(room, input, limits, occupied.contains(room.getId()))).toList();
        return new RoomPage(items, input.page(), input.size(), selection.total(), pages(selection.total(), input.size()), input.sort(), at);
    }

    public RoomView room(long roomId, CatalogQuery input) {
        Instant at = clock.instant();
        Room room = repository.room(roomId).filter(value -> validZone(value.getHotel().getTimeZone())).orElseThrow(CatalogService::notFound);
        DateLimits limits = limits(room.getHotel().getTimeZone(), at);
        requireLocalDates(input, limits);
        repository.roomPhotos(List.of(roomId));
        return roomView(room, input, limits, !repository.occupied(List.of(roomId), input).isEmpty());
    }

    private RoomView roomView(Room room, CatalogQuery input, DateLimits limits, boolean occupied) {
        Hotel hotel = room.getHotel();
        String reason = room.getCapacity() < input.guests() ? "CAPACITY_EXCEEDED" :
                input.dated() && occupied ? "ROOM_UNAVAILABLE" : null;
        String availability = !input.dated() ? "NOT_EVALUATED" : reason != null ? "UNAVAILABLE" : "AVAILABLE";
        return new RoomView(id(room.getId()), id(hotel.getId()), hotel.getSlug(), room.getName(), room.getDescription(),
                room.getCapacity(), photos(room.getPhotos()), pricing(room.getPrice(), input.nights()), availability, reason, limits);
    }

    private Hotel findHotel(String slug) {
        if (slug == null || slug.length() > 100 || !slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || slug.matches("[0-9]+")) throw notFound();
        return repository.hotel(slug).filter(value -> validZone(value.getTimeZone())).orElseThrow(CatalogService::notFound);
    }

    private List<CatalogRepository.CityZone> configuredZones(String city) {
        return repository.cityZones(city).stream().filter(value -> validZone(value.zone())).toList();
    }

    static boolean validZone(String zone) { return zone != null && ZoneId.getAvailableZoneIds().contains(zone); }

    static DateLimits limits(String zone, Instant at) {
        LocalDate today = at.atZone(ZoneId.of(zone)).toLocalDate();
        return new DateLimits(today, today.plusDays(1), today.plusDays(365), 30, zone);
    }

    private boolean localDatesValid(CatalogQuery input, DateLimits limits) {
        return !input.checkIn().isBefore(limits.minCheckInDate()) && !input.checkOut().isAfter(limits.maxCheckOutDate());
    }

    private void requireLocalDates(CatalogQuery input, DateLimits limits) {
        if (input.dated() && !localDatesValid(input, limits)) throw dateError(List.of(limits));
    }

    private V2ApiException dateError(List<DateLimits> limits) {
        return new V2ApiException(400, "DATE_RANGE_INVALID", "Даты выходят за допустимый период отеля.", Map.of("dateLimits", limits));
    }

    static Pricing pricing(BigDecimal nightly, int nights) {
        String price = nightly.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        String total = nightly.multiply(BigDecimal.valueOf(nights)).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        return new Pricing(price, nights, total, "RUB", true);
    }

    static List<Photo> photos(List<CatalogPhoto> photos) {
        return photos.stream().filter(photo -> photo != null && photo.getUrl() != null && ASSET.matcher(photo.getUrl()).matches()
                        && text(photo.getAlt(), 200) && text(photo.getCredit(), 300) && text(photo.getLicense(), 100))
                .limit(10).map(photo -> new Photo(photo.getUrl(), photo.getAlt(), photo.getCredit(), photo.getLicense())).toList();
    }

    private static boolean text(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
    private static String id(Long id) { return Long.toString(id); }
    private static long pages(long total, int size) { return total / size + (total % size == 0 ? 0 : 1); }
    private static V2ApiException notFound() { return new V2ApiException(404, "NOT_FOUND", "Объект не найден."); }
}
