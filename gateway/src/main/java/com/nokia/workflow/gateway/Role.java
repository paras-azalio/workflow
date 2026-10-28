package com.nokia.workflow.gateway;

import java.util.Locale;

/** The gateway's roles, each including the ones before it. */
enum Role {
    VIEWER,
    EXECUTOR,
    ADMIN;

    boolean atLeast(Role other) {
        return ordinal() >= other.ordinal();
    }

    static Role parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Unknown role '%s': use VIEWER, EXECUTOR or ADMIN.".formatted(value));
        }
    }
}
