package com.kryukow.HotelReservationService.security;

import com.kryukow.HotelReservationService.dto.user.UserCreateUpdateRequestDto;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordValidationTest {
    @Test void enforcesBcryptByteLimitForAsciiAndMultibytePasswords() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var request = new UserCreateUpdateRequestDto();
            request.setName("alice"); request.setEmail("alice@example.com");
            for (String valid : new String[]{"a".repeat(72), "я".repeat(36), "😀".repeat(18)}) {
                request.setPassword(valid);
                assertThat(validator.validate(request)).isEmpty();
            }
            for (String invalid : new String[]{"a".repeat(73), "я".repeat(37), "😀".repeat(19)}) {
                request.setPassword(invalid);
                assertThat(validator.validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("password"));
            }
        }
    }
}
