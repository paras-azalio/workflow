package com.nokia.workflow.wrapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a wrapper returns: a status, a message and the results as a flat {@code KEY=VALUE} map.
 *
 * <p>Status codes (the default success rule of every workflow step reads these):
 * <ul>
 *   <li>{@code 0} SUCCESS</li>
 *   <li>{@code 1} WARNING - finished, but something needs a look; does not stop a flow by default</li>
 *   <li>{@code 9} FAILED - same code an NEI uses for a failed task</li>
 * </ul>
 */
public final class WrapperResult {

    public enum Status {
        SUCCESS(0), WARNING(1), FAILED(9);

        private final int code;

        Status(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    private final Status status;
    private final String message;
    private final Map<String, String> results = new LinkedHashMap<String, String>();
    private final List<String> log = new ArrayList<String>();

    private WrapperResult(Status status, String message) {
        this.status = status;
        this.message = message == null ? "" : message;
    }

    public static WrapperResult success(String message) {
        return new WrapperResult(Status.SUCCESS, message);
    }

    public static WrapperResult warning(String message) {
        return new WrapperResult(Status.WARNING, message);
    }

    public static WrapperResult failed(String message) {
        return new WrapperResult(Status.FAILED, message);
    }

    /** Adds a result; {@code null} values are skipped so results never carry the text "null". */
    public WrapperResult put(String key, Object value) {
        if (key != null && value != null) {
            results.put(key, String.valueOf(value));
        }
        return this;
    }

    public WrapperResult putAll(Map<String, String> values) {
        if (values != null) {
            for (Map.Entry<String, String> e : values.entrySet()) {
                put(e.getKey(), e.getValue());
            }
        }
        return this;
    }

    public WrapperResult addLog(List<String> lines) {
        if (lines != null) {
            log.addAll(lines);
        }
        return this;
    }

    public Status status() {
        return status;
    }

    public String message() {
        return message;
    }

    public Map<String, String> results() {
        return Collections.unmodifiableMap(results);
    }

    public List<String> log() {
        return Collections.unmodifiableList(log);
    }
}
