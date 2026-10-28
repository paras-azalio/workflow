package com.nokia.workflow.gateway;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Request paths as the rule check and Kestra must both see them. The raw path is percent-decoded once,
 * {@code //} and {@code .} segments are dropped and {@code ..} is applied; the result is what the rules match
 * AND what is forwarded (re-encoded), so a path can never pass the check in one form and reach Kestra in another.
 */
final class Paths {

    private static final String SEGMENT_SAFE = "-._~!$&'()*+,;=:@";

    private Paths() {
    }

    /** @param decoded the path the rules match; @param encoded the same path, percent-encoded, to forward */
    record Normalized(String decoded, String encoded) {
    }

    /** @throws IllegalArgumentException for a path that leaves the root, holds a NUL or backslash, or bad escapes */
    static Normalized normalize(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return new Normalized("/", "/");
        }
        boolean trailingSlash = rawPath.length() > 1 && rawPath.endsWith("/");
        Deque<String> segments = new ArrayDeque<>();
        for (String rawSegment : rawPath.split("/")) {
            String s = decode(rawSegment);
            if (s.indexOf('\0') >= 0 || s.indexOf('\\') >= 0) {
                throw new IllegalArgumentException("Invalid character in the request path.");
            }
            if (s.indexOf('/') >= 0) {
                throw new IllegalArgumentException("Encoded slashes are not allowed in the request path.");
            }
            if (s.isEmpty() || ".".equals(s)) {
                continue;
            }
            if ("..".equals(s)) {
                if (segments.isEmpty()) {
                    throw new IllegalArgumentException("The request path leaves the root.");
                }
                segments.removeLast();
                continue;
            }
            segments.addLast(s);
        }
        StringBuilder decoded = new StringBuilder();
        StringBuilder encoded = new StringBuilder();
        for (String s : segments) {
            decoded.append('/').append(s);
            encoded.append('/').append(encode(s));
        }
        if (decoded.isEmpty()) {
            return new Normalized("/", "/");
        }
        if (trailingSlash) {
            decoded.append('/');
            encoded.append('/');
        }
        return new Normalized(decoded.toString(), encoded.toString());
    }

    static String decode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    throw new IllegalArgumentException("Invalid percent-encoding in the request path.");
                }
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    throw new IllegalArgumentException("Invalid percent-encoding in the request path.");
                }
                out.write(hi * 16 + lo);
                i += 2;
            } else {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(b, 0, b.length);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    static String encode(String segment) {
        StringBuilder sb = new StringBuilder();
        for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || SEGMENT_SAFE.indexOf(c) >= 0) {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                  .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }
}
