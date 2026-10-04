package org.eclipse.evitest.launch;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobFunction;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchDelegate;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.IStreamListener;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.ISourceLocator;
import org.eclipse.debug.core.model.IStreamMonitor;
import org.eclipse.debug.core.sourcelookup.ISourceLookupDirector;
import org.eclipse.evitest.Activator;

/**
 * Debugs the tests with the Node.js debugger of Wild Web Developer.
 * <p>
 * Vitest runs with {@code --inspectBrk} and {@code --no-file-parallelism}: each test file runs in a new process which
 * opens the inspector and waits for a debugger. Each time the inspector says it is listening, a Wild Web Developer
 * "attach" session is started in the launch of Vitest, so that its debug targets are in the same launch as the tests.
 */
final class NodeDebugger {

  /** The "Attach to a running Node.js application" launch configuration type of Wild Web Developer. */
  static final String ATTACH_TYPE = "org.eclipse.wildwebdeveloper.launchConfiguration.nodeDebugAttach";
  /** The source lookup of the debug adapters (LSP4E). */
  private static final String SOURCE_LOCATOR = "org.eclipse.lsp4e.debug.sourceLocator";

  private NodeDebugger() {
  }

  static boolean isAvailable() {
    return DebugPlugin.getDefault().getLaunchManager().getLaunchConfigurationType(ATTACH_TYPE) != null;
  }

  static void attachToEachTestFile(ILaunch launch, IProcess vitest, int port) {
    String listening = "Debugger listening on ws://127.0.0.1:" + port + "/";
    IStreamListener listener = new IStreamListener() {
      private final StringBuilder line = new StringBuilder();

      @Override
      public synchronized void streamAppended(String text, IStreamMonitor monitor) {
        for (int i = 0; i < text.length(); i++) {
          char c = text.charAt(i);
          if (c == '\n') {
            if (line.toString().contains(listening)) {
              attach(launch, port);
            }
            line.setLength(0);
          } else {
            line.append(c);
          }
        }
      }
    };
    vitest.getStreamsProxy().getErrorStreamMonitor().addListener(listener);
  }

  private static void attach(ILaunch launch, int port) {
    Job job = Job.create("Attaching the Node.js debugger to Vitest", (IJobFunction) monitor -> {
      if (launch.isTerminated()) {
        return Status.OK_STATUS;
      }
      try {
        ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
        ILaunchConfigurationType type = manager.getLaunchConfigurationType(ATTACH_TYPE);
        ILaunchConfigurationWorkingCopy configuration = type.newInstance(null, "Vitest (debugger)");
        configuration.setAttribute("address", "127.0.0.1");
        configuration.setAttribute("port", port);
        ILaunchDelegate[] delegates = type.getDelegates(Set.of(ILaunchManager.DEBUG_MODE));
        if (delegates.length == 0) {
          return Activator.error("Wild Web Developer has no delegate to attach the Node.js debugger.", null);
        }
        setSourceLocator(launch, manager, configuration);
        Set<IProcess> processes = new HashSet<>(Arrays.asList(launch.getProcesses()));
        Set<IDebugTarget> targets = new HashSet<>(Arrays.asList(launch.getDebugTargets()));
        delegates[0].getDelegate().launch(configuration, ILaunchManager.DEBUG_MODE, launch, monitor);
        List<IProcess> newProcesses = Arrays.stream(launch.getProcesses()).filter(p -> !processes.contains(p)).toList();
        List<IDebugTarget> newTargets = Arrays.stream(launch.getDebugTargets()).filter(t -> !targets.contains(t))
            .toList();
        terminateWith(newTargets, newProcesses);
        return Status.OK_STATUS;
      } catch (CoreException e) {
        return Activator.error("Could not attach the Node.js debugger to Vitest: " + e.getMessage(), e);
      }
    });
    job.setSystem(true);
    job.schedule();
  }

  /** The source lookup of the launch of Vitest is the one of the debug adapters: the editors open on the frames. */
  private static void setSourceLocator(ILaunch launch, ILaunchManager manager, ILaunchConfigurationWorkingCopy configuration) {
    if (launch.getSourceLocator() != null) {
      return;
    }
    try {
      ISourceLocator locator = manager.newSourceLocator(SOURCE_LOCATOR);
      if (locator instanceof ISourceLookupDirector director) {
        director.initializeDefaults(configuration);
      }
      launch.setSourceLocator(locator);
    } catch (CoreException e) {
      // Without LSP4E, the frames do not open an editor.
      Activator.log(e.getStatus());
    }
  }

  /**
   * Each attach session starts a debug adapter process: it is terminated with the debug target of the test file, when
   * the next test file runs.
   */
  private static void terminateWith(List<IDebugTarget> targets, List<IProcess> processes) {
    if (targets.isEmpty() || processes.isEmpty()) {
      return;
    }
    IDebugEventSetListener listener = new IDebugEventSetListener() {
      @Override
      public void handleDebugEvents(DebugEvent[] events) {
        if (targets.stream().allMatch(IDebugTarget::isTerminated)) {
          DebugPlugin.getDefault().removeDebugEventListener(this);
          for (IProcess process : processes) {
            if (process.canTerminate()) {
              try {
                process.terminate();
              } catch (DebugException e) {
                Activator.log(e);
              }
            }
          }
        }
      }
    };
    DebugPlugin.getDefault().addDebugEventListener(listener);
    listener.handleDebugEvents(new DebugEvent[0]);
  }
}
