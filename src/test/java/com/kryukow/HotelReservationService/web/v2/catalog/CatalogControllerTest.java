package com.kryukow.HotelReservationService.web.v2.catalog;

import com.kryukow.HotelReservationService.web.v2.common.V2ExceptionAdvice;
import com.kryukow.HotelReservationService.web.v2.common.V2ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CatalogControllerTest {
    CatalogService service;
    MockMvc mvc;
    @BeforeEach void setup() {
        service = mock(CatalogService.class);
        mvc = MockMvcBuilders.standaloneSetup(new CatalogController(service)).setControllerAdvice(new V2ExceptionAdvice()).build();
    }

    @Test
    void incompleteDateHasStructuredSafeFieldsAndCommonErrorHeaders() throws Exception {
        mvc.perform(get("/api/v2/hotels").param("checkInDate", "2027-01-02"))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("Vary", "Cookie"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("checkOutDate"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("REQUIRED"))
                .andExpect(jsonPath("$.fieldErrors[0].rejectedValue").doesNotExist());
        verifyNoInteractions(service);
    }

    @Test
    void duplicateUnknownAndUnsupportedDetailParametersAreRejected() throws Exception {
        mvc.perform(get("/api/v2/hotels").param("guests", "1", "2")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v2/cities").param("userId", "7")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v2/rooms/1").param("priceMin", "10.00")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void hiddenAndMissingRoomShareNotFoundEnvelope() throws Exception {
        when(service.room(eq(1L), any())).thenThrow(new V2ApiException(404, "NOT_FOUND", "Объект не найден."));
        mvc.perform(get("/api/v2/rooms/1")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.details").isEmpty());
    }
}
