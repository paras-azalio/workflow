package com.nokia.workflow.wrapper;

/** Wrappers registered only on the test classpath (src/test/resources/META-INF/services). */
public final class TestWrappers {

    private TestWrappers() {
    }

    /** Echoes the request as results; WANT_STATUS = SUCCESS / WARNING / FAILED picks the status. */
    public static final class Echo implements ComponentWrapper {
        @Override
        public String name() {
            return "TEST_ECHO";
        }

        @Override
        public String description() {
            return "test only";
        }

        @Override
        public WrapperResult execute(WrapperRequest request, WrapperContext context) {
            System.out.println("noise from the component");   // must not reach the real stdout
            context.log("echo called");
            String want = request.get("WANT_STATUS", "SUCCESS");
            WrapperResult r = "FAILED".equals(want) ? WrapperResult.failed("asked to fail")
                    : "WARNING".equals(want) ? WrapperResult.warning("asked to warn")
                    : WrapperResult.success("ok");
            r.putAll(request.asMap());
            r.put("REQUIRED", request.has("NEED") ? request.require("NEED") : null);
            r.put("CONFIG_DIR", context.config("test.dir"));
            return r;
        }
    }

    /** Throws, to check that unexpected errors become FAILED. */
    public static final class Boom implements ComponentWrapper {
        @Override
        public String name() {
            return "TEST_BOOM";
        }

        @Override
        public String description() {
            return "test only";
        }

        @Override
        public WrapperResult execute(WrapperRequest request, WrapperContext context) {
            request.require("NOT_THERE_UNLESS_GIVEN");
            throw new IllegalStateException("kaboom");
        }
    }
}
