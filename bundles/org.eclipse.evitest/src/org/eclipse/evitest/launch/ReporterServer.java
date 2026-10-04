package org.eclipse.evitest.launch;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.debug.core.ILaunch;

/**
 * The sockets where the reporters of the launches connect.
 * <p>
 * The launch delegate opens the socket before starting Vitest, so the reporter cannot connect before Eclipse listens.
 * The test runner client of the Unit Test view, created when the process is added to the launch, takes it here.
 */
public final class ReporterServer {

  private static final Map<ILaunch, ServerSocket> SERVERS = new ConcurrentHashMap<>();

  private ReporterServer() {
  }

  /** Opens a socket on a free port of the loopback interface for the launch, returns its port. */
  public static int open(ILaunch launch) throws IOException {
    // 127.0.0.1 where the reporter connects, even if Java prefers IPv6.
    ServerSocket server = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }));
    ServerSocket previous = SERVERS.put(launch, server);
    closeQuietly(previous);
    return server.getLocalPort();
  }

  /** Takes the socket of the launch: the caller closes it. Null if there is none. */
  public static ServerSocket take(ILaunch launch) {
    return SERVERS.remove(launch);
  }

  /** Closes the socket of the launch if nobody took it. */
  public static void close(ILaunch launch) {
    closeQuietly(SERVERS.remove(launch));
  }

  public static void closeQuietly(AutoCloseable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (Exception e) {
        // Closed anyway.
      }
    }
  }
}
