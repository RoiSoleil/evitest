package org.eclipse.evitest.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.evitest.core.NodeLocator;
import org.junit.jupiter.api.Test;

/**
 * Runs the tests of the reporters of EVitest ({@code reporter/test/*.test.cjs}, with the test runner of Node.js): the
 * contracts of the reporters with the documented APIs of the frameworks, without the frameworks.
 */
class ReportersTest {

  @Test
  void theTestsOfTheReportersPass() throws Exception {
    File reporters = new File(System.getProperty("evitest.reporters", "../../bundles/org.eclipse.evitest/reporter"));
    assumeTrue(new File(reporters, "test").isDirectory(), "No sources of the reporters in " + reporters);
    File node = NodeLocator.find(System.getenv());
    if (Boolean.parseBoolean(System.getenv("EVITEST_REQUIRE_FRAMEWORKS"))) {
      assertTrue(node != null, "Node.js was not found");
    }
    assumeTrue(node != null, "Node.js was not found");
    ProcessBuilder builder = new ProcessBuilder(List.of(node.getAbsolutePath(), "--test", "--test-reporter=spec",
        "test/*.test.cjs")).directory(reporters).redirectErrorStream(true);
    builder.environment().remove("EVITEST_PORT");
    builder.environment().remove("EVITEST_PATTERN");
    builder.environment().put("NO_COLOR", "1");
    Process process = builder.start();
    process.getOutputStream().close();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(120, TimeUnit.SECONDS), output);
    assertEquals(0, process.exitValue(), output);
    assertTrue(output.contains("fail 0"), output);
  }
}
