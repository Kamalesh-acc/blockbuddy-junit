package bb;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherFactory;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

/**
 * A small JUnit runner window (classic JUnit Swing runner style) for the JUnit 5 tests.
 *
 * Run: java -cp ... bb.JUnitWindow [className] [--auto] [--shot file.png]
 *   className  bb.M1HandleTest, bb.M3AcceptTest, or "bb" for all tests
 *   --auto     press Run on start
 *   --shot     after the run, save a screenshot of the window and exit
 */
public class JUnitWindow extends JFrame {

    private static final Color GREEN = new Color(0, 200, 0);
    private static final Color RED = new Color(220, 0, 0);

    private final JComboBox<String> className = new JComboBox<>(
            new String[]{"bb.M1HandleTest", "bb.M3AcceptTest", "bb"});
    private final JButton run = new JButton("Run");
    private final JProgressBar bar = new JProgressBar();
    private final JLabel runs = new JLabel("Runs: 0/0");
    private final JLabel errors = new JLabel("Errors: 0");
    private final JLabel failures = new JLabel("Failures: 0");
    private final DefaultListModel<String> results = new DefaultListModel<>();
    private final DefaultListModel<String> failureList = new DefaultListModel<>();
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Tests");
    private final JTree tree = new JTree(root);
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel status = new JLabel(" ");

    private int total, done, nErrors, nFailures;

    public JUnitWindow(String initial) {
        super("JUnit");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        JMenuBar mb = new JMenuBar();
        JMenu menu = new JMenu("JUnit");
        menu.setMnemonic('J');
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(e -> System.exit(0));
        menu.add(exit);
        mb.add(menu);
        setJMenuBar(mb);

        className.setEditable(true);
        className.setSelectedItem(initial);
        run.addActionListener(e -> startRun());

        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 2, 4, 2);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.gridwidth = 3; c.anchor = GridBagConstraints.WEST;
        p.add(bold(new JLabel("Test class name:")), c);

        c.gridy = 1; c.gridwidth = 1; c.weightx = 1;
        p.add(className, c);
        c.gridx = 1; c.weightx = 0;
        p.add(new JButton("..."), c);
        c.gridx = 2;
        p.add(run, c);

        c.gridx = 0; c.gridy = 2; c.gridwidth = 3;
        JCheckBox reload = new JCheckBox("Reload classes every run", true);
        p.add(reload, c);

        bar.setForeground(GREEN);
        bar.setPreferredSize(new Dimension(380, 18));
        bar.setStringPainted(false);
        c.gridy = 3; c.gridwidth = 2; c.weightx = 1;
        p.add(bar, c);
        c.gridx = 2; c.gridwidth = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.CENTER;
        JLabel ju = new JLabel("<html><b><font color='#00a000'>J</font><font color='#e00000'>U</font></b></html>");
        ju.setFont(ju.getFont().deriveFont(Font.BOLD, 26f));
        p.add(ju, c);

