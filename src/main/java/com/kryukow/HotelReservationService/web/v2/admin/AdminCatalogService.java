package com.kryukow.HotelReservationService.web.v2.admin;

import com.kryukow.HotelReservationService.model.Hotel;
import com.kryukow.HotelReservationService.model.Room;
import com.kryukow.HotelReservationService.repository.HotelRepository;
import com.kryukow.HotelReservationService.repository.RoomRepository;
import com.kryukow.HotelReservationService.web.v2.catalog.model.CatalogPhoto;
import com.kryukow.HotelReservationService.web.v2.common.V2ApiException;
import com.kryukow.HotelReservationService.web.v2.media.CatalogMediaStore;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.kryukow.HotelReservationService.web.v2.admin.AdminCatalogDtos.*;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name="app.web.v2.enabled", havingValue="true")
@Transactional
public class AdminCatalogService {
    private final HotelRepository hotels;
    private final RoomRepository rooms;
    private final CatalogMediaStore media;
    private final jakarta.persistence.EntityManager entityManager;

    @Transactional(readOnly=true)
    public Page<HotelView> hotels(int page, int size) {
        var result=hotels.findAll(paging(page,size));
        return new Page<>(result.stream().map(this::view).toList(),page,size,result.getTotalElements(),result.getTotalPages());
    }
    @Transactional(readOnly=true)
    public HotelView hotel(long id) { return view(hotels.findById(id).orElseThrow(AdminCatalogService::missing)); }
    @Transactional(readOnly=true)
    public Page<RoomView> rooms(long hotelId,int page,int size) {
        if(!hotels.existsById(hotelId)) throw missing();
        var result=rooms.findAll((root,q,cb)->cb.equal(root.get("hotel").get("id"),hotelId),paging(page,size));
        return new Page<>(result.stream().map(this::view).toList(),page,size,result.getTotalElements(),result.getTotalPages());
    }
    public HotelView createHotel(HotelInput input) {
        var h=new Hotel(); apply(h,input,false);
        hotels.saveAndFlush(h); return view(h);
    }
    public HotelView updateHotel(long id,HotelInput input) {
        var h=hotels.lockById(id).orElseThrow(AdminCatalogService::missing);
        version(h.getVersion(),input.version());
        // Booking reads the hotel only after acquiring its room lock. Hold each room
        // before changing shared hotel terms, so a quote sees one coherent snapshot.
        rooms.findAll((root,q,cb)->cb.equal(root.get("hotel").get("id"),id),Sort.by("id"))
                .forEach(r->rooms.findByIdForUpdate(r.getId()).orElseThrow(AdminCatalogService::missing));
        apply(h,input,true); hotels.saveAndFlush(h); return view(h);
    }
    public RoomView createRoom(long hotelId,RoomInput input) {
        var h=hotels.lockById(hotelId).orElseThrow(AdminCatalogService::missing);
        var r=new Room();r.setHotel(h);apply(r,input,false);rooms.saveAndFlush(r);return view(r);
    }
    public RoomView updateRoom(long id,RoomInput input) {
        // Resolve immutable parent first; every admin writer locks hotel then room.
        var parentIds=entityManager.createQuery("select r.hotel.id from Room r where r.id=:id",Long.class).setParameter("id",id).getResultList();
        if(parentIds.isEmpty()) throw missing();
        hotels.lockById(parentIds.getFirst()).orElseThrow(AdminCatalogService::missing);
        var r=rooms.findByIdForUpdate(id).orElseThrow(AdminCatalogService::missing);
        version(r.getVersion(),input.version()); apply(r,input,true);rooms.saveAndFlush(r);return view(r);
    }
    private void apply(Hotel h,HotelInput i,boolean updating) {
        if(i==null) throw invalid("request");
        String slug=ascii(i.slug(),"slug",100);
        if(slug.matches("[0-9]+")) throw invalid("slug");
        if(updating && h.getSlug()!=null && !Objects.equals(h.getSlug(),slug)) throw invalid("slug");
        String name=text(i.name(),"name",200,false);
        if(hotels.exists((root,q,cb)->cb.and(cb.equal(root.get("name"),name),cb.notEqual(root.get("id"),h.getId()==null?-1L:h.getId())))) throw conflict();
        if(hotels.exists((root,q,cb)->cb.and(cb.equal(root.get("slug"),slug),cb.notEqual(root.get("id"),h.getId()==null?-1L:h.getId())))) throw conflict();
        String zone=text(i.timeZone(),"timeZone",64,false);
        if(!ZoneId.getAvailableZoneIds().contains(zone)) throw invalid("timeZone");
        if(i.distanceFromCityCenter()==null || !Double.isFinite(i.distanceFromCityCenter()) || i.distanceFromCityCenter()<0 || i.distanceFromCityCenter()>100000) throw invalid("distanceFromCityCenter");
        if(i.published()==null) throw invalid("published");
        var photos=photos(i.photos());
        h.setSlug(slug);h.setName(name);h.setTitle(name);h.setCity(text(i.city(),"city",100,false));
        h.setCityCode(ascii(i.cityCode(),"cityCode",64));h.setTimeZone(zone);h.setAddress(text(i.address(),"address",255,false));
        h.setDescription(text(i.description(),"description",10000,true));h.setDistanceFromCityCenter(i.distanceFromCityCenter());
        h.setPublished(i.published());h.setDemo(true);h.getPhotos().clear();h.getPhotos().addAll(photos);
    }
    private void apply(Room r,RoomInput i,boolean updating) {
        if(i==null) throw invalid("request");
        if(i.number()==null || i.number()<1 || i.number()>1000000) throw invalid("number");
        if(i.capacity()==null || i.capacity()<1 || i.capacity()>100) throw invalid("capacity");
        if(i.published()==null) throw invalid("published");
        if(i.nightlyPrice()==null || !i.nightlyPrice().matches("(?:0|[1-9][0-9]{0,9})\\.[0-9]{2}")) throw invalid("nightlyPrice");
        var price=new BigDecimal(i.nightlyPrice());if(price.signum()<=0) throw invalid("nightlyPrice");
        if(rooms.exists((root,q,cb)->cb.and(cb.equal(root.get("hotel").get("id"),r.getHotel().getId()),cb.equal(root.get("number"),i.number()),cb.notEqual(root.get("id"),r.getId()==null?-1L:r.getId())))) throw conflict();
        var photos=photos(i.photos());r.setName(text(i.name(),"name",200,false));r.setDescription(text(i.description(),"description",5000,true));
        r.setNumber(i.number());r.setCapacity(i.capacity());r.setPrice(price);r.setPublished(i.published());r.getPhotos().clear();r.getPhotos().addAll(photos);
    }
    private List<CatalogPhoto> photos(List<Photo> input) {
        if(input==null || input.size()>10) throw invalid("photos");
        var result=new ArrayList<CatalogPhoto>();
        for(var item:input) {
            if(item==null) throw invalid("photos");
            String url=text(item.url(),"photos.url",500,false);media.assertUsablePath(url);
            var p=new CatalogPhoto();p.setUrl(url);p.setAlt(text(item.alt(),"photos.alt",200,false));
            p.setCredit(text(item.credit(),"photos.credit",300,false));p.setLicense(text(item.license(),"photos.license",100,false));result.add(p);
        }
        return result;
    }
    private List<Photo> photoViews(List<CatalogPhoto> photos) {return photos.stream().map(p->new Photo(p.getUrl(),p.getAlt(),p.getCredit(),p.getLicense())).toList();}
    private HotelView view(Hotel h) {return new HotelView(h.getId().toString(),h.getVersion(),h.getSlug(),h.getName(),h.getCity(),h.getCityCode(),h.getTimeZone(),h.getAddress(),h.getDescription(),h.getDistanceFromCityCenter(),h.getPublished(),photoViews(h.getPhotos()));}
    private RoomView view(Room r) {return new RoomView(r.getId().toString(),r.getHotel().getId().toString(),r.getVersion(),r.getName(),r.getNumber(),r.getCapacity(),r.getPrice().setScale(2).toPlainString(),r.getDescription(),r.getPublished(),photoViews(r.getPhotos()));}
    private static PageRequest paging(int page,int size) {if(page<0 || page>1000000 || size<1 || size>100) throw invalid("page");return PageRequest.of(page,size,Sort.by("id"));}
    private static String ascii(String s,String field,int max) {s=text(s,field,max,false);if(!s.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) throw invalid(field);return s;}
    private static String text(String s,String field,int max,boolean emptyAllowed) {if(s==null || s.length()>max || (!emptyAllowed && s.isBlank()) || s.codePoints().anyMatch(c->Character.isISOControl(c) && c!='\n' && c!='\t')) throw invalid(field);return s.trim();}
    private static void version(Long current,Long expected) {if(expected==null || expected<0) throw invalid("version");if(!Objects.equals(current,expected)) throw conflict();}
    private static V2ApiException invalid(String field) {return new V2ApiException(400,"VALIDATION_FAILED","Проверьте поля запроса.").withFieldError(field,"INVALID_FORMAT","Проверьте значение поля.");}
    private static V2ApiException missing() {return new V2ApiException(404,"NOT_FOUND","Объект не найден.");}
    private static V2ApiException conflict() {return new V2ApiException(409,"RESOURCE_CONFLICT","Данные изменились или уже существуют. Обновите страницу.");}
}
