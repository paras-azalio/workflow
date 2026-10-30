package io.azalio.workflow.wrapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** The request parameters of one call: an ordered, flat {@code KEY=VALUE} map, like an NEI arglist. */
public final class WrapperRequest {

    private final Map<String, String> params;

    public WrapperRequest(Map<String, String> params) {
        this.params = new LinkedHashMap<String, String>(params);
    }

    public Map<String, String> asMap() {
        return Collections.unmodifiableMap(params);
    }

    /** The value, or {@code null} when the key is absent or blank. Keys are case-sensitive, like NEI args. */
    public String get(String key) {
        String v = params.get(key);
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    public String get(String key, String defaultValue) {
        String v = get(key);
        return v == null ? defaultValue : v;
    }

    public boolean has(String key) {
        return get(key) != null;
    }

    /** Thrown to the wrapper's caller as a FAILED result naming the missing key. */
    public String require(String key) {
        String v = get(key);
        if (v == null) {
            throw new MissingParameterException(key);
        }
        return v;
    }

    /** A required parameter is missing; reported as FAILED with message {@code Missing parameter: KEY}. */
    public static final class MissingParameterException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String key;

        public MissingParameterException(String key) {
            super("Missing parameter: " + key);
            this.key = key;
        }

        public String key() {
            return key;
        }
    }
}
