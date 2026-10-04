package org.eclipse.evitest;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.URIUtil;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.ImageRegistry;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public class Activator extends AbstractUIPlugin {

  public static final String PLUGIN_ID = "org.eclipse.evitest";

  public static final String IMG_VITEST = "icons/evitest.png";

  private static Activator plugin;

  @Override
  public void start(BundleContext context) throws Exception {
    super.start(context);
    plugin = this;
  }

  @Override
  public void stop(BundleContext context) throws Exception {
    plugin = null;
    super.stop(context);
  }

  public static Activator getDefault() {
    return plugin;
  }

  @Override
  protected void initializeImageRegistry(ImageRegistry registry) {
    for (String path : new String[] { IMG_VITEST }) {
      registry.put(path, ImageDescriptor.createFromURL(getBundle().getEntry(path)));
    }
  }

  public static ImageDescriptor getImageDescriptor(String path) {
    return getDefault().getImageRegistry().getDescriptor(path);
  }

  /** The reporter of EVitest for Vitest, extracted from the bundle if it is a jar. */
  public static File getReporterFile() throws IOException {
    return new File(getReporterFolder(), "evitest-reporter.mjs");
  }

  /** The folder of the reporters of EVitest (they require each other), extracted from the bundle if it is a jar. */
  public static File getReporterFolder() throws IOException {
    URL url = getDefault().getBundle().getEntry("reporter/");
    if (url == null) {
      throw new IOException("The reporters of EVitest are missing from their bundle");
    }
    try {
      // URIUtil: the URL of FileLocator is not encoded (spaces), and its path starts with a slash on Windows (/C:/).
      return URIUtil.toFile(URIUtil.toURI(FileLocator.toFileURL(url)));
    } catch (URISyntaxException e) {
      throw new IOException(e);
    }
  }

  public static IStatus error(String message, Throwable exception) {
    return new Status(IStatus.ERROR, PLUGIN_ID, message, exception);
  }

  public static void log(Throwable exception) {
    log(error(exception.getMessage() == null ? exception.toString() : exception.getMessage(), exception));
  }

  public static void log(IStatus status) {
    Activator activator = getDefault();
    if (activator != null) {
      activator.getLog().log(status);
    }
  }
}
