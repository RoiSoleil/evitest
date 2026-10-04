package org.eclipse.evitest.launch;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.LaunchConfigurationDelegate;
import org.eclipse.evitest.Activator;
import org.eclipse.evitest.Preferences;
import org.eclipse.evitest.core.VitestCommandLine;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;

/**
 * Runs Vitest with the reporter of EVitest, which sends the results to the Unit Test view.
 */
public class VitestLaunchDelegate extends LaunchConfigurationDelegate {

  private static final String UNIT_TEST_BUNDLE = "org.eclipse.unittest.ui";

  public VitestLaunchDelegate() {
    // The Unit Test view follows the launches added once its bundle is started: it must be started before the
    // launch is added, which follows the creation of this delegate.
    Bundle bundle = Platform.getBundle(UNIT_TEST_BUNDLE);
    if (bundle != null && bundle.getState() != Bundle.ACTIVE) {
      try {
        bundle.start(Bundle.START_TRANSIENT);
      } catch (BundleException e) {
        Activator.log(e);
      }
    }
  }

  @Override
  public boolean buildForLaunch(ILaunchConfiguration configuration, String mode, IProgressMonitor monitor) {
    // Vite compiles the tests itself: building the workspace would only slow down the launch.
    return false;
  }

  @Override
  public void launch(ILaunchConfiguration configuration, String mode, ILaunch launch, IProgressMonitor monitor)
      throws CoreException {
    VitestLaunchSettings settings = VitestLaunchSettings.resolve(configuration);
    boolean debug = ILaunchManager.DEBUG_MODE.equals(mode);
    if (debug && !NodeDebugger.isAvailable()) {
      throw new CoreException(Activator.error(
          "Debugging the tests needs the Node.js debugger of Wild Web Developer: install it from the Eclipse Marketplace"
              + " (https://marketplace.eclipse.org/content/wild-web-developer-html-css-javascript-typescript-nodejs-angular-json-yaml-kubernetes-xml).",
          null));
    }
    if (monitor != null && monitor.isCanceled()) {
      return;
    }

    int port;
    try {
      port = ReporterServer.open(launch);
    } catch (IOException e) {
      throw new CoreException(Activator.error("Could not open a socket for the results of Vitest.", e));
    }
    try {
      launch.setAttribute(VitestLaunchConstants.LAUNCH_PORT, Integer.toString(port));
      launch.setAttribute(VitestLaunchConstants.LAUNCH_ROOT, settings.getRoot().getAbsolutePath());
      if (configuration.getAttribute(DebugPlugin.ATTR_CONSOLE_ENCODING, (String) null) == null) {
        // Node.js writes UTF-8, whatever the encoding of the workspace.
        launch.setAttribute(DebugPlugin.ATTR_CONSOLE_ENCODING, "UTF-8");
      }

      int inspectorPort = debug ? freePort() : 0;
      List<String> command = new VitestCommandLine() //
          .node(settings.getNode().getAbsolutePath()) //
          .vitest(settings.getInstallation()) //
          .reporter(Activator.getReporterFile().getAbsolutePath()) //
          .filters(settings.getFilters()) //
          .selectors(settings.getSelectors()) //
          .namePattern(settings.getNamePattern()) //
          .updateSnapshots(settings.isUpdateSnapshots()) //
          .inspector(inspectorPort) //
          .arguments(settings.getArguments()) //
          .build();

      Map<String, String> environment = new HashMap<>(settings.getEnvironment());
      environment.put("EVITEST_PORT", Integer.toString(port));
      if (Preferences.getBoolean(Preferences.COLORS) && !environment.containsKey("FORCE_COLOR")
          && !environment.containsKey("NO_COLOR")) {
        // The console of Eclipse shows the colors of the ANSI escape sequences.
        environment.put("FORCE_COLOR", "1");
      }
      String[] envp = environment.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
          .toArray(String[]::new);

      String[] commandLine = command.toArray(String[]::new);
      Process process = DebugPlugin.exec(commandLine, settings.getRoot(), envp, false);
      if (process == null) {
        ReporterServer.close(launch);
        return;
      }
      Map<String, String> attributes = new HashMap<>();
      attributes.put(IProcess.ATTR_PROCESS_TYPE, VitestLaunchConstants.PROCESS_TYPE);
      attributes.put(IProcess.ATTR_CMDLINE, DebugPlugin.renderArguments(commandLine, null));
      String version = settings.getInstallation().version();
      String label = settings.getNode().getAbsolutePath() + " (Vitest " + (version == null ? "" : version + ", ")
          + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(new Date()) + ")";
      IProcess vitest = DebugPlugin.newProcess(launch, process, label, attributes);
      terminateLaunchWith(launch, vitest);
      if (debug) {
        NodeDebugger.attachToEachTestFile(launch, vitest, inspectorPort);
      }
    } catch (IOException | RuntimeException | CoreException e) {
      ReporterServer.close(launch);
      if (e instanceof CoreException coreException) {
        throw coreException;
      }
      throw new CoreException(Activator.error("Could not run Vitest: " + e.getMessage(), e));
    }
  }

  /**
   * When Vitest ends, closes the socket of the reporter if nobody took it, and terminates the rest of the launch (the
   * debuggers).
   */
  private static void terminateLaunchWith(ILaunch launch, IProcess vitest) {
    IDebugEventSetListener listener = new IDebugEventSetListener() {
      @Override
      public void handleDebugEvents(DebugEvent[] events) {
        for (DebugEvent event : events) {
          if (event.getKind() == DebugEvent.TERMINATE && event.getSource() == vitest) {
            ended(this);
          }
        }
      }

      void ended(IDebugEventSetListener self) {
        DebugPlugin.getDefault().removeDebugEventListener(self);
        ReporterServer.close(launch);
        if (!launch.isTerminated() && launch.canTerminate()) {
          try {
            launch.terminate();
          } catch (DebugException e) {
            Activator.log(e);
          }
        }
      }
    };
    DebugPlugin.getDefault().addDebugEventListener(listener);
    if (vitest.isTerminated()) {
      // Ended before the listener was added.
      listener.handleDebugEvents(new DebugEvent[] { new DebugEvent(vitest, DebugEvent.TERMINATE) });
    }
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }))) {
      return socket.getLocalPort();
    }
  }
}
