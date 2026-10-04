package org.eclipse.evitest.ui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.evitest.Activator;
import org.eclipse.evitest.launch.ReporterServer;
import org.eclipse.unittest.launcher.ITestRunnerClient;
import org.eclipse.unittest.model.ITestRunSession;

/**
 * Receives the events of the reporter of the launch, on the socket opened by the launch delegate, and gives them to a
 * {@link VitestEventHandler}.
 */
public class VitestTestRunnerClient implements ITestRunnerClient {

  /** How long the reader waits for the reporter once Vitest ended: the last events may still be in the socket. */
  private static final int GRACE_MILLIS = 2000;

  private final ITestRunSession session;
  private final VitestEventHandler handler;
  private volatile boolean stopped;
  private volatile ServerSocket server;
  private volatile Socket socket;

  public VitestTestRunnerClient(ITestRunSession session) {
    this.session = session;
    this.handler = new VitestEventHandler(session);
  }

  @Override
  public void startMonitoring() {
    ILaunch launch = session.getLaunch();
    server = launch == null ? null : ReporterServer.take(launch);
    Thread reader = new Thread(this::read, "EVitest results of " + (launch == null ? "?" : launchName(launch)));
    reader.setDaemon(true);
    reader.start();
  }

  private static String launchName(ILaunch launch) {
    return launch.getLaunchConfiguration() == null ? "Vitest" : launch.getLaunchConfiguration().getName();
  }

  private void read() {
    try {
      if (server == null) {
        handler.abort("The launch has no connection for the results of Vitest.");
        return;
      }
      Socket connection = accept();
      if (connection == null) {
        if (!stopped) {
          handler.abort("Vitest ended before running the tests: see the console for its errors.");
        }
        return;
      }
      socket = connection;
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while (!stopped && (line = reader.readLine()) != null) {
          try {
            handler.handle(line);
          } catch (RuntimeException e) {
            // One bad event does not lose the others.
            Activator.log(e);
          }
        }
      }
    } catch (IOException e) {
      if (!stopped) {
        Activator.log(e);
      }
    } finally {
      if (!handler.isSessionEnded()) {
        handler.abort(stopped ? null : "Vitest ended before the end of the tests: see the console.");
      }
      closeConnections();
    }
  }

  /** Waits for the reporter to connect, null if Vitest ended (or the session was stopped) without connecting. */
  private Socket accept() throws IOException {
    ILaunch launch = session.getLaunch();
    server.setSoTimeout(250);
    long terminatedSince = 0;
    while (!stopped) {
      try {
        return server.accept();
      } catch (SocketTimeoutException e) {
        if (launch.isTerminated()) {
          if (terminatedSince == 0) {
            terminatedSince = System.currentTimeMillis();
          } else if (System.currentTimeMillis() - terminatedSince > GRACE_MILLIS) {
            return null;
          }
        }
      }
    }
    return null;
  }

  @Override
  public void stopTest() {
    stopMonitoring();
    ILaunch launch = session.getLaunch();
    if (launch != null && launch.canTerminate()) {
      try {
        launch.terminate();
      } catch (DebugException e) {
        Activator.log(e);
      }
    }
  }

  @Override
  public void stopMonitoring() {
    stopped = true;
    closeConnections();
  }

  private void closeConnections() {
    ReporterServer.closeQuietly(socket);
    ReporterServer.closeQuietly(server);
  }
}
