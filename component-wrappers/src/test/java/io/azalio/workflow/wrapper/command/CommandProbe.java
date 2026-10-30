package io.azalio.workflow.wrapper.command;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** The program CommandWrapperTest configures: answers from its request (STATUS, SLEEP_MS, EXIT, echo of TO). */
public final class CommandProbe {

    public static void main(String[] args) throws Exception {
        Properties request = new Properties();
        request.load(new InputStreamReader(new FileInputStream(args[0]), StandardCharsets.UTF_8));
        System.out.println("probe got TO=" + request.getProperty("TO"));
        if (request.getProperty("SLEEP_MS") != null) {
            Thread.sleep(Long.parseLong(request.getProperty("SLEEP_MS")));
        }
        Properties response = new Properties();
        if (request.getProperty("STATUS") != null) {
            response.setProperty("STATUS", request.getProperty("STATUS"));
            response.setProperty("MESSAGE", "probe says " + request.getProperty("STATUS"));
        }
        response.setProperty("MESSAGE_ID", "m-" + request.getProperty("TO"));
        response.setProperty("SEEN_PASSWORD", request.getProperty("SMTP_PASSWORD", ""));
        response.store(new OutputStreamWriter(new FileOutputStream(args[1]), StandardCharsets.UTF_8), null);
        System.exit(Integer.parseInt(request.getProperty("EXIT", "0")));
    }
}
