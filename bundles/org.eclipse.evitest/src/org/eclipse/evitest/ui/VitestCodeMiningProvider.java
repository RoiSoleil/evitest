package org.eclipse.evitest.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.evitest.Preferences;
import org.eclipse.evitest.core.JsTestScanner;
import org.eclipse.evitest.core.JsTestScanner.TestBlock;
import org.eclipse.evitest.core.TestSelector;
import org.eclipse.evitest.launch.VitestLaunchShortcut;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.codemining.AbstractCodeMiningProvider;
import org.eclipse.jface.text.codemining.CodeMiningReconciler;
import org.eclipse.jface.text.codemining.ICodeMining;
import org.eclipse.jface.text.codemining.LineHeaderCodeMining;
import org.eclipse.jface.text.source.ISourceViewerExtension5;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Shows "Run | Debug" above each test and suite of the editors of the test files, with the state of its last run:
 * ✓ passed, ✗ failed, ○ skipped, … running.
 */
public class VitestCodeMiningProvider extends AbstractCodeMiningProvider implements TestResults.Listener {

  private volatile ITextViewer viewer;
  private volatile String file;
  private volatile boolean listening;
  private volatile boolean updateScheduled;

  @Override
  public CompletableFuture<List<? extends ICodeMining>> provideCodeMinings(ITextViewer textViewer,
      IProgressMonitor monitor) {
    viewer = textViewer;
    ITextEditor editor = getAdapter(ITextEditor.class);
    IFile input = editor == null ? null : editor.getEditorInput().getAdapter(IFile.class);
    if (input == null || input.getLocation() == null || !Preferences.getBoolean(Preferences.CODE_MININGS)) {
      return CompletableFuture.completedFuture(List.of());
    }
    file = input.getLocation().toFile().getAbsolutePath();
    if (!listening) {
      listening = true;
      TestResults.addListener(this);
      installReconciler(textViewer);
    }
    IDocument document = textViewer.getDocument();
    if (document == null) {
      return CompletableFuture.completedFuture(List.of());
    }
    String source = document.get();
    return CompletableFuture.supplyAsync(() -> {
      List<ICodeMining> minings = new ArrayList<>();
      Set<Integer> lines = new HashSet<>();
      for (TestBlock block : JsTestScanner.flatten(JsTestScanner.scan(source))) {
        try {
          // One group per line: two tests declared on the same line would draw over each other. The lines are counted
          // in the copy of the document, the document may change meanwhile.
          if (!lines.add(Integer.valueOf(lineOf(source, block.getOffset())))) {
            continue;
          }
          TestSelector selector = block.toSelector();
          Position position = new Position(block.getOffset(), 1);
          String status = status(TestResults.get(file, selector));
          minings.add(new TestMining(position, this, status + "Run", event -> launch(input, selector,
              ILaunchManager.RUN_MODE)));
          minings.add(new TestMining(position, this, "Debug", event -> launch(input, selector,
              ILaunchManager.DEBUG_MODE)));
        } catch (BadLocationException e) {
          // The document changed: the next update shows the minings.
        }
      }
      return minings;
    });
  }

  private static int lineOf(String source, int offset) {
    int line = 0;
    for (int i = 0; i < offset; i++) {
      if (source.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }

  private static String status(TestResults.State state) {
    if (state == null) {
      return "";
    }
    return switch (state) {
      case PASSED -> "✓ ";
      case FAILED -> "✗ ";
      case SKIPPED -> "○ ";
      case RUNNING -> "… ";
    };
  }

  private static void launch(IFile file, TestSelector selector, String mode) {
    VitestLaunchShortcut.launch(List.of(file), selector, mode);
  }

  /** The minings follow the changes of the document. */
  private static void installReconciler(ITextViewer textViewer) {
    StyledText widget = textViewer.getTextWidget();
    if (widget == null || widget.isDisposed()) {
      return;
    }
    widget.getDisplay().asyncExec(() -> {
      if (!widget.isDisposed()) {
        CodeMiningReconciler reconciler = new CodeMiningReconciler();
        reconciler.install(textViewer);
        widget.addDisposeListener(e -> reconciler.uninstall());
      }
    });
  }

  @Override
  public void resultsChanged(String changedFile) {
    ITextViewer textViewer = viewer;
    String currentFile = file;
    if (textViewer == null || currentFile == null || !TestResults.key(currentFile).equals(changedFile)
        || updateScheduled) {
      return;
    }
    StyledText widget = textViewer.getTextWidget();
    if (widget == null || widget.isDisposed()) {
      return;
    }
    // The results of a run arrive test after test: one update for many of them.
    updateScheduled = true;
    widget.getDisplay().asyncExec(() -> widget.getDisplay().timerExec(150, () -> {
      updateScheduled = false;
      if (!widget.isDisposed() && textViewer instanceof ISourceViewerExtension5 extension) {
        extension.updateCodeMinings();
      }
    }));
  }

  @Override
  public void dispose() {
    TestResults.removeListener(this);
    listening = false;
    viewer = null;
    super.dispose();
  }

  private static final class TestMining extends LineHeaderCodeMining {
    TestMining(Position position, VitestCodeMiningProvider provider, String label, Consumer<MouseEvent> action)
        throws BadLocationException {
      super(position, provider, action);
      setLabel(label);
    }
  }
}
