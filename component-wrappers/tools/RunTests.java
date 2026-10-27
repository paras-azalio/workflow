import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

/**
 * Runs the JUnit 5 tests without Maven Surefire - for machines whose local Maven repository
 * lacks the Surefire provider and cannot reach a remote repository. Used by run-tests.sh.
 *
 * <p>No argument: every test of the module. With a class name: only that class.
 */
public final class RunTests {

    public static void main(String[] args) {
        LauncherDiscoveryRequest req = args.length > 0
                ? request().selectors(selectClass(args[0])).build()
                : request().selectors(selectPackage("com.nokia.workflow.wrapper")).build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.execute(req, listener);
        TestExecutionSummary s = listener.getSummary();
        PrintWriter out = new PrintWriter(System.out);
        s.printFailuresTo(out, 20);
        s.printTo(out);
        out.flush();
        System.exit(s.getTotalFailureCount() == 0 && s.getTestsFoundCount() > 0 ? 0 : 1);
    }
}
