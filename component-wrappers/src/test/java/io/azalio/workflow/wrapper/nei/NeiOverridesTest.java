package io.azalio.workflow.wrapper.nei;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class NeiOverridesTest {

    @Test
    public void loginAndGrcKeysAreSplitOffTheRequest() {
        Map<String, String> req = new LinkedHashMap<String, String>();
        req.put("ACTION", "MOP_EXECUTION");
        req.put("login.M2MUSER1", "north_ops");
        req.put("grc.NIAM_IP", "10.0.0.2");
        req.put("login.", "ignored: no key");
        assertEquals("{M2MUSER1=north_ops}", NeiWrapper.prefixed(req, NeiWrapper.LOGIN_PREFIX).toString());
        assertEquals("{NIAM_IP=10.0.0.2}", NeiWrapper.prefixed(req, NeiWrapper.GRC_PREFIX).toString());
    }
}
