package com.kryukow.HotelReservationService.web.v2.booking;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Database recovery records are encrypted; the stable key is supplied outside the database. */
final class QuoteCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    QuoteCipher(String encoded) {
        byte[] raw;
        try { raw = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException e) { throw new IllegalStateException("Browser quote encryption key must be base64-encoded 32 bytes"); }
        if (raw.length != 32) throw new IllegalStateException("Browser quote encryption key must contain 32 bytes");
        key = new SecretKeySpec(raw,"AES");
    }
    String encrypt(String value) {
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv));
            byte[] encrypted = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[iv.length + encrypted.length]; System.arraycopy(iv,0,result,0,iv.length); System.arraycopy(encrypted,0,result,iv.length,encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Cannot protect browser recovery record"); }
    }
    String decrypt(String value) {
        try {
            byte[] input = Base64.getDecoder().decode(value); if (input.length < 28) throw new GeneralSecurityException();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOfRange(input,0,12)));
            return new String(c.doFinal(input,12,input.length-12),StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) { throw new IllegalStateException("Cannot recover protected browser record"); }
    }
    String token() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (GeneralSecurityException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
