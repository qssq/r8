// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.kotlin;

import com.android.tools.r8.KotlinTestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.ToolHelper.KotlinTargetVersion;
import com.android.tools.r8.utils.InternalOptions;
import com.google.common.collect.ImmutableList;
import java.util.Collection;
import java.util.function.Consumer;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ProcessKotlinStdlibTest extends KotlinTestBase {
  private final TestParameters parameters;

  public ProcessKotlinStdlibTest(TestParameters parameters, KotlinTargetVersion targetVersion) {
    super(targetVersion);
    this.parameters = parameters;
  }

  @Parameterized.Parameters(name = "{0} target: {1}")
  public static Collection<Object[]> data() {
    return buildParameters(
        getTestParameters().withAllRuntimes().build(), KotlinTargetVersion.values());
  }

  private void test(Collection<String> rules) throws Exception {
    test(rules, null);
  }

  private void test(
      Collection<String> rules, Consumer<InternalOptions> optionsConsumer) throws Exception {
    testForR8(parameters.getBackend())
        .addProgramFiles(ToolHelper.getKotlinStdlibJar())
        .addOptionsModification(optionsConsumer)
        .addKeepRules(rules)
        .compile();
  }

  @Test
  public void testAsIs() throws Exception {
    test(ImmutableList.of("-dontshrink", "-dontoptimize", "-dontobfuscate"));
  }

  @Test
  public void testDontShrinkAndDontOptimize() throws Exception {
    test(ImmutableList.of("-dontshrink", "-dontoptimize"));
  }

  @Ignore("b/129558497")
  @Test
  public void testDontShrinkAndDontOptimizeDifferently() throws Exception {
     test(
         ImmutableList.of("-keep,allowobfuscation class **.*Exception*"),
         o -> {
           o.enableTreeShaking = false;
           o.enableVerticalClassMerging = false;
         });
  }

  @Test
  public void testDontShrinkAndDontObfuscate() throws Exception {
    test(ImmutableList.of("-dontshrink", "-dontobfuscate"));
  }

  @Test
  public void testDontShrink() throws Exception {
    test(ImmutableList.of("-dontshrink"));
  }

  @Ignore("b/129558497")
  @Test
  public void testDontShrinkDifferently() throws Exception {
    test(
        ImmutableList.of("-keep,allowobfuscation class **.*Exception*"),
        o -> o.enableTreeShaking = false);
  }

  @Test
  public void testDontOptimize() throws Exception {
    test(ImmutableList.of("-dontoptimize"));
  }

  @Test
  public void testDontObfuscate() throws Exception {
    test(ImmutableList.of("-dontobfuscate"));
  }
}
