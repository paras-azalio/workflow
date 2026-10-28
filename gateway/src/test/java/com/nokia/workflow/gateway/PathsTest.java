package com.nokia.workflow.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PathsTest {

    @Test
    public void dotSegmentsAndDoubleSlashesCannotHideAPathFromTheRules() {
        assertEquals("/api/v1/main/basicAuth", Paths.normalize("/ui/../api//v1/./main/basicAuth").decoded());
        assertEquals("/api/v1/main/basicAuth", Paths.normalize("/api/v1/main/%62asicAuth").decoded());
    }

    @Test
    public void theForwardedPathIsTheCheckedPathReEncoded() {
        Paths.Normalized n = Paths.normalize("/api/v1/main/flows/cr_automation/a%20b");
        assertEquals("/api/v1/main/flows/cr_automation/a b", n.decoded());
        assertEquals("/api/v1/main/flows/cr_automation/a%20b", n.encoded());
    }

    @Test
    public void trailingSlashOfTheUiIsKept() {
        assertEquals("/ui/", Paths.normalize("/ui/").encoded());
        assertEquals("/", Paths.normalize("/").encoded());
    }

    @Test
    public void pathsLeavingTheRootOrHidingSlashesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> Paths.normalize("/../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> Paths.normalize("/api/v1/main/flows%2F..%2Fx"));
        assertThrows(IllegalArgumentException.class, () -> Paths.normalize("/api/v1/%5Cx"));
        assertThrows(IllegalArgumentException.class, () -> Paths.normalize("/api/v1/%00"));
        assertThrows(IllegalArgumentException.class, () -> Paths.normalize("/api/v1/%zz"));
    }
}
