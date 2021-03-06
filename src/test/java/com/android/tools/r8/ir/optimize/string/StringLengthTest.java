// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.optimize.string;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import com.android.tools.r8.D8TestRunResult;
import com.android.tools.r8.ForceInline;
import com.android.tools.r8.NeverInline;
import com.android.tools.r8.R8TestRunResult;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.TestRunResult;
import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.android.tools.r8.utils.codeinspector.InstructionSubject;
import com.android.tools.r8.utils.codeinspector.MethodSubject;
import com.google.common.collect.Streams;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

class StringLengthTestMain {

  @ForceInline
  static String simpleInlinable() {
    return "Shared";
  }

  @NeverInline
  static int npe() {
    String n = null;
    // Cannot be computed at compile time.
    return n.length();
  }

  public static void main(String[] args) {
    String s1 = "GONE";
    // Can be computed at compile time: constCount++
    System.out.println(s1.length());

    String s2 = simpleInlinable();
    // Depends on inlining: constCount++
    System.out.println(s2.length());
    String s3 = simpleInlinable();
    System.out.println(s3);

    String s4 = "Another_shared";
    // Can be computed at compile time: constCount++
    System.out.println(s4.length());
    System.out.println(s4);

    String s5 = "\uD800\uDC00";  // U+10000
    // Can be computed at compile time: constCount++
    System.out.println(s5.length());
    // Even reusable: should not increase any counts.
    System.out.println(s5.codePointCount(0, s5.length()));
    System.out.println(s5);

    // Make sure this is not optimized in DEBUG mode.
    int l = "ABC".length();
    System.out.println(l);

    try {
      npe();
    } catch (NullPointerException npe) {
      // expected
    }
  }
}

@RunWith(Parameterized.class)
public class StringLengthTest extends TestBase {
  private static final String JAVA_OUTPUT = StringUtils.lines(
      "4",
      "6",
      "Shared",
      "14",
      "Another_shared",
      "2",
      "1",
      "𐀀", // Different output in Windows.
      "3"
  );
  private static final Class<?> MAIN = StringLengthTestMain.class;

  @Parameterized.Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimes().build();
  }

  private final TestParameters parameters;

  public StringLengthTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Test
  public void testJVMOutput() throws Exception {
    assumeTrue(
        "Only run JVM reference once (for CF backend)",
        parameters.getBackend() == Backend.CF);
    // TODO(b/119097175)
    if (!ToolHelper.isWindows()) {
      testForJvm()
          .addTestClasspath()
          .run(parameters.getRuntime(), MAIN)
          .assertSuccessWithOutput(JAVA_OUTPUT);
    }
  }

  private static boolean isStringLength(DexMethod method) {
    return method.toSourceString().equals("int java.lang.String.length()");
  }

  private long countStringLength(MethodSubject method) {
    return Streams.stream(method.iterateInstructions(instructionSubject -> {
      if (instructionSubject.isInvoke()) {
        return isStringLength(instructionSubject.getMethod());
      }
      return false;
    })).count();
  }

  private long countNonZeroConstNumber(MethodSubject method) {
    return Streams.stream(method.iterateInstructions(InstructionSubject::isConstNumber)).count()
        - Streams.stream(method.iterateInstructions(instr -> instr.isConstNumber(0))).count();
  }

  private void test(
      TestRunResult result, int expectedStringLengthCount, int expectedConstNumberCount)
      throws Exception {
    CodeInspector codeInspector = result.inspector();
    ClassSubject mainClass = codeInspector.clazz(MAIN);
    MethodSubject mainMethod = mainClass.mainMethod();
    assertThat(mainMethod, isPresent());
    assertEquals(expectedStringLengthCount, countStringLength(mainMethod));
    assertEquals(expectedConstNumberCount, countNonZeroConstNumber(mainMethod));
  }

  @Test
  public void testD8() throws Exception {
    assumeTrue("Only run D8 for Dex backend", parameters.getBackend() == Backend.DEX);

    D8TestRunResult result =
        testForD8()
            .release()
            .addProgramClasses(MAIN)
            .setMinApi(parameters.getRuntime())
            .run(parameters.getRuntime(), MAIN);
    // TODO(b/119097175)
    if (!ToolHelper.isWindows()) {
      result.assertSuccessWithOutput(JAVA_OUTPUT);
    }
    test(result, 1, 4);

    result =
        testForD8()
            .debug()
            .addProgramClasses(MAIN)
            .setMinApi(parameters.getRuntime())
            .run(parameters.getRuntime(), MAIN);
    // TODO(b/119097175)
    if (!ToolHelper.isWindows()) {
      result.assertSuccessWithOutput(JAVA_OUTPUT);
    }
    test(result, 6, 0);
  }

  @Test
  public void testR8() throws Exception {
    R8TestRunResult result =
        testForR8(parameters.getBackend())
            .addProgramClasses(MAIN)
            .enableInliningAnnotations()
            .addKeepMainRule(MAIN)
            .setMinApi(parameters.getRuntime())
            .run(parameters.getRuntime(), MAIN);
    // TODO(b/119097175)
    if (!ToolHelper.isWindows()) {
      result.assertSuccessWithOutput(JAVA_OUTPUT);
    }
    test(result, 0, parameters.getBackend() == Backend.DEX ? 5 : 6);
  }
}
