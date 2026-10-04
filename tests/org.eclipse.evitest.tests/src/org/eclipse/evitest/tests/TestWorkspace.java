package org.eclipse.evitest.tests;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.evitest.core.TestFramework;
import org.eclipse.evitest.launch.VitestLaunchConstants;

/**
 * The projects and the launches of the SWTBot tests.
 */
public class TestWorkspace {

  public static IProject createProject(String name) throws CoreException {
    IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
    project.create(null);
    project.open(null);
    return project;
  }

  /**
   * The fixture of a framework (see {@link Fixtures}) as a project of the workspace, skipped if the framework is not
   * installed. Its .project is deleted by {@link #deleteProjects()}.
   */
  public static IProject createFixtureProject(String name, TestFramework framework) throws CoreException {
    File fixture = Fixtures.fixture(framework);
    IWorkspace workspace = ResourcesPlugin.getWorkspace();
    IProject project = workspace.getRoot().getProject(name);
    IProjectDescription description = workspace.newProjectDescription(name);
    description.setLocation(IPath.fromFile(fixture));
    project.create(description, null);
    project.open(null);
    // The files of the fixture, known by the workspace before the tests use them.
    project.refreshLocal(IResource.DEPTH_INFINITE, null);
    return project;
  }

  public static IFile createFile(IProject project, String path, String content) throws CoreException {
    IFile file = project.getFile(path);
    createFolders(file.getParent());
    file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true, null);
    return file;
  }

  private static void createFolders(IContainer container) throws CoreException {
    if (container instanceof IFolder folder && !folder.exists()) {
      createFolders(folder.getParent());
      folder.create(true, true, null);
    }
  }

  /** Deletes the projects; the content of the projects outside of the workspace, as the fixture, is kept. */
  public static void deleteProjects() throws CoreException {
    IPath workspace = ResourcesPlugin.getWorkspace().getRoot().getLocation();
    for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
      IPath location = project.getLocation();
      boolean inWorkspace = location == null || workspace.isPrefixOf(location);
      project.delete(inWorkspace, true, null);
      if (!inWorkspace) {
        location.append(IProjectDescription.DESCRIPTION_FILE_NAME).toFile().delete();
      }
    }
  }

  /** Terminates and removes the launches, deletes the Vitest launch configurations. */
  public static void deleteLaunches() throws CoreException {
    ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
    for (ILaunch launch : manager.getLaunches()) {
      if (!launch.isTerminated()) {
        launch.terminate();
      }
      manager.removeLaunch(launch);
    }
    for (ILaunchConfiguration configuration : manager
        .getLaunchConfigurations(manager.getLaunchConfigurationType(VitestLaunchConstants.LAUNCH_CONFIGURATION_TYPE))) {
      configuration.delete();
    }
  }

  public static ILaunchConfiguration findConfiguration(String name) throws CoreException {
    ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
    for (ILaunchConfiguration configuration : manager
        .getLaunchConfigurations(manager.getLaunchConfigurationType(VitestLaunchConstants.LAUNCH_CONFIGURATION_TYPE))) {
      if (configuration.getName().equals(name)) {
        return configuration;
      }
    }
    return null;
  }
}
