package com.kryukow.HotelReservationService.web.v2.booking;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kryukow.HotelReservationService.web.v2.common.V2ApiException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class BookingInputTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void idsCannotLoseJavascriptPrecisionOrSelectAnOwner() throws Exception {
        var input=json.readTree("{\"roomId\":\"9007199254740993\",\"checkInDate\":\"2026-11-10\",\"checkOutDate\":\"2026-11-12\",\"guests\":2}");
        assertThat(BookingSelection.parse(input,false).roomId()).isEqualTo(9007199254740993L);
        ((com.fasterxml.jackson.databind.node.ObjectNode)input).put("userId","1");assertThatThrownBy(()->BookingSelection.parse(input,false)).isInstanceOf(V2ApiException.class);
    }
    @Test void impossibleCalendarDatesAndFractionalGuestsAreRejected() throws Exception {
        for(String fields:List.of("\"checkInDate\":\"2026-02-30\",\"guests\":2","\"checkInDate\":\"2026-11-10\",\"guests\":1.5")){
            var node=json.readTree("{\"roomId\":\"1\",\"checkOutDate\":\"2026-11-12\","+fields+"}");assertThatThrownBy(()->BookingSelection.parse(node,false)).isInstanceOf(V2ApiException.class);
        }
    }
    @Test void keyRequiresUuidVersionFour(){assertThat(BookingSelection.key(UUID.randomUUID().toString())).isNotNull();assertThatThrownBy(()->BookingSelection.key("00000000-0000-0000-0000-000000000000")).isInstanceOf(V2ApiException.class);}
    @Test void recoveryCipherAuthenticatesAndRandomizesStoredQuotes(){
        QuoteCipher c=new QuoteCipher(Base64.getEncoder().encodeToString(new byte[32]));String a=c.encrypt("private quote"),b=c.encrypt("private quote");
        assertThat(a).isNotEqualTo(b);assertThat(c.decrypt(a)).isEqualTo("private quote");
        byte[] corrupt=Base64.getDecoder().decode(a);corrupt[15]^=1;
        assertThatThrownBy(()->c.decrypt(Base64.getEncoder().encodeToString(corrupt))).isInstanceOf(IllegalStateException.class);
    }
}
