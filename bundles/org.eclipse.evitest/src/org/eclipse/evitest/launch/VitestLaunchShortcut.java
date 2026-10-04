package org.eclipse.evitest.launch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.ILaunchShortcut2;
import org.eclipse.evitest.Activator;
import org.eclipse.evitest.core.JsTestScanner;
import org.eclipse.evitest.core.JsTestScanner.TestBlock;
import org.eclipse.evitest.core.TestSelector;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.unittest.ui.ConfigureViewerSupport;

/**
 * Run As > JavaScript Test: runs the selected test files, folders or projects with their test framework, or in an
 * editor the test or the suite at the cursor (the whole file outside the tests).
 */
public class VitestLaunchShortcut implements ILaunchShortcut2 {

  @Override
  public void launch(ISelection selection, String mode) {
    List<IResource> resources = resources(selection);
    if (resources.isEmpty()) {
      showError("Select test files, folders or a project to run.");
      return;
    }
    launch(resources, null, mode);
  }

  @Override
  public void launch(IEditorPart editor, String mode) {
    IResource resource = getLaunchableResource(editor);
    if (resource == null) {
      showError("This editor does not show a file of the workspace.");
      return;
    }
    launch(List.of(resource), selectorAtCursor(editor), mode);
  }

  /** The test or the suite at the cursor of the editor, null outside the tests (the whole file runs). */
  static TestSelector selectorAtCursor(IEditorPart editor) {
    ITextEditor textEditor = Adapters.adapt(editor, ITextEditor.class);
    if (textEditor == null || textEditor.getDocumentProvider() == null) {
      return null;
    }
    IDocument document = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
    ISelection selection = textEditor.getSelectionProvider() == null ? null
        : textEditor.getSelectionProvider().getSelection();
    if (document == null || !(selection instanceof ITextSelection textSelection)) {
      return null;
    }
    TestBlock block = JsTestScanner.find(JsTestScanner.scan(document.get()), textSelection.getOffset());
    return block == null ? null : block.toSelector();
  }

  /** Runs the tests of the resources, only the tests of the selector if it is not null. */
  public static void launch(List<? extends IResource> resources, TestSelector selector, String mode) {
    try {
      ILaunchConfiguration configuration = findConfiguration(resources, selector);
      if (configuration == null) {
        configuration = createConfiguration(resources, selector);
      }
      DebugUITools.launch(configuration, mode);
    } catch (CoreException e) {
      Activator.log(e.getStatus());
      showError(e.getStatus().getMessage());
    }
  }

  private static ILaunchConfiguration findConfiguration(List<? extends IResource> resources, TestSelector selector)
      throws CoreException {
    List<String> paths = paths(resources);
    List<String> selectors = selector == null ? Collections.emptyList() : List.of(selector.toJson());
    for (ILaunchConfiguration configuration : getManager().getLaunchConfigurations(getType())) {
      if (paths.equals(configuration.getAttribute(VitestLaunchConstants.ATTR_PATHS, Collections.emptyList()))
          && selectors.equals(configuration.getAttribute(VitestLaunchConstants.ATTR_SELECTORS, Collections.emptyList()))
          && configuration.getAttribute(VitestLaunchConstants.ATTR_NAME_PATTERN, "").isEmpty()) {
        return configuration;
      }
    }
    return null;
  }

  private static ILaunchConfiguration createConfiguration(List<? extends IResource> resources, TestSelector selector)
      throws CoreException {
    IResource first = resources.get(0);
    String name = first.getName();
    if (resources.size() > 1) {
      name += " and " + (resources.size() - 1) + " more";
    }
    if (selector != null) {
      name += " - " + selector.label();
    }
    // The names of the launch configurations are file names: no slash...
    name = getManager().generateLaunchConfigurationName(name.replaceAll("[\\\\/:*?\"<>|@&]", "_"));
    ILaunchConfigurationWorkingCopy configuration = getType().newInstance(null, name);
    configuration.setAttribute(VitestLaunchConstants.ATTR_PROJECT, first.getProject().getName());
    configuration.setAttribute(VitestLaunchConstants.ATTR_PATHS, paths(resources));
    if (selector != null) {
      configuration.setAttribute(VitestLaunchConstants.ATTR_SELECTORS, List.of(selector.toJson()));
    }
    configuration.setMappedResources(resources.toArray(IResource[]::new));
    new ConfigureViewerSupport(VitestLaunchConstants.VIEW_SUPPORT).apply(configuration);
    return configuration.doSave();
  }

  private static List<String> paths(List<? extends IResource> resources) {
    List<String> paths = new ArrayList<>();
    for (IResource resource : resources) {
      paths.add(VitestLaunchSettings.toPath(resource));
    }
    return paths;
  }

  private static List<IResource> resources(ISelection selection) {
    List<IResource> resources = new ArrayList<>();
    if (selection instanceof IStructuredSelection structured) {
      for (Object element : structured) {
        IResource resource = Adapters.adapt(element, IResource.class);
        if (resource != null && resource.getLocation() != null && !resources.contains(resource)) {
          resources.add(resource);
        }
      }
    }
    return resources;
  }

  @Override
  public ILaunchConfiguration[] getLaunchConfigurations(ISelection selection) {
    return matchingConfigurations(resources(selection), null);
  }

  @Override
  public ILaunchConfiguration[] getLaunchConfigurations(IEditorPart editor) {
    IResource resource = getLaunchableResource(editor);
    return resource == null ? null : matchingConfigurations(List.of(resource), selectorAtCursor(editor));
  }

  /** The existing configuration of the resources, null to let the launch create one. */
  private static ILaunchConfiguration[] matchingConfigurations(List<IResource> resources, TestSelector selector) {
    if (resources.isEmpty()) {
      return null;
    }
    try {
      ILaunchConfiguration configuration = findConfiguration(resources, selector);
      return configuration == null ? null : new ILaunchConfiguration[] { configuration };
    } catch (CoreException e) {
      Activator.log(e.getStatus());
      return null;
    }
  }

  @Override
  public IResource getLaunchableResource(ISelection selection) {
    List<IResource> resources = resources(selection);
    return resources.isEmpty() ? null : resources.get(0);
  }

  @Override
  public IResource getLaunchableResource(IEditorPart editor) {
    IEditorInput input = editor == null ? null : editor.getEditorInput();
    IFile file = input == null ? null : Adapters.adapt(input, IFile.class);
    return file == null || file.getLocation() == null ? null : file;
  }

  private static ILaunchManager getManager() {
    return DebugPlugin.getDefault().getLaunchManager();
  }

  private static ILaunchConfigurationType getType() {
    return Objects.requireNonNull(getManager().getLaunchConfigurationType(VitestLaunchConstants.LAUNCH_CONFIGURATION_TYPE));
  }

  private static void showError(String message) {
    Display display = Display.getDefault();
    display.asyncExec(() -> MessageDialog.openError(display.getActiveShell(), "EVitest", message));
  }
}
