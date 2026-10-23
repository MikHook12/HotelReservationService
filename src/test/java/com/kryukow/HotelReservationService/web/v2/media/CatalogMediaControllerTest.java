package com.kryukow.HotelReservationService.web.v2.media;

import com.kryukow.HotelReservationService.web.v2.common.V2ApiException;
import com.kryukow.HotelReservationService.web.v2.common.V2ExceptionAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CatalogMediaControllerTest {
    private static final String NAME = "01234567-0123-0123-0123-0123456789ab.png";
    private CatalogMediaStore store;
    private MockMvc mvc;
    @BeforeEach void setUp() {
        store = mock(CatalogMediaStore.class);
        mvc = MockMvcBuilders.standaloneSetup(new CatalogMediaController(store))
                .setControllerAdvice(new V2ExceptionAdvice()).build();
    }

    @Test void uploadReturnsCreatedAndStableRelativeUrl() throws Exception {
        when(store.upload(any())).thenReturn("/media/" + NAME);
        mvc.perform(multipart("/api/v2/admin/media").file(new MockMultipartFile("file", new byte[] {1})))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.url").value("/media/" + NAME));
    }

    @Test void getReturnsOnlyImageBytesAndSafeHeaders() throws Exception {
        when(store.read(NAME)).thenReturn(new CatalogMediaStore.StoredMedia(new byte[] {1, 2}, "image/png"));
        mvc.perform(get("/api/v2/media/" + NAME)).andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(new byte[] {1, 2}))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "public, max-age=86400, immutable"));
    }

    @Test void missingPartAndFileUseV2ErrorEnvelope() throws Exception {
        mvc.perform(multipart("/api/v2/admin/media"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        when(store.read(NAME)).thenThrow(new V2ApiException(404, "NOT_FOUND", "Фото не найдено."));
        mvc.perform(get("/api/v2/media/" + NAME)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
