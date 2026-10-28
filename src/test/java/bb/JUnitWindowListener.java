package bb;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

/**
 * Opens the JUnit window whenever the tests run: from IntelliJ (right-click > Run),
 * from VS Code, or with "mvn test". The window fills in live as each test finishes.
 *
 * Registered through META-INF/services, so JUnit loads it by itself.
 * It stays off when there is no screen (GitHub Actions), when the env var CI is set,
 * or with -Dbb.window=off. After the run, the test run waits until you close the window.
 */
public class JUnitWindowListener implements TestExecutionListener {

    private JUnitWindow window;
    private TestExecutionListener ui;
    private final CountDownLatch closed = new CountDownLatch(1);

    private static boolean enabled() {
        return !JUnitWindow.ownRun
                && !GraphicsEnvironment.isHeadless()
                && System.getenv("CI") == null
                && !"off".equals(System.getProperty("bb.window"));
    }

    @Override
    public void testPlanExecutionStarted(TestPlan plan) {
        if (!enabled()) return;
        List<String> classes = plan.getRoots().stream()
                .flatMap(r -> plan.getChildren(r).stream())
                .map(id -> id.getSource().filter(s -> s instanceof ClassSource)
                        .map(s -> ((ClassSource) s).getClassName()).orElse(id.getDisplayName()))
                .collect(Collectors.toList());
        if (classes.isEmpty()) return;
        String target = classes.size() == 1 ? classes.get(0) : "bb";
        long start = System.nanoTime();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) {
                    // default look is fine
                }
                window = new JUnitWindow(target);
                window.embedded = true;
                window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                window.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosed(WindowEvent e) {
                        closed.countDown();
                    }
                });
                SwingUtilities.updateComponentTreeUI(window);
                window.resetFor(target);
                window.setTotal(JUnitWindow.countTests(plan));
                window.setVisible(true);
                window.toFront();
            });
        } catch (Exception e) {
            window = null;
            return;
        }
        ui = window.uiListener(plan, start);
    }

    @Override
    public void dynamicTestRegistered(TestIdentifier id) {
        if (ui != null) ui.dynamicTestRegistered(id);
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (ui != null) ui.executionFinished(id, result);
    }

    @Override
    public void testPlanExecutionFinished(TestPlan plan) {
        if (ui == null) return;
        ui.testPlanExecutionFinished(plan);
        String shot = System.getProperty("bb.window.shot"); // used to check the window without clicking
        if (shot != null) {
            try {
                SwingUtilities.invokeAndWait(() -> { });
                Thread.sleep(500);
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        javax.imageio.ImageIO.write(window.snapshot(), "png", new java.io.File(shot));
                    } catch (Exception ignored) {
                        // best effort
                    }
                    window.dispose();
                });
            } catch (Exception ignored) {
                // fall through
            }
        }
        try {
            closed.await(); // keep the window open until you close it
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
