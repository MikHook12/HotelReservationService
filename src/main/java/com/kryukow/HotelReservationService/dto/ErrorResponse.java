package com.kryukow.HotelReservationService.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {

    private int status; // 404, 400, 500
    private String error; // "Not Found", "Bad Request"
    private String message; // "Hotel not found with id: 99"
    private String path; // "uri=/api/v1/hotels/99"
    private LocalDateTime timestamp;
}