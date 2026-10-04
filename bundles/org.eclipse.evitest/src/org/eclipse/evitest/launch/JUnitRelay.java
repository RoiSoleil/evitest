package org.eclipse.evitest.launch;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.debug.core.IStreamListener;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStreamMonitor;
import org.eclipse.debug.core.model.IStreamsProxy;
import org.eclipse.evitest.Activator;
import org.eclipse.evitest.core.frameworks.JUnitReport;
import org.eclipse.evitest.core.TestFramework;

/**
 * Gives the results of the frameworks without a reporter API (Bun, Deno) to the Unit Test view: when they end, their
 * JUnit report and their output become the events of a reporter, sent to the socket of the launch as a reporter would.
 */
final class JUnitRelay {

  /** The output kept for the errors of the tests. */
  private static final int OUTPUT_LIMIT = 4 * 1024 * 1024;

  private JUnitRelay() {
  }

  /**
   * Keeps the output of the process, and returns what sends the results when it ended (once).
   */
  static Runnable start(IProcess process, TestFramework framework, String version, File root, File junitReport,
      String pattern, int port) {
    StringBuilder output = new StringBuilder();
    IStreamsProxy streams = process.getStreamsProxy();
    if (streams != null) {
      for (IStreamMonitor monitor : new IStreamMonitor[] { streams.getOutputStreamMonitor(),
          streams.getErrorStreamMonitor() }) {
        if (monitor == null) {
          continue;
        }
        IStreamListener listener = (text, source) -> append(output, text);
        synchronized (output) {
          append(output, monitor.getContents());
          monitor.addListener(listener);
        }
      }
    }
    AtomicBoolean sent = new AtomicBoolean();
    return () -> {
      if (sent.compareAndSet(false, true)) {
        String text;
        synchronized (output) {
          text = output.toString();
        }
        send(JUnitReport.toEvents(framework, version, root, read(junitReport), text, pattern), port);
      }
    };
  }

  private static void append(StringBuilder output, String text) {
    if (text == null || text.isEmpty()) {
      return;
    }
    synchronized (output) {
      if (output.length() < OUTPUT_LIMIT) {
        output.append(text);
      }
    }
  }

  private static String read(File junitReport) {
    if (junitReport == null || !junitReport.isFile()) {
      return null;
    }
    try {
      return Files.readString(junitReport.toPath(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      Activator.log(e);
      return null;
    }
  }

  /** Sends the events to the socket of the launch, as the reporters of the other frameworks. */
  static void send(List<String> events, int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), port), 5000);
      Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
      for (String event : events) {
        writer.write(event);
        writer.write('\n');
      }
      writer.flush();
      socket.shutdownOutput();
    } catch (IOException e) {
      Activator.log(e);
    }
  }
}
