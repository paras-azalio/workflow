package com.nokia.workflow.wrapper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Deployment settings and logging for one call.
 *
 * <p>{@link #config()} is the project / environment config file (it replaces the NEI's GRC section
 * in {@code mds.rc}): directories, naming suffixes, runtimes, servers. One file per project and
 * environment, e.g. {@code cr_automation-lab.properties}.
 */
public final class WrapperContext {

    private final Properties config;
    private final File workDir;
    private final java.util.Map<String, String> options;
    private final List<String> log = new ArrayList<String>();

    public WrapperContext(Properties config, File workDir) {
        this(config, workDir, null);
    }

    /** {@code options}: call options from the command line, e.g. {@code mml.log} (the NEI's MML log file). */
    public WrapperContext(Properties config, File workDir, java.util.Map<String, String> options) {
        this.config = config == null ? new Properties() : config;
        this.workDir = workDir == null ? new File(".") : workDir;
        this.options = options == null ? new java.util.HashMap<String, String>() : options;
    }

    /** A call option (e.g. {@code mml.log}), or {@code null}. */
    public String option(String name) {
        return options.get(name);
    }

    public Properties config() {
        return config;
    }

    /**
     * A config value, or {@code null} when absent or blank. {@code ${WORKDIR}} in a value is replaced by the
     * absolute working folder (forward slashes), for files that arrive with the step, e.g. Kestra namespace files.
     */
    public String config(String key) {
        String v = config.getProperty(key);
        if (v == null || v.trim().isEmpty()) {
            return null;
        }
        return v.trim().replace("${WORKDIR}", workDir.getAbsolutePath().replace('\\', '/'));
    }

    public String config(String key, String defaultValue) {
        String v = config(key);
        return v == null ? defaultValue : v;
    }

    /** The step's working folder; relative request paths resolve against it. */
    public File workDir() {
        return workDir;
    }

    /** Resolves a path from a request or config value: absolute stays as is, relative is under {@link #workDir()}. */
    public File resolve(String path) {
        File f = new File(path);
        return f.isAbsolute() ? f : new File(workDir, path);
    }

    public void log(String line) {
        log.add(line);
    }

    public List<String> logLines() {
        return Collections.unmodifiableList(log);
    }
}
