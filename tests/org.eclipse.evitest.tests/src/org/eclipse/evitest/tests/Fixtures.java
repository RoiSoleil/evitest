package org.eclipse.evitest.tests;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.evitest.Activator;
import org.eclipse.evitest.core.Installation;
import org.eclipse.evitest.core.frameworks.JUnitReport;
import org.eclipse.evitest.core.NodeLocator;
import org.eclipse.evitest.core.TestCommandLine;
import org.eclipse.evitest.core.TestFramework;
import org.eclipse.evitest.core.TestSelector;
import org.eclipse.evitest.ui.VitestEventHandler;

/**
 * The projects of the end to end tests, one per framework in {@code fixtures/<framework id>}, and their runs.
 * <p>
 * The folder of the fixtures is given by the {@code evitest.fixtures} system property (set by the build). A fixture
 * whose framework is not installed ({@code npm ci} not run, Bun or Deno missing) is skipped.
 */
public final class Fixtures {

  private Fixtures() {
  }

  /** The folder of the fixtures. */
  public static File folder() {
    return new File(System.getProperty("evitest.fixtures", "fixtures")).getAbsoluteFile();
  }

  /**
   * The fixture of a framework, the test is skipped if the framework is not installed; it fails instead when the
   * EVITEST_REQUIRE_FRAMEWORKS variable is true (the build of GitHub installs all the frameworks).
   */
  public static File fixture(TestFramework framework) {
    File fixture = new File(folder(), framework.id());
    require(fixture.isDirectory(), "No fixture " + fixture);
    if (framework.usesNode()) {
      require(NodeLocator.find(System.getenv()) != null, "Node.js was not found");
    }
    require(Installation.find(framework, fixture, System.getenv(), null) != null,
        framework.label() + " is not installed for " + fixture + (framework.isPackage() ? ": run npm ci there" : ""));
    return fixture;
  }

  private static void require(boolean condition, String message) {
    if (Boolean.parseBoolean(System.getenv("EVITEST_REQUIRE_FRAMEWORKS"))) {
      assertTrue(condition, message);
    } else {
      assumeTrue(condition, message);
    }
  }

  /** The test file of the fixture with the tests of math, relative to the fixture. */
  public static String mathFile(TestFramework framework) {
    return switch (framework) {
      case VITEST, BUN -> "src/math.test.ts";
      case JEST -> "src/math.test.js";
      case MOCHA, NODE -> "test/math.test.js";
      case JASMINE -> "spec/mathSpec.js";
      case PLAYWRIGHT -> "tests/math.spec.ts";
      case DENO -> "src/math_test.ts";
    };
  }

  /** The test file with a failing beforeAll, null if the framework has no hooks (Deno). */
  public static String hooksFile(TestFramework framework) {
    return switch (framework) {
      case VITEST, BUN -> "src/hooks.test.ts";
      case JEST -> "src/hooks.test.js";
      case MOCHA, NODE -> "test/hooks.test.js";
      case JASMINE -> "spec/hooksSpec.js";
      case PLAYWRIGHT -> "tests/hooks.spec.ts";
      case DENO -> null;
    };
  }

  /** The test file which cannot be loaded. */
  public static String brokenFile(TestFramework framework) {
    return switch (framework) {
      case VITEST, BUN -> "src/broken.test.ts";
      case JEST -> "src/broken.test.js";
      case MOCHA, NODE -> "test/broken.test.js";
      case JASMINE -> "spec/brokenSpec.js";
      case PLAYWRIGHT -> "tests/broken.spec.ts";
      case DENO -> "src/broken_test.ts";
    };
  }

  /** The result of a run: the session of the Unit Test view, the handler of the events, the output. */
  record Run(FakeSession session, VitestEventHandler handler, String output) {
  }

  /** Runs the tests of the fixture as the launch does, with the reporter of EVitest or the JUnit report. */
  static Run run(TestFramework framework, List<String> filters, List<TestSelector> selectors)
      throws Exception {
    return run(framework, filters, selectors, false);
  }