        c.gridx = 0; c.gridy = 4; c.gridwidth = 3; c.fill = GridBagConstraints.HORIZONTAL;
        JPanel counters = new JPanel(new GridLayout(1, 3));
        errors.setIcon(new XIcon());
        failures.setIcon(new XIcon());
        counters.add(bold(runs));
        counters.add(bold(errors));
        counters.add(bold(failures));
        counters.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(150, 170, 200)));
        p.add(counters, c);

        c.gridy = 5;
        p.add(bold(new JLabel("Results:")), c);

        JList<String> resultList = new JList<>(results);
        resultList.setCellRenderer(new ResultRenderer());
        JScrollPane rs = new JScrollPane(resultList);
        rs.setPreferredSize(new Dimension(380, 170));
        c.gridy = 6; c.gridwidth = 2; c.weightx = 1; c.weighty = 1; c.fill = GridBagConstraints.BOTH;
        p.add(rs, c);
        c.gridx = 2; c.gridwidth = 1; c.weightx = 0; c.weighty = 0; c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTH;
        JButton run2 = new JButton("Run");
        run2.setEnabled(false);
        p.add(run2, c);

        tabs.addTab("Failures", new XIcon(), new JScrollPane(new JList<>(failureList)));
        tabs.addTab("Test Hierarchy", new JScrollPane(tree));
        tabs.setPreferredSize(new Dimension(380, 150));
        c.gridx = 0; c.gridy = 7; c.gridwidth = 2; c.weighty = 0.6; c.fill = GridBagConstraints.BOTH;
        p.add(tabs, c);

        status.setBorder(BorderFactory.createLoweredBevelBorder());
        c.gridy = 8; c.gridwidth = 2; c.weighty = 0; c.fill = GridBagConstraints.HORIZONTAL;
        p.add(status, c);
        JButton exitBtn = new JButton("Exit");
        exitBtn.addActionListener(e -> System.exit(0));
        c.gridx = 2; c.gridwidth = 1;
        p.add(exitBtn, c);

        setContentPane(p);
        setSize(560, 640);
        setLocationRelativeTo(null);
    }

    private static JLabel bold(JLabel l) {
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    private void startRun() {
        String target = String.valueOf(className.getSelectedItem()).trim();
        results.clear();
        failureList.clear();
        root.removeAllChildren();
        root.setUserObject(target);
        ((DefaultTreeModel) tree.getModel()).reload();
        total = done = nErrors = nFailures = 0;
        bar.setForeground(GREEN);
        bar.setValue(0);
        run.setEnabled(false);
        status.setText("Running " + target + " ...");
        updateCounters();
        new Thread(() -> execute(target), "junit-run").start();
    }

    private void execute(String target) {
        long start = System.nanoTime();
        LauncherDiscoveryRequest req = request()
                .selectors(target.contains(".") && Character.isUpperCase(target.charAt(target.lastIndexOf('.') + 1))
                        ? selectClass(target) : selectPackage(target))
                .build();
        Launcher launcher = LauncherFactory.create();
        TestPlan plan = launcher.discover(req);
        int count = (int) plan.countTestIdentifiers(t -> t.isTest() && !t.getUniqueId().contains("test-template"));
        Map<String, DefaultMutableTreeNode> nodes = new HashMap<>();

        SwingUtilities.invokeLater(() -> {
            total = count;
            bar.setMaximum(Math.max(1, count));
            updateCounters();
        });

        launcher.execute(req, new TestExecutionListener() {
            @Override
            public void dynamicTestRegistered(TestIdentifier id) {
                // Parameterized cases (J-06) are only known once they start.
                if (id.isTest()) SwingUtilities.invokeLater(() -> {
                    total++;
                    bar.setMaximum(total);
                    updateCounters();
                });
            }

            @Override
            public void executionFinished(TestIdentifier id, TestExecutionResult result) {
                if (!id.isTest()) {
                    // A class whose setup failed never runs its tests: show that as an error.
                    if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
                        Throwable t = result.getThrowable().orElse(null);
                        SwingUtilities.invokeLater(() -> {
                            nErrors++;
                            failureList.addElement(id.getDisplayName() + " (setup): "
                                    + (t == null ? result.getStatus() : t));
                            bar.setForeground(RED);
                            updateCounters();
                        });
                    }
                    return;
                }
                String parent = plan.getParent(id).map(TestIdentifier::getDisplayName).orElse("");
                Throwable t = result.getThrowable().orElse(null);
                boolean ok = result.getStatus() == TestExecutionResult.Status.SUCCESSFUL;
                boolean failure = !ok && t instanceof AssertionError;
                SwingUtilities.invokeLater(() -> {
                    done++;
                    String name = label(parent, id.getDisplayName());
                    results.addElement((ok ? "OK|" : "X|") + name);
                    DefaultMutableTreeNode cls = nodes.computeIfAbsent(parent, k -> {
                        DefaultMutableTreeNode n = new DefaultMutableTreeNode(k);
                        root.add(n);
                        return n;
                    });
                    cls.add(new DefaultMutableTreeNode((ok ? "✔ " : "✘ ") + name));
                    if (!ok) {
                        if (failure) nFailures++; else nErrors++;
                        failureList.addElement(name + ": " + (t == null ? result.getStatus() : t.getMessage()));
                        bar.setForeground(RED);
                    }
                    bar.setValue(done);
                    updateCounters();
                });
            }
        });

        double secs = (System.nanoTime() - start) / 1e9;
        SwingUtilities.invokeLater(() -> {
            sortResults();
            ((DefaultTreeModel) tree.getModel()).reload();
            for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
            tabs.setSelectedIndex(failureList.isEmpty() ? 1 : 0);
            status.setText(String.format("Finished: %.3f seconds", secs));
            run.setEnabled(true);
        });
    }

    /** Lists results in J-number order (they finish in any order). */
    private void sortResults() {
        java.util.List<String> all = java.util.Collections.list(results.elements());
        all.sort(java.util.Comparator.comparing(v -> v.substring(v.indexOf('|') + 1)));
        results.clear();
        all.forEach(results::addElement);
    }

    /** Parameterized J-06 cases are named "[1] ..." by JUnit; use our own display name instead. */
    private static String label(String parent, String name) {
        return name.startsWith("[") ? parent + " " + name : name;
    }

    private void updateCounters() {
        runs.setText("Runs: " + done + "/" + total);
        errors.setText("Errors: " + nErrors);
        failures.setText("Failures: " + nFailures);
    }

    /** Small red X, like the classic runner. */
    private static final class XIcon implements Icon {
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(RED);
            g2.setStroke(new BasicStroke(2f));
            g2.drawLine(x + 1, y + 2, x + 8, y + 9);
            g2.drawLine(x + 8, y + 2, x + 1, y + 9);
            g2.dispose();
        }
        public int getIconWidth() { return 10; }
        public int getIconHeight() { return 11; }
    }

    private static final class ResultRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focus) {
            String v = String.valueOf(value);
            boolean ok = v.startsWith("OK|");
            JLabel l = (JLabel) super.getListCellRendererComponent(list, v.substring(v.indexOf('|') + 1),
                    index, selected, focus);
            l.setText((ok ? "✔  " : "✘  ") + l.getText());
            if (!selected) l.setForeground(ok ? new Color(0, 130, 0) : RED);
            return l;
        }
    }

    /**
     * Draws the window into an image (title bar + contents), so other windows on the
     * screen can never end up in the screenshot.
     */
    java.awt.image.BufferedImage snapshot() {
        JRootPane rp = getRootPane();
        int bar = 32, w = rp.getWidth(), h = rp.getHeight();
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                w + 2, h + bar + 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(150, 150, 150));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(Color.WHITE);
        g.fillRect(1, 1, w, bar);
        // Small "Ju" app icon and title.
        g.setColor(new Color(40, 40, 40));
        g.fillRect(10, 9, 16, 14);
        g.setFont(new Font("Segoe UI", Font.BOLD, 10));
        g.setColor(new Color(0, 200, 0));
        g.drawString("J", 12, 20);
        g.setColor(new Color(240, 60, 60));
        g.drawString("u", 18, 20);
        g.setColor(Color.BLACK);
        g.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        g.drawString(getTitle(), 34, 21);
        // Minimize, maximize, close.
        g.setStroke(new BasicStroke(1f));
        int cx = w - 22, cy = bar / 2 + 1;
        g.drawLine(cx - 5, cy - 5, cx + 5, cy + 5);
        g.drawLine(cx + 5, cy - 5, cx - 5, cy + 5);
        g.drawRect(w - 22 - 46 - 5, cy - 5, 10, 10);
        g.drawLine(w - 22 - 92 - 5, cy, w - 22 - 92 + 5, cy);
        g.translate(1, bar + 1);
        rp.printAll(g);
        g.dispose();
        return img;
    }

    public static void main(String[] args) throws Exception {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        String target = "bb.M1HandleTest";
        boolean auto = false;
        String shot = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--auto")) auto = true;
            else if (args[i].equals("--shot")) shot = args[++i];
            else target = args[i];
        }
        String t = target;
        JUnitWindow[] w = new JUnitWindow[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new JUnitWindow(t);
            w[0].setVisible(true);
            w[0].toFront();
        });
        if (auto || shot != null) SwingUtilities.invokeAndWait(w[0]::startRun);
        if (shot != null) {
            while (!w[0].status.getText().startsWith("Finished")) Thread.sleep(300);
            Thread.sleep(800);
            SwingUtilities.invokeAndWait(() -> { w[0].toFront(); w[0].repaint(); });
            Thread.sleep(600);
            File out = new File(shot);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    ImageIO.write(w[0].snapshot(), "png", out);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            System.exit(0);
        }
    }
}
