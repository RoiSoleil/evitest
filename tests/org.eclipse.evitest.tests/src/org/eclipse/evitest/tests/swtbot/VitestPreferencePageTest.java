package org.eclipse.evitest.tests.swtbot;

import static org.eclipse.swtbot.swt.finder.matchers.WidgetMatcherFactory.allOf;
import static org.eclipse.swtbot.swt.finder.matchers.WidgetMatcherFactory.widgetOfType;
import static org.eclipse.swtbot.swt.finder.matchers.WidgetMatcherFactory.withRegex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.eclipse.evitest.Preferences;
import org.eclipse.evitest.core.TestFilePatterns;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swtbot.swt.finder.finders.UIThreadRunnable;
import org.eclipse.swtbot.swt.finder.waits.Conditions;
import org.eclipse.swtbot.swt.finder.waits.WaitForObjectCondition;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotLabel;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotShell;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotText;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Window > Preferences > EVitest.
 */
class VitestPreferencePageTest extends SwtBotTest {

  private static final String PAGE = "org.eclipse.evitest.preferencePage";

  @AfterEach
  void restoreThePreferences() {
    IPreferenceStore store = Preferences.store();
    for (String key : new String[] { Preferences.NODE_PATH, Preferences.TEST_FILES, Preferences.ARGUMENTS,
        Preferences.COLORS, Preferences.CODE_MININGS }) {
      store.setToDefault(key);
    }
  }

  private static SWTBotShell openPreferences() {
    UIThreadRunnable.asyncExec(() -> {
      PreferenceDialog dialog = PreferencesUtil.createPreferenceDialogOn(
          PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(), PAGE, new String[] { PAGE }, null);
      dialog.open();
    });
    // "Preferences (Filtered)": the dialog shows only the page of EVitest.
    WaitForObjectCondition<Shell> preferences = Conditions.waitForShell(withRegex("Preferences.*"));
    bot.waitUntil(preferences);
    SWTBotShell shell = new SWTBotShell(preferences.get(0));
    shell.activate();
    return shell;
  }

  @Test
  void showsTheDefaultPreferences() {
    SWTBotShell shell = openPreferences();
    Label description = shell.bot().widget(allOf(widgetOfType(Label.class), withRegex("(?s)Run the Vitest tests.*")));
    String text = new SWTBotLabel(description).getText();
    assertTrue(text.contains("Node.js found: "), text);
    assertEquals("", shell.bot().textWithLabel("Node.js:").getText());
    assertEquals(TestFilePatterns.DEFAULT, shell.bot().textWithLabel("Test files:").getText());
    assertEquals("", shell.bot().textWithLabel("Additional arguments of Vitest:").getText());
    assertTrue(shell.bot().checkBox("Colors in the console").isChecked());
    assertTrue(shell.bot().checkBox("Show 'Run | Debug' above the tests in the editors").isChecked());
    shell.bot().button("Cancel").click();
    bot.waitUntil(Conditions.shellCloses(shell));
  }

  @Test
  void savesThePreferences() {
    SWTBotShell shell = openPreferences();
    shell.bot().textWithLabel("Test files:").setText("*.unit.ts, *.e2e.{js,ts}");
    shell.bot().textWithLabel("Additional arguments of Vitest:").setText("--bail=1");
    shell.bot().checkBox("Colors in the console").deselect();
    shell.bot().checkBox("Show 'Run | Debug' above the tests in the editors").deselect();
    shell.bot().button("Apply and Close").click();
    bot.waitUntil(Conditions.shellCloses(shell));

    assertEquals("*.unit.ts, *.e2e.{js,ts}", Preferences.getString(Preferences.TEST_FILES));
    assertTrue(Preferences.testFilePatterns().matches("math.e2e.js"));
    assertFalse(Preferences.testFilePatterns().matches("math.test.ts"));
    assertEquals("--bail=1", Preferences.getString(Preferences.ARGUMENTS));
    assertFalse(Preferences.getBoolean(Preferences.COLORS));
    assertFalse(Preferences.getBoolean(Preferences.CODE_MININGS));

    // The page shows the saved preferences.
    shell = openPreferences();
    assertEquals("*.unit.ts, *.e2e.{js,ts}", shell.bot().textWithLabel("Test files:").getText());
    assertFalse(shell.bot().checkBox("Colors in the console").isChecked());
    shell.bot().button("Cancel").click();
    bot.waitUntil(Conditions.shellCloses(shell));
  }

  @Test
  void restoresTheDefaults() {
    Preferences.store().setValue(Preferences.TEST_FILES, "*.unit.ts");
    Preferences.store().setValue(Preferences.COLORS, false);
    SWTBotShell shell = openPreferences();
    shell.bot().button("Restore Defaults").click();
    assertEquals(TestFilePatterns.DEFAULT, shell.bot().textWithLabel("Test files:").getText());
    assertTrue(shell.bot().checkBox("Colors in the console").isChecked());
    shell.bot().button("Apply and Close").click();
    bot.waitUntil(Conditions.shellCloses(shell));
    assertEquals(TestFilePatterns.DEFAULT, Preferences.getString(Preferences.TEST_FILES));
    assertTrue(Preferences.getBoolean(Preferences.COLORS));
  }

  @Test
  void refusesAMissingNodeJs() {
    SWTBotShell shell = openPreferences();
    // The field is validated on the key strokes.
    SWTBotText node = shell.bot().textWithLabel("Node.js:");
    node.setText(new File("missing-nod").getAbsolutePath());
    node.typeText("e");
    assertFalse(shell.bot().button("Apply and Close").isEnabled());
    // Blank: Node.js is searched on the PATH.
    node.setText("");
    node.typeText(" ");
    assertTrue(shell.bot().button("Apply and Close").isEnabled());
    shell.bot().button("Cancel").click();
    bot.waitUntil(Conditions.shellCloses(shell));
  }
}
