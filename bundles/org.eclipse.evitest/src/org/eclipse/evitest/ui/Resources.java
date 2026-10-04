package org.eclipse.evitest.ui;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.evitest.Activator;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

/** Finds the files of the tests in the workspace and opens them. */
public final class Resources {

  private Resources() {
  }

  /** The file of the workspace at this location, null if it is not in the workspace. */
  public static IFile findFile(String location) {
    if (location == null) {
      return null;
    }
    URI uri = new File(location).getAbsoluteFile().toURI();
    IFile best = null;
    for (IFile file : ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(uri)) {
      if (file.exists() && (best == null || file.getFullPath().segmentCount() < best.getFullPath().segmentCount())) {
        // The shortest path: the file in its own project rather than in a project nesting it.
        best = file;
      }
    }
    return best;
  }

  /** The location of a resource, null if it is not on the file system. */
  public static File toFile(IResource resource) {
    return resource == null || resource.getLocation() == null ? null : resource.getLocation().toFile();
  }

  /**
   * Opens the file in an editor and selects a position.
   *
   * @param line the line, 1 based, or 0 to only open the file
   * @param column the column, 1 based, or 0 for the start of the line
   * @param length the length of the selection
   * @return the editor, null if it could not be opened
   */
  public static IEditorPart open(String location, int line, int column, int length) {
    IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
    IWorkbenchPage page = window == null ? null : window.getActivePage();
    if (page == null || location == null) {
      return null;
    }
    try {
      IEditorPart editor;
      IFile file = findFile(location);
      if (file != null) {
        editor = IDE.openEditor(page, file, true);
      } else {
        IFileStore store = EFS.getLocalFileSystem().getStore(new File(location).toURI());
        if (!store.fetchInfo().exists()) {
          return null;
        }
        editor = IDE.openEditorOnFileStore(page, store);
      }
      if (line > 0) {
        select(editor, line, column, length);
      }
      return editor;
    } catch (PartInitException e) {
      Activator.log(e.getStatus());
      return null;
    }
  }

  private static void select(IEditorPart editor, int line, int column, int length) {
    ITextEditor textEditor = editor == null ? null : editor.getAdapter(ITextEditor.class);
    if (textEditor == null) {
      return;
    }
    IDocumentProvider provider = textEditor.getDocumentProvider();
    IDocument document = provider == null ? null : provider.getDocument(textEditor.getEditorInput());
    if (document == null) {
      return;
    }
    try {
      int lineIndex = Math.min(line, document.getNumberOfLines()) - 1;
      IRegion region = document.getLineInformation(lineIndex);
      int offset = region.getOffset() + Math.max(0, Math.min(column - 1, region.getLength()));
      int selectionLength = Math.max(0, Math.min(length, region.getOffset() + region.getLength() - offset));
      textEditor.selectAndReveal(offset, selectionLength);
    } catch (BadLocationException e) {
      // The file changed since the run: it stays open where it is.
    }
  }

  /** The content of a file (UTF-8), null if it cannot be read. */
  public static String read(String location) {
    try {
      return Files.readString(new File(location).toPath(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return null;
    }
  }
}
