/**
 * Copyright (C) 2018 GZoltar contributors.
 * 
 * This file is part of GZoltar.
 * 
 * GZoltar is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 * 
 * GZoltar is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License along with GZoltar. If
 * not, see <https://www.gnu.org/licenses/>.
 */
package com.gzoltar.cli.commands;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import org.kohsuke.args4j.Option;
import com.gzoltar.cli.Command;
import com.gzoltar.cli.test.TestMethod;
import com.gzoltar.cli.test.TestRunner;
import com.gzoltar.cli.test.TestTask;
import com.gzoltar.cli.test.junit.JUnitTestTask;
import com.gzoltar.cli.test.testng.TestNGTestTask;
import com.gzoltar.cli.utils.ClassType;

/**
 * The <code>runTestMethods</code> command.
 */
public class RunTestMethods extends Command {

  @Option(name = "--testMethods", usage = "file with list of test methods to run",
      metaVar = "<path>", required = true)
  private File testMethods = null;

  @Option(name = "--collectCoverage", usage = "collect coverage of each test method",
      metaVar = "<boolean>", required = false)
  private Boolean collectCoverage = false;

  @Override
  public String description() {
    return "Run test methods in isolation.";
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String name() {
    return "runTestMethods";
  }

  @Override
  public int execute(final PrintStream out, final PrintStream err) throws Exception {
    out.println("* " + this.description());

    if (!this.testMethods.exists() || !this.testMethods.canRead()) {
      throw new RuntimeException(this.testMethods + " does not exist or cannot be read");
    }

    final URL[] classpathURLs =
        ((URLClassLoader) Thread.currentThread().getContextClassLoader()).getURLs();

    try (BufferedReader br = new BufferedReader(new FileReader(this.testMethods))) {
      String line;
      while ((line = br.readLine()) != null) {
        String[] split = line.split(",");

        TestMethod testMethod = new TestMethod(ClassType.valueOf(split[0]), split[1]);
        TestTask testTask = null;

        switch (testMethod.getClassType()) {
          case JUNIT:
            testTask = new JUnitTestTask(classpathURLs, this.collectCoverage, testMethod);
            break;
          case TESTNG:
            testTask = new TestNGTestTask(classpathURLs, this.collectCoverage, testMethod);
            break;
          default:
            throw new RuntimeException(testMethod.getLongName() + " is not supported");
        }
        assert testTask != null;

        TestRunner.run(testTask);
        testTask = null;
      }
    }

    return 0;
  }
}
