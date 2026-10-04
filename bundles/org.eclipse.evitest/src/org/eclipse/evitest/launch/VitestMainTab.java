package org.eclipse.evitest.launch;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.ui.AbstractLaunchConfigurationTab;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.StringVariableSelectionDialog;
import org.eclipse.evitest.Activator;
import org.eclipse.evitest.core.TestSelector;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.dialogs.ResourceSelectionDialog;
import org.eclipse.unittest.ui.ConfigureViewerSupport;

/**
 * The main tab of the Vitest launch configurations: what runs, where, and how.
 */
public class VitestMainTab extends AbstractLaunchConfigurationTab {

  private Text projectText;
  private Text pathsText;
  private Text testsText;
  private Text patternText;
  private Text rootText;
  private Text nodeText;
  private Text argumentsText;
  private Button updateSnapshotsButton;

  /** The selectors read from the configuration and their labels, kept as long as the labels are not edited. */
  private List<String> selectors = List.of();
  private String selectorLabels = "";

  @Override
  public void createControl(Composite parent) {
    Composite composite = new Composite(parent, SWT.NONE);
    GridLayoutFactory.swtDefaults().numColumns(1).applyTo(composite);
    setControl(composite);

    Group tests = group(composite, "Tests", 3);
    projectText = labeledText(tests, "&Project:", SWT.SINGLE);
    button(tests, "&Browse...", this::chooseProject);
    pathsText = labeledText(tests, "&Files and folders:", SWT.MULTI | SWT.V_SCROLL);
    ((GridData) pathsText.getLayoutData()).heightHint = 50;
    pathsText.setToolTipText("One workspace path per line. All the tests of the folder of Vitest when empty.");
    button(tests, "&Add...", this::addPaths);
    testsText = labeledText(tests, "&Tests:", SWT.MULTI | SWT.V_SCROLL);
    ((GridData) testsText.getLayoutData()).heightHint = 40;
    testsText.setToolTipText("The suites and the tests to run, one per line: suite > nested suite > test. "
        + "All the tests of the files when empty.");
    new Label(tests, SWT.NONE);
    patternText = labeledText(tests, "Test &name pattern:", SWT.SINGLE);
    patternText.setToolTipText("A regular expression on the full names of the tests (--testNamePattern), "
        + "used when no test is chosen above.");
    new Label(tests, SWT.NONE);
    updateSnapshotsButton = new Button(tests, SWT.CHECK);
    updateSnapshotsButton.setText("&Update the snapshots (--update)");
    GridDataFactory.fillDefaults().span(3, 1).applyTo(updateSnapshotsButton);
    updateSnapshotsButton.addListener(SWT.Selection, e -> updateLaunchConfigurationDialog());

    Group environment = group(composite, "Vitest", 3);
    rootText = labeledText(environment, "&Folder of Vitest:", SWT.SINGLE);
    rootText.setMessage("Found from the tests: the folder of their vitest.config, vite.config or package.json");
    Composite rootButtons = new Composite(environment, SWT.NONE);
    GridLayoutFactory.fillDefaults().numColumns(2).applyTo(rootButtons);
    button(rootButtons, "Br&owse...", this::chooseRoot);
    button(rootButtons, "&Variables...", () -> insertVariable(rootText));
    nodeText = labeledText(environment, "No&de.js:", SWT.SINGLE);
    nodeText.setMessage("The one of the preferences, or the one found on the PATH");
    button(environment, "B&rowse...", this::chooseNode);
    argumentsText = labeledText(environment, "&Arguments:", SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
    ((GridData) argumentsText.getLayoutData()).heightHint = 40;
    argumentsText.setToolTipText("Additional arguments of Vitest, for instance --project=unit or --bail=1");
    button(environment, "Var&iables...", () -> insertVariable(argumentsText));
  }

  private static Group group(Composite parent, String text, int columns) {
    Group group = new Group(parent, SWT.NONE);
    group.setText(text);
    GridLayoutFactory.swtDefaults().numColumns(columns).applyTo(group);
    GridDataFactory.fillDefaults().grab(true, false).applyTo(group);
    return group;
  }

  private Text labeledText(Composite parent, String label, int style) {
    Label labelControl = new Label(parent, SWT.NONE);
    labelControl.setText(label);
    GridDataFactory.swtDefaults().align(SWT.BEGINNING, (style & SWT.MULTI) != 0 ? SWT.BEGINNING : SWT.CENTER)
        .applyTo(labelControl);
    Text text = new Text(parent, SWT.BORDER | style);
    GridDataFactory.fillDefaults().grab(true, false).hint(200, SWT.DEFAULT).applyTo(text);
    text.addModifyListener(e -> updateLaunchConfigurationDialog());
    return text;
  }

  private static Button button(Composite parent, String text, Runnable action) {
    Button button = new Button(parent, SWT.PUSH);
    button.setText(text);
    GridDataFactory.swtDefaults().align(SWT.FILL, SWT.BEGINNING).applyTo(button);
    button.addListener(SWT.Selection, e -> action.run());
    return button;
  }

  private void chooseProject() {
    ElementListSelectionDialog dialog = new ElementListSelectionDialog(getShell(), new LabelProvider() {
      @Override
      public String getText(Object element) {
        return ((IProject) element).getName();
      }
    });
    dialog.setTitle("Project");
    dialog.setMessage("Choose the project of the tests:");
    dialog.setElements(Arrays.stream(ResourcesPlugin.getWorkspace().getRoot().getProjects())
        .filter(IProject::isOpen).toArray());
    if (dialog.open() == Window.OK && dialog.getFirstResult() instanceof IProject project) {
      projectText.setText(project.getName());
    }
  }

  private void addPaths() {
    IResource root = ResourcesPlugin.getWorkspace().getRoot();
    String project = projectText.getText().trim();
    if (!project.isEmpty() && ResourcesPlugin.getWorkspace().getRoot().getProject(project).exists()) {
      root = ResourcesPlugin.getWorkspace().getRoot().getProject(project);
    }
    ResourceSelectionDialog dialog = new ResourceSelectionDialog(getShell(), root, "Choose the test files and folders:");
    if (dialog.open() == Window.OK && dialog.getResult() != null) {
      List<String> paths = new ArrayList<>(lines(pathsText.getText()));
      for (Object element : dialog.getResult()) {
        if (element instanceof IResource resource) {
          String path = VitestLaunchSettings.toPath(resource);
          if (!paths.contains(path)) {
            paths.add(path);
          }
        }
      }
      pathsText.setText(String.join("\n", paths));
    }
  }

  private void chooseRoot() {
    DirectoryDialog dialog = new DirectoryDialog(getShell());
    dialog.setMessage("Choose the folder where Vitest runs (the folder of its configuration):");
    String folder = dialog.open();
    if (folder != null) {
      rootText.setText(folder);
    }
  }

  private void chooseNode() {
    FileDialog dialog = new FileDialog(getShell(), SWT.OPEN);
    dialog.setText("Node.js");
    String file = dialog.open();
    if (file != null) {
      nodeText.setText(file);
    }
  }

  private void insertVariable(Text text) {
    StringVariableSelectionDialog dialog = new StringVariableSelectionDialog(getShell());
    if (dialog.open() == Window.OK && dialog.getVariableExpression() != null) {
      text.insert(dialog.getVariableExpression());
    }
  }

  private static List<String> lines(String text) {
    List<String> lines = new ArrayList<>();
    for (String line : text.split("\\R")) {
      if (!line.isBlank()) {
        lines.add(line.trim());
      }
    }
    return lines;
  }

  @Override
  public void setDefaults(ILaunchConfigurationWorkingCopy configuration) {
    IResource resource = DebugUITools.getSelectedResource();
    if (resource != null && resource.getProject() != null) {
      configuration.setAttribute(VitestLaunchConstants.ATTR_PROJECT, resource.getProject().getName());
    }
    new ConfigureViewerSupport(VitestLaunchConstants.VIEW_SUPPORT).apply(configuration);
  }

  @Override
  public void initializeFrom(ILaunchConfiguration configuration) {
    try {
      projectText.setText(configuration.getAttribute(VitestLaunchConstants.ATTR_PROJECT, ""));
      pathsText.setText(String.join("\n",
          configuration.getAttribute(VitestLaunchConstants.ATTR_PATHS, Collections.emptyList())));
      selectors = configuration.getAttribute(VitestLaunchConstants.ATTR_SELECTORS, Collections.emptyList());
      List<String> labels = new ArrayList<>();
      for (String json : selectors) {
        TestSelector selector = TestSelector.fromJson(json);
        if (selector != null) {
          labels.add(selector.label());
        }
      }
      selectorLabels = String.join("\n", labels);
      testsText.setText(selectorLabels);
      patternText.setText(configuration.getAttribute(VitestLaunchConstants.ATTR_NAME_PATTERN, ""));
      updateSnapshotsButton.setSelection(configuration.getAttribute(VitestLaunchConstants.ATTR_UPDATE_SNAPSHOTS, false));
      rootText.setText(configuration.getAttribute(VitestLaunchConstants.ATTR_ROOT, ""));
      nodeText.setText(configuration.getAttribute(VitestLaunchConstants.ATTR_NODE, ""));
      argumentsText.setText(configuration.getAttribute(VitestLaunchConstants.ATTR_ARGUMENTS, ""));
    } catch (CoreException e) {
      Activator.log(e.getStatus());
    }
  }

  @Override
  public void performApply(ILaunchConfigurationWorkingCopy configuration) {
    configuration.setAttribute(VitestLaunchConstants.ATTR_PROJECT, emptyToNull(projectText.getText()));
    List<String> paths = lines(pathsText.getText());
    configuration.setAttribute(VitestLaunchConstants.ATTR_PATHS, paths.isEmpty() ? null : paths);
    List<String> newSelectors;
    if (testsText.getText().equals(selectorLabels)) {
      newSelectors = selectors;
    } else {
      // Edited by the user: each line is a suite or a test (a suite selector matches both).
      newSelectors = new ArrayList<>();
      for (String line : lines(testsText.getText())) {
        List<String> names = Arrays.stream(line.split(" > ")).map(String::trim).toList();
        newSelectors.add(TestSelector.suite(names).toJson());
      }
    }
    configuration.setAttribute(VitestLaunchConstants.ATTR_SELECTORS, newSelectors.isEmpty() ? null : newSelectors);
    configuration.setAttribute(VitestLaunchConstants.ATTR_NAME_PATTERN, emptyToNull(patternText.getText()));
    configuration.setAttribute(VitestLaunchConstants.ATTR_UPDATE_SNAPSHOTS, updateSnapshotsButton.getSelection());
    configuration.setAttribute(VitestLaunchConstants.ATTR_ROOT, emptyToNull(rootText.getText()));
    configuration.setAttribute(VitestLaunchConstants.ATTR_NODE, emptyToNull(nodeText.getText()));
    configuration.setAttribute(VitestLaunchConstants.ATTR_ARGUMENTS, emptyToNull(argumentsText.getText()));
    List<IResource> resources = new ArrayList<>();
    for (String path : paths) {
      IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(path);
      if (resource != null) {
        resources.add(resource);
      }
    }
    configuration.setMappedResources(resources.isEmpty() ? null : resources.toArray(IResource[]::new));
    new ConfigureViewerSupport(VitestLaunchConstants.VIEW_SUPPORT).apply(configuration);
  }

  private static String emptyToNull(String text) {
    return text == null || text.isBlank() ? null : text.trim();
  }

  @Override
  public boolean isValid(ILaunchConfiguration configuration) {
    setErrorMessage(null);
    String project = projectText.getText().trim();
    if (!project.isEmpty() && !ResourcesPlugin.getWorkspace().getRoot().getProject(project).exists()) {
      setErrorMessage("The project " + project + " does not exist.");
      return false;
    }
    for (String path : lines(pathsText.getText())) {
      File file = VitestLaunchSettings.toFile(path);
      if (file == null || !file.exists()) {
        setErrorMessage("The file or folder " + path + " does not exist.");
        return false;
      }
    }
    if (project.isEmpty() && lines(pathsText.getText()).isEmpty() && rootText.getText().isBlank()) {
      setErrorMessage("Choose the project, the tests or the folder of Vitest.");
      return false;
    }
    return true;
  }

  @Override
  public String getName() {
    return "Vitest";
  }

  @Override
  public Image getImage() {
    return Activator.getDefault().getImageRegistry().get(Activator.IMG_VITEST);
  }

  @Override
  public String getId() {
    return Activator.PLUGIN_ID + ".mainTab";
  }
}
