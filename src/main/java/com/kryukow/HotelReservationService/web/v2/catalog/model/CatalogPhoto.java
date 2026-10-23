package com.kryukow.HotelReservationService.web.v2.catalog.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Reviewed, owned media metadata only. No upload or remote fetch behavior. */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class CatalogPhoto {
    @Column(name = "asset_path", nullable = false, length = 500)
    private String url;
    @Column(nullable = false, length = 200)
    private String alt;
    @Column(nullable = false, length = 300)
    private String credit;
    @Column(nullable = false, length = 100)
    private String license;
}
