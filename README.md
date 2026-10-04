# <img src="docs/logo.png" width="40" align="top" alt=""> EVitest : Run the JavaScript tests in Eclipse, like the JUnit tests.

[![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/RoiSoleil/evitest/build.yml)](https://github.com/RoiSoleil/evitest/actions/workflows/build.yml)
[![codecov](https://codecov.io/gh/RoiSoleil/evitest/branch/main/graph/badge.svg)](https://codecov.io/gh/RoiSoleil/evitest)
[![GitHub](https://img.shields.io/github/license/RoiSoleil/evitest)](LICENSE)

Right click a test file, a folder or a project and *Run As > JavaScript Test*: the results arrive in the *Unit Test*
view of Eclipse, the one of JUnit, while the tests run. [Vitest](https://vitest.dev), [Jest](https://jestjs.io),
[Mocha](https://mochajs.org), [Jasmine](https://jasmine.github.io), [Playwright Test](https://playwright.dev),
[Deno](https://docs.deno.com/runtime/fundamentals/testing/), [Bun](https://bun.sh/docs/cli/test) and
[node:test](https://nodejs.org/api/test.html) are found from the tests.

# Features

- **The Unit Test view**: the tree of the files, the suites (`describe`) and the tests, the progress bar, the
  counters, the history of the runs, the failures only, the stack trace of the selected failure.
- **Run what you want**: a project, folders, test files (*Run As > JavaScript Test* or **Alt+Shift+X V**), or in an
  editor the test or the suite at the cursor (the whole file outside the tests).
- **Run | Debug above each test and suite** in the editors, with the state of its last run: ✓ passed, ✗ failed,
  ○ skipped, … running.
- **Rerun** a test, a suite or a file from the view, or **only the failed tests**, in Run or Debug mode.
- **Navigation**: a double click on a test opens it at its declaration; on a line of the stack trace, at that line
  (with the source maps of the framework: the line of the TypeScript source).
- **Compare** the expected and the actual values of a failed assertion (`toEqual`, `assert.deepStrictEqual`...) side
  by side.
- The errors outside the tests are shown too: a file which cannot be loaded, a failed `beforeAll`, the unhandled
  errors of the run.
- **Debug** the tests (**Alt+Shift+D V**) with the breakpoints of the editors: the Node.js debugger of
  [Wild Web Developer](https://marketplace.eclipse.org/content/wild-web-developer-html-css-javascript-typescript-nodejs-angular-json-yaml-kubernetes-xml)
  attaches to the tests.
- The console of the run shows the output of the framework, in colors, and its `file.test.ts:12:5` are links.
- Vitest 1 to 5, its projects (workspaces), the projects of Jest and of Playwright, monorepos (the framework hoisted in
  a parent `node_modules`), `.each` and `.for` tests, `describe(MyClass, ...)`.

## The frameworks

| Framework | Found from | Results | Debug | Selection of the tests |
|---|---|---|---|---|
| Vitest | `import ... from 'vitest'`, `vitest.config.*`, `vite.config.*`, `vitest` in package.json | While they run | Yes | Tests and suites |
| Jest | `@jest/globals`, `jest.config.*`, `jest` in package.json | While they run | Yes | Tests and suites |
| Mocha | `.mocharc.*`, `mocha` in package.json | While they run | Yes | Tests and suites |
| Jasmine | `spec/support/jasmine.json`, `jasmine` in package.json | While they run | Yes | Tests and suites |
| Playwright Test | `@playwright/test`, `playwright.config.*` | While they run | No | Tests and suites |
| node:test | `node:test`, a test script `node --test` | While they run | Yes (Node.js 22.8+) | Tests and suites |
| Bun | `bun:test`, `bunfig.toml`, `bun.lock`, a test script `bun test` | When they end | No | Tests and suites |
| Deno | `Deno.test`, `@std/testing`, `deno.json`, a test script `deno test` | When they end | No | Tests (`Deno.test`), with their steps |

Bun and Deno have no API for the reporters: EVitest reads their JUnit report, and the errors in their output, when
they end. Deno runs with `--allow-all` unless the arguments of the launch configuration have permissions.

# Requirements

- Eclipse 2024-06 or newer, Java 21.
- Node.js: found on the `PATH`, with nvm, Volta, Homebrew, fnm... or set in *Window > Preferences > EVitest*.
- The framework installed in the project (`npm install`): EVitest runs the one of `node_modules`. Bun and Deno: found
  on the `PATH`, in `~/.bun/bin` and `~/.deno/bin`, installed by npm, or set in the preferences.
- For the debugger only: Wild Web Developer.

# Update Site

You can find the latest build of EVitest here:

https://github.com/RoiSoleil/evitest/raw/update-site/latest/

# Usage

The framework of a test file is the one it imports (`vitest`, `@jest/globals`, `node:test`, `bun:test`,
`@playwright/test`, `Deno.test`); else the one configured in the nearest folder (`vitest.config.*`, `jest.config.*`,
`.mocharc.*`, `spec/support/jasmine.json`, `playwright.config.*`, `deno.json`, `bunfig.toml`); else the one of the
nearest `package.json` (its dependencies, its `jest` or `mocha` configuration, its `test` script). The tests run in the
folder of this configuration: the configuration of the project applies. The launch configurations
(*Run > Run Configurations... > JavaScript Test*) can change the framework and the folder, and choose:

- the project, the files and the folders, the tests (`suite > nested suite > test`, one per line);
- a test name pattern (on the full names of the tests), the update of the snapshots;
- the Node.js executable, additional arguments of the framework (`--project=unit`, `--bail=1`...), the environment.

*Window > Preferences > EVitest* has the executables of Node.js, Bun and Deno, the names of the test files
(`*.{test,spec}.{js,ts,...}`, `*Spec.js`, `*_test.ts`, `*-test.js` by default), additional arguments for all the
launches of Vitest, the colors of the console and the *Run | Debug* above the tests.

# How it works

EVitest runs the command line of the framework with its own reporter (in
[`bundles/org.eclipse.evitest/reporter`](bundles/org.eclipse.evitest/reporter)): the one of Vitest
([`evitest-reporter.mjs`](bundles/org.eclipse.evitest/reporter/evitest-reporter.mjs)), of Jest, Mocha, Jasmine,
Playwright Test and node:test, next to the reporter of the console. They send the events of the run (files collected,
tests started and ended, errors) as lines of JSON to a socket of Eclipse. For Bun and Deno, Eclipse turns their JUnit
report into the same events. They become the tests of the Unit Test view through its
`org.eclipse.unittest.ui.unittestViewSupport` extension point, the one of the JUnit tests of Eclipse since 4.20.

# Build

Requires JDK 21 and Maven 3.9 (the Apache distribution: the Maven packaged by some Linux distributions does not work
with Tycho).

```bash
mvn clean install   # update site in update-site/org.eclipse.evitest/target/repository
```

The end to end tests run the real frameworks of the projects of
[`tests/org.eclipse.evitest.tests/fixtures`](tests/org.eclipse.evitest.tests/fixtures), one per framework with the
same tests: run `npm ci` in `vitest`, `jest`, `mocha`, `jasmine` and `playwright` first, with Node.js, Bun and Deno on
the `PATH`, else the tests of the missing frameworks are skipped (`EVITEST_REQUIRE_FRAMEWORKS=true` makes them fail, as
on GitHub).

The [SWTBot](https://eclipse.dev/swtbot/) tests (`org.eclipse.evitest.tests.swtbot`) drive the workbench as a user:
the preference page, the launch configurations, *Run As > JavaScript Test* with each framework and the Unit Test view.
They need a display: `xvfb-run -a mvn clean install` on a server. A failed SWTBot test saves a screenshot in
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
