package org.eclipse.evitest.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.evitest.Activator;
import org.eclipse.evitest.core.NodeLocator;
import org.eclipse.evitest.core.TestSelector;
import org.eclipse.evitest.core.VitestCommandLine;
import org.eclipse.evitest.core.VitestLocator;
import org.eclipse.evitest.ui.StackFrameLocation;
import org.eclipse.evitest.ui.TestElementData;
import org.eclipse.evitest.ui.VitestEventHandler;
import org.eclipse.unittest.model.ITestElement.FailureTrace;
import org.junit.jupiter.api.Test;

/**
 * Runs the real Vitest of the fixture project with the reporter, and checks what the Unit Test view receives.
 * <p>
 * Needs Node.js and the dependencies of the fixture ({@code npm ci} in {@code fixture}): skipped without them. The
 * fixture is found with the {@code evitest.fixture} system property (set by the build), the reporter with
 * {@code evitest.reporter} or in the bundle.
 */
class VitestEndToEndTest {

  private static File fixture() {
    File fixture = new File(System.getProperty("evitest.fixture", "fixture"));
    assumeTrue(new File(fixture, "node_modules/vitest/vitest.mjs").isFile(),
        "The fixture has no node_modules: run npm ci in " + fixture.getAbsolutePath());
    return fixture;
  }

  private static File node() {
    File node = NodeLocator.find(System.getenv());
    assumeTrue(node != null, "Node.js was not found");
    return node;
  }

  private static String reporter() throws IOException {
    String reporter = System.getProperty("evitest.reporter");
    return reporter != null ? reporter : Activator.getReporterFile().getAbsolutePath();
  }

  /** Runs Vitest in the fixture, returns the session and the output of Vitest. */
  private static FakeSession run(List<String> filters, List<TestSelector> selectors) throws Exception {
    File fixture = fixture();
    VitestLocator.Installation installation = VitestLocator.findInstallation(fixture);
    FakeSession session = new FakeSession();
    VitestEventHandler handler = new VitestEventHandler(session);
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }))) {
      List<String> command = new VitestCommandLine().node(node().getAbsolutePath()).vitest(installation)
          .reporter(reporter()).filters(filters).selectors(selectors).build();
      ProcessBuilder builder = new ProcessBuilder(command).directory(fixture).redirectErrorStream(true);
      Map<String, String> environment = builder.environment();
      environment.put("EVITEST_PORT", Integer.toString(server.getLocalPort()));
      environment.remove("FORCE_COLOR");
      Process process = builder.start();
      StringBuilder output = new StringBuilder();
      Thread outputReader = new Thread(() -> {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
          reader.lines().forEach(line -> output.append(line).append('\n'));
        } catch (IOException e) {
          // Ended.
        }
      });
      outputReader.start();
      server.setSoTimeout(120_000);
      try (Socket socket = server.accept(); BufferedReader reader = new BufferedReader(
          new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          handler.handle(line);
        }
      }
      assertTrue(process.waitFor(120, TimeUnit.SECONDS), "Vitest did not end");
      outputReader.join(10_000);
      assertTrue(handler.isSessionEnded(), "The run did not end:\n" + output);
    }
    return session;
  }

  private static List<String> sorted(List<String> lines) {
    List<String> copy = new ArrayList<>(lines);
    Collections.sort(copy);
    return copy;
  }

  @Test
  void runsAllTheTests() throws Exception {
    FakeSession session = run(List.of(), List.of());
    List<String> log = session.log;
    assertEquals("started", log.get(0));
    assertEquals("completed", log.get(log.size() - 1));
    assertTrue(log.contains("suite src/math.test.ts (8)"), log.toString());
    assertTrue(log.contains("ended src/math.test.ts > math > adds > one and one"), log.toString());
    assertTrue(log.contains("failed src/math.test.ts > math > compares objects FAILURE"), log.toString());
    assertTrue(log.contains("failed src/math.test.ts > throws ERROR"), log.toString());
    assertTrue(log.contains("ignored src/math.test.ts > math > skipped"), log.toString());
    assertTrue(log.contains("ignored src/math.test.ts > math > later"), log.toString());
    assertTrue(log.contains("ended src/math.test.ts > doubles 2"), log.toString());
    assertTrue(log.contains("ended src/math.test.ts > doubles 3"), log.toString());
    // A failed beforeAll fails its suite and skips its tests.
    assertTrue(log.contains("failed src/hooks.test.ts > with a broken hook ERROR"), log.toString());
    assertTrue(log.contains("ignored src/hooks.test.ts > with a broken hook > never runs"), log.toString());
    // A file which cannot be loaded fails without tests.
    assertTrue(log.contains("suite src/broken.test.ts (0)"), log.toString());
    assertTrue(log.contains("failed src/broken.test.ts ERROR"), log.toString());

    FakeSession.Element compares = session.element("src/math.test.ts > math > compares objects");
    FailureTrace trace = compares.getFailureTrace();
    assertTrue(trace.isComparisonFailure());
    assertTrue(trace.getExpected().contains("\"b\": 3"), trace.getExpected());
    assertTrue(trace.getActual().contains("\"b\": 2"), trace.getActual());
    // The first frame is the line of the assertion in the source (source maps).
    String frame = trace.getTrace().lines().filter(line -> line.contains("math.test.ts")).findFirst().orElseThrow();
    StackFrameLocation location = StackFrameLocation.parse(frame, null);
    assertEquals(new File(fixture(), "src/math.test.ts").getCanonicalPath(), new File(location.file()).getCanonicalPath());
    assertEquals(18, location.line());

    TestElementData data = TestElementData.parse(session.element("src/math.test.ts > math > adds > one and one").getData());
    assertEquals(List.of("math", "adds", "one and one"), data.names());
    assertEquals(Integer.valueOf(5), data.line());
  }

  @Test
  void runsOneTest() throws Exception {
    FakeSession session = run(List.of("src/math.test.ts"),
        List.of(TestSelector.test(List.of("math", "adds", "one and one"))));
    List<String> ended = sorted(session.log.stream().filter(line -> line.startsWith("ended ")).toList());
    assertEquals(List.of("ended src/math.test.ts > math > adds > one and one"), ended);
    assertTrue(session.log.stream().noneMatch(line -> line.contains("hooks.test.ts")), session.log.toString());
  }

  @Test
  void runsOneSuiteAndTemplates() throws Exception {
    FakeSession session = run(List.of("src/math.test.ts"),
        List.of(TestSelector.suite(List.of("math", "adds")), new TestSelector(false, List.of("doubles %i"), true)));
    List<String> ended = sorted(session.log.stream().filter(line -> line.startsWith("ended ")).toList());
    assertEquals(List.of("ended src/math.test.ts > doubles 2", "ended src/math.test.ts > doubles 3",
        "ended src/math.test.ts > math > adds > one and one", "ended src/math.test.ts > math > adds > two and two"),
        ended);
  }
}