  /**
   * Runs the tests of the fixture, with the colors of the console of Eclipse (FORCE_COLOR) if colors is true.
   */
  static Run run(TestFramework framework, List<String> filters, List<TestSelector> selectors, boolean colors)
      throws Exception {
    File fixture = fixture(framework);
    Installation installation = Installation.find(framework, fixture, System.getenv(), null);
    File node = NodeLocator.find(System.getenv());
    if (framework == TestFramework.NODE) {
      installation = new Installation(framework, null, NodeLocator.version(node));
    }
    File junit = framework.isLive() ? null : File.createTempFile("evitest-junit-", ".xml");
    if (junit != null) {
      junit.delete();
    }
    FakeSession session = new FakeSession();
    VitestEventHandler handler = new VitestEventHandler(session);
    StringBuilder output = new StringBuilder();
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }))) {
      TestCommandLine commandLine = new TestCommandLine(installation)
          .node(node == null ? "node" : node.getAbsolutePath())
          .nodeVersion(NodeLocator.version(node))
          .reporters(Activator.getReporterFolder())
          .root(fixture)
          .junitReport(junit)
          .filters(filters)
          .selectors(selectors);
      List<String> command = commandLine.build();
      String pattern = commandLine.testNamePattern();
      ProcessBuilder builder = new ProcessBuilder(command).directory(fixture).redirectErrorStream(true);
      Map<String, String> environment = builder.environment();
      environment.put("EVITEST_PORT", Integer.toString(server.getLocalPort()));
      environment.putAll(commandLine.environment());
      if (colors) {
        environment.remove("NO_COLOR");
        environment.put("FORCE_COLOR", "1");
      } else {
        environment.remove("FORCE_COLOR");
        environment.put("NO_COLOR", "1");
      }
      environment.put("CI", "1");
      Process process = builder.start();
      process.getOutputStream().close();
      Thread outputReader = new Thread(() -> {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
          reader.lines().forEach(line -> {
            synchronized (output) {
              output.append(line).append('\n');
            }
          });
        } catch (IOException e) {
          // Ended.
        }
      });
      outputReader.start();
      if (framework.isLive()) {
        Socket socket = accept(server, process);
        assertTrue(socket != null, "The reporter did not connect:\n" + command + "\n" + output);
        try (socket; BufferedReader reader = new BufferedReader(
            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
          String line;
          while ((line = reader.readLine()) != null) {
            handler.handle(line);
          }
        }
      }
      assertTrue(process.waitFor(180, TimeUnit.SECONDS), framework.label() + " did not end:\n" + output);
      outputReader.join(10_000);
      if (!framework.isLive()) {
        String xml = junit.isFile() ? Files.readString(junit.toPath(), StandardCharsets.UTF_8) : null;
        String text;
        synchronized (output) {
          text = output.toString();
        }
        for (String event : JUnitReport.toEvents(framework, installation.version(), fixture, xml, text,
            pattern)) {
          handler.handle(event);
        }
      }
      assertTrue(handler.isSessionEnded(), "The run did not end:\n" + command + "\n" + output);
    } finally {
      if (junit != null) {
        junit.delete();
      }
    }
    return new Run(session, handler, output.toString());
  }

  /** Waits for the reporter, null if the process ended without connecting. */
  private static Socket accept(ServerSocket server, Process process) throws IOException {
    server.setSoTimeout(500);
    long deadline = System.currentTimeMillis() + 180_000;
    long exitedAt = 0;
    while (System.currentTimeMillis() < deadline) {
      try {
        return server.accept();
      } catch (SocketTimeoutException e) {
        if (!process.isAlive()) {
          if (exitedAt == 0) {
            exitedAt = System.currentTimeMillis();
          } else if (System.currentTimeMillis() - exitedAt > 2000) {
            return null;
          }
        }
      }
    }
    return null;
  }
}
