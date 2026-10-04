# <img src="docs/logo.png" width="40" align="top" alt=""> EVitest : Run the [Vitest](https://vitest.dev) tests in Eclipse, like the JUnit tests.

[![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/RoiSoleil/evitest/build.yml)](https://github.com/RoiSoleil/evitest/actions/workflows/build.yml)
[![codecov](https://codecov.io/gh/RoiSoleil/evitest/branch/main/graph/badge.svg)](https://codecov.io/gh/RoiSoleil/evitest)
[![GitHub](https://img.shields.io/github/license/RoiSoleil/evitest)](LICENSE)

Right click a test file, a folder or a project and *Run As > Vitest Test*: the results arrive in the *Unit Test*
view of Eclipse, the one of JUnit, while Vitest runs them.

# Features

- **The Unit Test view**: the tree of the files, the suites (`describe`) and the tests, the progress bar, the
  counters, the history of the runs, the failures only, the stack trace of the selected failure.
- **Run what you want**: a project, folders, test files (*Run As > Vitest Test* or **Alt+Shift+X V**), or in an editor
  the test or the suite at the cursor (the whole file outside the tests).
- **Run | Debug above each test and suite** in the editors, with the state of its last run: ✓ passed, ✗ failed,
  ○ skipped, … running.
- **Rerun** a test, a suite or a file from the view, or **only the failed tests**, in Run or Debug mode.
- **Navigation**: a double click on a test opens it at its declaration; on a line of the stack trace, at that line
  (with the source maps of Vitest: the line of the TypeScript source).
- **Compare** the expected and the actual values of a failed assertion (`toEqual`, `toBe`...) side by side.
- The errors outside the tests are shown too: a file which cannot be loaded, a failed `beforeAll`, the unhandled
  errors of the run.
- **Debug** the tests (**Alt+Shift+D V**) with the breakpoints of the editors: the Node.js debugger of
  [Wild Web Developer](https://marketplace.eclipse.org/content/wild-web-developer-html-css-javascript-typescript-nodejs-angular-json-yaml-kubernetes-xml)
  attaches to each test file.
- The console of the run shows the output of Vitest, in colors, and its `file.test.ts:12:5` are links.
- Vitest 1 to 5, its projects (workspaces), monorepos (Vitest hoisted in a parent `node_modules`), `.each` and
  `.for` tests, `describe(MyClass, ...)`.

# Requirements

- Eclipse 2024-06 or newer, Java 21.
- Node.js: found on the `PATH`, with nvm, Volta, Homebrew, fnm... or set in *Window > Preferences > EVitest*.
- Vitest installed in the project (`npm install`): EVitest runs the `node_modules/vitest` of the project.
- For the debugger only: Wild Web Developer.

# Update Site

You can find the latest build of EVitest here:

https://github.com/RoiSoleil/evitest/raw/update-site/latest/

# Usage

The folder where Vitest runs is the nearest folder of the tests with a `vitest.config.*`, a `vite.config.*` or a
`vitest.workspace.*`, else with a `package.json` depending on Vitest: the configuration of the project applies. The
launch configurations (*Run > Run Configurations... > Vitest*) can change it, and choose:

- the project, the files and the folders, the tests (`suite > nested suite > test`, one per line);
- a test name pattern (`--testNamePattern`), the update of the snapshots (`--update`);
- the Node.js executable, additional arguments of Vitest (`--project=unit`, `--bail=1`...), the environment.

*Window > Preferences > EVitest* has the Node.js executable, the names of the test files (`*.{test,spec}.{js,ts,...}`
by default), additional arguments for all the launches, the colors of the console and the *Run | Debug* above the
tests.

# How it works

EVitest runs `node node_modules/vitest/vitest.mjs run` with the `default` reporter (the console) and its own reporter
([`evitest-reporter.mjs`](bundles/org.eclipse.evitest/reporter/evitest-reporter.mjs)), which sends the events of the
run (files collected, tests started and ended, errors) as lines of JSON to a socket of Eclipse. They become the tests
of the Unit Test view through its `org.eclipse.unittest.ui.unittestViewSupport` extension point, the one of the JUnit
tests of Eclipse since 4.20.

# Build

Requires JDK 21 and Maven 3.9 (the Apache distribution: the Maven packaged by some Linux distributions does not work
with Tycho).

```bash
mvn clean install   # update site in update-site/org.eclipse.evitest/target/repository
```

The end to end tests run the real Vitest of [`tests/org.eclipse.evitest.tests/fixture`](tests/org.eclipse.evitest.tests/fixture):
run `npm ci` there first, with Node.js on the `PATH`, else they are skipped.

The [SWTBot](https://eclipse.dev/swtbot/) tests (`org.eclipse.evitest.tests.swtbot`) drive the workbench as a user:
the preference page, the launch configurations, *Run As > Vitest Test* and the Unit Test view. They need a display:
`xvfb-run -a mvn clean install` on a server. A failed SWTBot test saves a screenshot in
`tests/org.eclipse.evitest.tests/screenshots`.

JaCoCo measures the coverage of the plug-in by the tests; `tests/org.eclipse.evitest.coverage` writes the report in
`target/site/jacoco-aggregate` and GitHub Actions sends it to [Codecov](https://codecov.io/gh/RoiSoleil/evitest).

The icons are drawn by `tools/MakeIcon.java` (same drawing as `icons/evitest.svg`):

```bash
java tools/MakeIcon.java 16 bundles/org.eclipse.evitest/icons/evitest.png
java tools/MakeIcon.java 32 bundles/org.eclipse.evitest/icons/evitest@2x.png
```

# License

[Eclipse Public License, v2.0](http://www.eclipse.org/legal/epl-v20.html)
