package com.kryukow.HotelReservationService.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

@Transactional
class HotelRatingMigrationIntegrationTest extends BaseIntegrationTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void v2BackfillsLegacyAverageTimesCountAndInitializesVersion() {
        // PostgreSQL temporary table shadows public.hotels only on this transaction's connection.
        jdbc.execute("create temporary table hotels (id bigint, rating double precision, count_of_ratings integer) on commit drop");
        jdbc.execute("insert into hotels values (1, 4.2, 3), (2, null, 0)");
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V2__hotel_rating_aggregate.sql"));
            return null;
        });
        assertThat(jdbc.queryForObject("select rating_sum from hotels where id = 1", BigDecimal.class))
                .isEqualByComparingTo("12.6000");
        assertThat(jdbc.queryForObject("select rating_sum from hotels where id = 2", BigDecimal.class))
                .isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select version from hotels where id = 1", Long.class)).isZero();
    }
}
