package io.azalio.workflow.wrapper.command;

import java.util.Map;
import java.util.TreeMap;

/**
 * A standalone program for the COMMAND tests: prints its arguments as ARG1=..., its P_* environment as ENV_X=...,
 * and last a JSON object {"RESULT": the first argument or P_RESULT, "ARGS": count}.
 */
public final class EchoProbe {

    private EchoProbe() {
    }

    public static void main(String[] args) {
        for (int i = 0; i < args.length; i++) {
            System.out.println("ARG" + (i + 1) + "=" + args[i]);
        }
        Map<String, String> env = new TreeMap<String, String>(System.getenv());
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getKey().startsWith("P_")) {
                System.out.println("ENV_" + e.getKey().substring(2) + "=" + e.getValue());
            }
        }
        String result = args.length > 0 ? args[0] : env.get("P_RESULT");
        System.out.println("not json {");
        System.out.println("{\"RESULT\": \"" + (result == null ? "" : result) + "\", \"ARGS\": " + args.length + "}");
    }
}
