package com.kryukow.HotelReservationService.web.v2.catalog;

import com.kryukow.HotelReservationService.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Transactional
class CatalogMigrationIntegrationTest extends BaseIntegrationTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void v6PreservesLegacyRowsUnpublishedAndNeverInventsZonesLocationsOrSlugs() throws Exception {
        // Isolated, transaction-local schema in the test container; rollback removes only this fixture schema.
        String schema = "catalog_migration_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("create schema " + schema);
        jdbc.execute("set local search_path to " + schema);
        script("db/migration/V1__initial_schema.sql");
        jdbc.update("insert into hotels(id,name,title,city,address,distance_from_city_center) values (1,'Legacy','Title','Unknown','Address',1)");
        jdbc.update("insert into rooms(id,name,description,number,price,room_capacity,hotel_id) values (1,'Legacy room','Existing description',101,100,2,1)");
        script("db/migration/V6__browser_catalog.sql");
        var hotel = jdbc.queryForMap("select * from hotels where id=1");
        assertThat(hotel.get("name")).isEqualTo("Legacy");
        assertThat(hotel.get("published")).isEqualTo(false);
        assertThat(hotel.get("demo")).isEqualTo(false);
        assertThat(hotel.get("time_zone")).isNull();
        assertThat(hotel.get("slug")).isNull();
        assertThat(hotel.get("latitude")).isNull();
        assertThat(hotel.get("longitude")).isNull();
        assertThat(jdbc.queryForObject("select description from rooms where id=1", String.class)).isEqualTo("Existing description");
        assertThat(jdbc.queryForObject("select published from rooms where id=1", Boolean.class)).isFalse();
        assertThatThrownBy(() -> jdbc.update("insert into hotel_photos values (1,0,'/media/../private.jpg','Alt','Credit','CC0')"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private void script(String resource) throws Exception {
        String sql;
        try (var stream = new ClassPathResource(resource).getInputStream()) { sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
        // Execute as one PostgreSQL script so PL/pgSQL dollar-quoted bodies retain their semicolons.
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) { statement.execute(sql); }
            return null;
        });
    }
}
