package com.nokia.workflow.gateway;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** PBKDF2-HMAC-SHA256 password hashes ({@code pbkdf2_sha256$<iterations>$<salt>$<hash>}) and the password rules. */
final class Passwords {

    static final int ITERATIONS = 210_000;
    static final int MIN_LENGTH = 12;
    static final int MAX_LENGTH = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Checked when the user does not exist, so an unknown user takes as long as a wrong password. */
    private static final String DUMMY = hash("dummy-password-for-timing".toCharArray());

    private Passwords() {
    }

    static String hash(char[] password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return "pbkdf2_sha256$" + ITERATIONS + "$" + b64(salt) + "$" + b64(derive(password, salt, ITERATIONS));
    }

    static boolean verify(char[] password, String stored) {
        String[] p = stored == null ? new String[0] : stored.split("\\$");
        if (p.length != 4 || !"pbkdf2_sha256".equals(p[0])) {
            verify(password, DUMMY);
            return false;
        }
        byte[] expected = Base64.getDecoder().decode(p[3]);
        byte[] actual = derive(password, Base64.getDecoder().decode(p[2]), Integer.parseInt(p[1]));
        return MessageDigest.isEqual(expected, actual);
    }

    static void verifyDummy(char[] password) {
        verify(password, DUMMY);
    }

    /** Empty when the password is acceptable for this user. */
    static List<String> problems(String username, String password) {
        List<String> out = new ArrayList<>();
        if (password == null || password.length() < MIN_LENGTH) {
            out.add("The password must have at least " + MIN_LENGTH + " characters.");
        } else if (password.length() > MAX_LENGTH) {
            out.add("The password must not exceed " + MAX_LENGTH + " characters.");
        }
        if (password != null && username != null
                && password.trim().toLowerCase(Locale.ROOT).equals(username.trim().toLowerCase(Locale.ROOT))) {
            out.add("The password must not be the user name.");
        }
        return out;
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is not available in this JVM.", e);
        }
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().withoutPadding().encodeToString(b);
    }
}
