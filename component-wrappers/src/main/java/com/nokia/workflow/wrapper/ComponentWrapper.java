package com.nokia.workflow.wrapper;

/**
 * One component behind the common wrapper contract.
 *
 * <p>A wrapper takes the request parameters (the same {@code KEY=VALUE} style an NEI receives),
 * runs its component and returns a {@link WrapperResult}: a status, the results and log lines.
 * The caller echoes the request into the final result, so a wrapper never has to.
 *
 * <p>Wrappers are discovered with {@link java.util.ServiceLoader}: list the implementation in
 * {@code META-INF/services/com.nokia.workflow.wrapper.ComponentWrapper} of its jar and put the jar on
 * the classpath. No change to this module or to the workflow engine is needed to add one.
 *
 * <p>Rules for implementations:
 * <ul>
 *   <li>Do not print to {@code System.out}; stdout is reserved for the result. Use
 *       {@link WrapperContext#log(String)} for log lines.</li>
 *   <li>Report expected failures (missing parameter, file not found, validation failed) as a
 *       {@link WrapperResult} with status FAILED and a clear message. Throwing is reserved for
 *       unexpected errors; the caller turns those into FAILED too.</li>
 *   <li>Read deployment settings (directories, runtimes, servers) from {@link WrapperContext#config()},
 *       never from hard-coded paths.</li>
 * </ul>
 */
public interface ComponentWrapper {

    /** Component name used in requests, e.g. {@code CIQ_PROCESSOR}. Case-insensitive, unique. */
    String name();

    /** One-line description for {@code --list}. */
    String description();

    /** Runs the component for one request. */
    WrapperResult execute(WrapperRequest request, WrapperContext context) throws Exception;
}
