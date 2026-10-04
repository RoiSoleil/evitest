package org.eclipse.evitest.launch;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.Job;
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
import org.eclipse.evitest.core.NodeLocator;
import org.eclipse.evitest.core.TestCommandLine;
import org.eclipse.evitest.core.TestFramework;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;

/**
 * Runs the tests with their framework and the reporter of EVitest, which sends the results to the Unit Test view.
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
    // The frameworks compile the tests themselves: building the workspace would only slow down the launch.
    return false;
  }

  @Override
  public void launch(ILaunchConfiguration configuration, String mode, ILaunch launch, IProgressMonitor monitor)
      throws CoreException {
    VitestLaunchSettings settings = VitestLaunchSettings.resolve(configuration);
    TestFramework framework = settings.getFramework();
    boolean debug = ILaunchManager.DEBUG_MODE.equals(mode);
    if (debug) {
      checkDebug(settings);
    }
    if (monitor != null && monitor.isCanceled()) {
      return;
    }

    int port;
    try {
      port = ReporterServer.open(launch);
    } catch (IOException e) {
      throw new CoreException(Activator.error("Could not open a socket for the results of the tests.", e));
    }
    File junitFolder = null;
    try {
      launch.setAttribute(VitestLaunchConstants.LAUNCH_PORT, Integer.toString(port));
      launch.setAttribute(VitestLaunchConstants.LAUNCH_ROOT, settings.getRoot().getAbsolutePath());
      launch.setAttribute(VitestLaunchConstants.LAUNCH_FRAMEWORK, framework.id());
      if (configuration.getAttribute(DebugPlugin.ATTR_CONSOLE_ENCODING, (String) null) == null) {
        // Node.js, Bun and Deno write UTF-8, whatever the encoding of the workspace.
        launch.setAttribute(DebugPlugin.ATTR_CONSOLE_ENCODING, "UTF-8");
      }

      File junitReport = null;
      if (!framework.isLive()) {
        junitFolder = Files.createTempDirectory("evitest-").toFile();
        junitReport = new File(junitFolder, "junit.xml");
      }
      int inspectorPort = debug ? freePort() : 0;
      TestCommandLine commandLine = new TestCommandLine(settings.getInstallation()) //
          .node(settings.getNode() == null ? "node" : settings.getNode().getAbsolutePath()) //
          .nodeVersion(settings.getNode() == null ? null : NodeLocator.version(settings.getNode())) //
          .reporters(Activator.getReporterFolder()) //
          .root(settings.getRoot()) //
          .junitReport(junitReport) //
          .filters(settings.getFilters()) //
          .selectors(settings.getSelectors()) //
          .namePattern(settings.getNamePattern()) //
          .updateSnapshots(settings.isUpdateSnapshots()) //
          .inspector(inspectorPort) //
          .arguments(settings.getArguments());
      List<String> command = commandLine.build();

      Map<String, String> environment = new HashMap<>(settings.getEnvironment());
      environment.put("EVITEST_PORT", Integer.toString(port));
      environment.putAll(commandLine.environment());
      if (Preferences.getBoolean(Preferences.COLORS) && !environment.containsKey("FORCE_COLOR")
          && !environment.containsKey("NO_COLOR")) {
        // The console of Eclipse shows the colors of the ANSI escape sequences.
        environment.put("FORCE_COLOR", "1");
      }
      String[] envp = environment.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
          .toArray(String[]::new);

      String[] commandLineArray = command.toArray(String[]::new);
      Process process = DebugPlugin.exec(commandLineArray, settings.getRoot(), envp, false);
      if (process == null) {
        ReporterServer.close(launch);
        deleteQuietly(junitFolder);
        return;
      }
      Map<String, String> attributes = new HashMap<>();
      attributes.put(IProcess.ATTR_PROCESS_TYPE, VitestLaunchConstants.PROCESS_TYPE);
      attributes.put(IProcess.ATTR_CMDLINE, DebugPlugin.renderArguments(commandLineArray, null));
      String version = settings.getInstallation().version();
      String label = command.get(0) + " (" + framework.label() + (version == null ? "" : " " + version) + ", "
          + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(new Date()) + ")";
      IProcess tests = DebugPlugin.newProcess(launch, process, label, attributes);
      Runnable beforeEnd = null;
      if (!framework.isLive()) {
        beforeEnd = JUnitRelay.start(tests, framework, version, settings.getRoot(), junitReport,
            commandLine.testNamePattern(), port);
      }
      terminateLaunchWith(launch, tests, beforeEnd, junitFolder);
      if (debug) {
        NodeDebugger.attachToEachTestFile(launch, tests, inspectorPort);
      }
    } catch (IOException | RuntimeException | CoreException e) {
      ReporterServer.close(launch);
      deleteQuietly(junitFolder);
      if (e instanceof CoreException coreException) {
        throw coreException;
      }
      throw new CoreException(Activator.error("Could not run " + framework.label() + ": " + e.getMessage(), e));
    }
  }

  /** Throws the reason why the tests cannot be debugged, if there is one. */
  private static void checkDebug(VitestLaunchSettings settings) throws CoreException {
    TestFramework framework = settings.getFramework();
    if (!framework.supportsDebug()) {
      throw new CoreException(Activator.error("Debugging the " + framework.label() + " tests is not supported: run"
          + " them, or debug them with the tools of " + framework.label() + ".", null));
    }
    if (framework == TestFramework.NODE) {
      int[] version = NodeLocator.majorMinor(NodeLocator.version(settings.getNode()));
      if (version[0] < 22 || (version[0] == 22 && version[1] < 8)) {
        throw new CoreException(Activator.error("Debugging the node:test tests needs Node.js 22.8 or newer (the tests"
            + " run in the process of the debugger).", null));
      }
    }
    if (!NodeDebugger.isAvailable()) {
      throw new CoreException(Activator.error(
          "Debugging the tests needs the Node.js debugger of Wild Web Developer: install it from the Eclipse Marketplace"
              + " (https://marketplace.eclipse.org/content/wild-web-developer-html-css-javascript-typescript-nodejs-angular-json-yaml-kubernetes-xml).",
          null));
    }
  }

  private static void deleteQuietly(File folder) {
    if (folder != null) {
      File[] files = folder.listFiles();
      if (files != null) {
        for (File file : files) {
          file.delete();
        }
      }
      folder.delete();
    }
  }

  /**
   * When the tests end, gives the results of the JUnit report (Bun, Deno), closes the socket of the reporter if nobody
   * took it, and terminates the rest of the launch (the debuggers).
   */
  private static void terminateLaunchWith(ILaunch launch, IProcess vitest, Runnable beforeEnd, File junitFolder) {
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
        // Not in the thread of the debug events, which would wait for the JUnit report to be read and sent.
        Job job = Job.create("Ending " + launchName(launch), monitor -> {
          if (beforeEnd != null) {
            beforeEnd.run();
          }
          deleteQuietly(junitFolder);
          ReporterServer.close(launch);
          if (!launch.isTerminated() && launch.canTerminate()) {
            try {
              launch.terminate();
            } catch (DebugException e) {
              Activator.log(e);
            }
          }
        });
        job.setSystem(true);
        job.schedule();
      }
    };
    DebugPlugin.getDefault().addDebugEventListener(listener);
    if (vitest.isTerminated()) {
      // Ended before the listener was added.
      listener.handleDebugEvents(new DebugEvent[] { new DebugEvent(vitest, DebugEvent.TERMINATE) });
    }
  }

  private static String launchName(ILaunch launch) {
    return launch.getLaunchConfiguration() == null ? "the tests" : launch.getLaunchConfiguration().getName();
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }))) {
      return socket.getLocalPort();
    }
  }
}
