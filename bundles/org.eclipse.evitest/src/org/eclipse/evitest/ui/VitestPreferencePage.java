package org.eclipse.evitest.ui;

import java.io.File;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.evitest.Activator;
import org.eclipse.evitest.Preferences;
import org.eclipse.evitest.core.NodeLocator;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.FileFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

/** Window > Preferences > EVitest. */
public class VitestPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

  public VitestPreferencePage() {
    super(GRID);
  }

  @Override
  public void init(IWorkbench workbench) {
    setPreferenceStore(Activator.getDefault().getPreferenceStore());
    File node = NodeLocator.find(DebugPlugin.getDefault().getLaunchManager().getNativeEnvironmentCasePreserved());
    setDescription("Run the JavaScript tests in the Unit Test view: Run As > JavaScript Test on the test files, the "
        + "folders and the projects, or Run | Debug above the tests in the editors. Vitest, Jest, Mocha, Jasmine, "
        + "Playwright Test, Deno, Bun and node:test are detected from the tests.\n\nNode.js found: "
        + (node == null ? "none, set it below." : node.getAbsolutePath()));
  }

  @Override
  protected void createFieldEditors() {
    FileFieldEditor node = new FileFieldEditor(Preferences.NODE_PATH, "&Node.js:", true,
        FileFieldEditor.VALIDATE_ON_KEY_STROKE, getFieldEditorParent());
    node.setEmptyStringAllowed(true);
    addField(node);
    FileFieldEditor bun = new FileFieldEditor(Preferences.BUN_PATH, "&Bun:", true,
        FileFieldEditor.VALIDATE_ON_KEY_STROKE, getFieldEditorParent());
    bun.setEmptyStringAllowed(true);
    bun.getTextControl(getFieldEditorParent()).setMessage("Found on the PATH or in ~/.bun/bin if empty");
    addField(bun);
    FileFieldEditor deno = new FileFieldEditor(Preferences.DENO_PATH, "&Deno:", true,
        FileFieldEditor.VALIDATE_ON_KEY_STROKE, getFieldEditorParent());
    deno.setEmptyStringAllowed(true);
    deno.getTextControl(getFieldEditorParent()).setMessage("Found on the PATH or in ~/.deno/bin if empty");
    addField(deno);
    StringFieldEditor testFiles = new StringFieldEditor(Preferences.TEST_FILES, "&Test files:", getFieldEditorParent());
    testFiles.getTextControl(getFieldEditorParent())
        .setToolTipText("The names of the test files: globs separated by commas, for instance *.test.ts, *.spec.ts");
    addField(testFiles);
    StringFieldEditor arguments = new StringFieldEditor(Preferences.ARGUMENTS, "Additional &arguments of Vitest:",
        getFieldEditorParent());
    arguments.getTextControl(getFieldEditorParent())
        .setToolTipText("Added to all the launches of Vitest, for instance --bail=1");
    addField(arguments);
    addField(new BooleanFieldEditor(Preferences.COLORS, "&Colors in the console", getFieldEditorParent()));
    addField(new BooleanFieldEditor(Preferences.CODE_MININGS,
        "&Show 'Run | Debug' above the tests in the editors",
        getFieldEditorParent()));
  }
}
