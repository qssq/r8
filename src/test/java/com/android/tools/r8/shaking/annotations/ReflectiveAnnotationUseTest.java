// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking.annotations;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static com.android.tools.r8.utils.codeinspector.Matchers.isRenamed;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.android.tools.r8.KotlinTestBase;
import com.android.tools.r8.R8TestBuilder;
import com.android.tools.r8.TestRunResult;
import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.ToolHelper.DexVm.Version;
import com.android.tools.r8.ToolHelper.KotlinTargetVersion;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.BooleanUtils;
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.android.tools.r8.utils.codeinspector.MethodSubject;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ReflectiveAnnotationUseTest extends KotlinTestBase {
  private static final String FOLDER = "internal_annotation";
  private static final String MAIN_CLASS_NAME = "internal_annotation.MainKt";
  private static final String ANNOTATION_NAME = "internal_annotation.Annotation";
  private static final String KEEP_ANNOTATIONS = "-keepattributes *Annotation*";

  private static final String JAVA_OUTPUT = StringUtils.lines(
      "Impl::toString",
      "Impl::Annotation::field2.Impl::Annotation::field2(Impl::Annotation::field2:2)"
  );

  private static final String OUTPUT_WITHOUT_ANNOTATION = StringUtils.lines(
      "Impl::toString",
      "null"
  );

  private final Backend backend;
  private final boolean minify;

  @Parameterized.Parameters(name = "Backend: {0} target: {1} minify: {2}")
  public static Collection<Object[]> data() {
    return buildParameters(Backend.values(), KotlinTargetVersion.values(), BooleanUtils.values());
  }

  public ReflectiveAnnotationUseTest(
      Backend backend, KotlinTargetVersion targetVersion, boolean minify) {
    super(targetVersion);
    this.backend = backend;
    this.minify = minify;
  }

  @Test
  public void b120951621_JVMoutput() throws Exception {
    assumeTrue("Only run JVM reference once (for CF backend)", backend == Backend.CF);
    AndroidApp app = AndroidApp.builder()
        .addProgramFile(getKotlinJarFile(FOLDER))
        .addProgramFile(getJavaJarFile(FOLDER))
        .build();
    String result = runOnJava(app, MAIN_CLASS_NAME);
    assertEquals(JAVA_OUTPUT, result);
  }

  @Test
  public void b120951621_keepAll() throws Exception {
    R8TestBuilder builder = testForR8(backend)
        .addProgramFiles(getKotlinJarFile(FOLDER))
        .addProgramFiles(getJavaJarFile(FOLDER))
        .addKeepMainRule(MAIN_CLASS_NAME)
        .addKeepRules(KEEP_ANNOTATIONS)
        .addKeepRules(
            "-keep @interface " + ANNOTATION_NAME + " {",
            "  *;",
            "}"
        );
    if (!minify) {
      builder.noMinification();
    }
    TestRunResult result = builder.run(MAIN_CLASS_NAME);

    result.assertSuccessWithOutput(JAVA_OUTPUT);

    CodeInspector inspector = result.inspector();
    ClassSubject clazz = inspector.clazz(ANNOTATION_NAME);
    assertThat(clazz, isPresent());
    assertThat(clazz, not(isRenamed()));
    MethodSubject f1 = clazz.uniqueMethodWithName("f1");
    assertThat(f1, isPresent());
    assertThat(f1, not(isRenamed()));
    MethodSubject f2 = clazz.uniqueMethodWithName("f2");
    assertThat(f2, isPresent());
    assertThat(f2, not(isRenamed()));
    MethodSubject f3 = clazz.uniqueMethodWithName("f3");
    assertThat(f3, isPresent());
    assertThat(f3, not(isRenamed()));
    MethodSubject f4 = clazz.uniqueMethodWithName("f4");
    assertThat(f4, isPresent());
    assertThat(f4, not(isRenamed()));
  }

  @Test
  public void b120951621_partiallyKeep() throws Exception {
    R8TestBuilder builder = testForR8(backend)
        .addProgramFiles(getKotlinJarFile(FOLDER))
        .addProgramFiles(getJavaJarFile(FOLDER))
        .addKeepMainRule(MAIN_CLASS_NAME)
        .addKeepRules(KEEP_ANNOTATIONS)
        .addKeepRules(
            "-keep,allowobfuscation @interface " + ANNOTATION_NAME + " {",
            "  java.lang.String *f2();",
            "}"
        );
    if (!minify) {
      builder.noMinification();
    }
    TestRunResult result = builder.run(MAIN_CLASS_NAME);

    if (backend == Backend.DEX) {
      if (ToolHelper.getDexVm().getVersion().isOlderThanOrEqual(Version.V4_4_4)) {
        result.assertFailureWithErrorThatMatches(
            containsString("failure in processEncodedAnnotation"));
        return;
      }
      if (ToolHelper.getDexVm().getVersion().isOlderThanOrEqual(Version.V6_0_1)) {
        result.assertFailureWithErrorThatMatches(containsString("IncompatibleClassChangeError"));
        return;
      }
      result.assertSuccessWithOutput(OUTPUT_WITHOUT_ANNOTATION);
    } else {
      result.assertSuccessWithOutput(JAVA_OUTPUT);
    }

    CodeInspector inspector = result.inspector();
    ClassSubject clazz = inspector.clazz(ANNOTATION_NAME);
    assertThat(clazz, isPresent());
    assertEquals(minify, clazz.isRenamed());
    MethodSubject f1 = clazz.uniqueMethodWithName("f1");
    assertThat(f1, isPresent());
    assertThat(f1, not(isRenamed()));
    MethodSubject f2 = clazz.uniqueMethodWithName("f2");
    assertThat(f2, isPresent());
    assertThat(f2, not(isRenamed()));
    MethodSubject f3 = clazz.uniqueMethodWithName("f3");
    assertThat(f3, not(isPresent()));
    MethodSubject f4 = clazz.uniqueMethodWithName("f4");
    assertThat(f4, not(isPresent()));
  }

  @Test
  public void b120951621_keepAnnotation() throws Exception {
    R8TestBuilder builder = testForR8(backend)
        .addProgramFiles(getKotlinJarFile(FOLDER))
        .addProgramFiles(getJavaJarFile(FOLDER))
        .addKeepMainRule(MAIN_CLASS_NAME)
        .addKeepRules(KEEP_ANNOTATIONS);
    if (!minify) {
      builder.noMinification();
    }
    TestRunResult result = builder.run(MAIN_CLASS_NAME);

    if (backend == Backend.DEX) {
      if (ToolHelper.getDexVm().getVersion().isOlderThanOrEqual(Version.V4_4_4)) {
        result.assertFailureWithErrorThatMatches(
            containsString("failure in processEncodedAnnotation"));
        return;
      }
      if (ToolHelper.getDexVm().getVersion().isOlderThanOrEqual(Version.V6_0_1)) {
        result.assertFailureWithErrorThatMatches(containsString("IncompatibleClassChangeError"));
        return;
      }
      result.assertSuccessWithOutput(OUTPUT_WITHOUT_ANNOTATION);
    } else {
      result.assertSuccessWithOutput(JAVA_OUTPUT);
    }

    CodeInspector inspector = result.inspector();
    ClassSubject clazz = inspector.clazz(ANNOTATION_NAME);
    assertThat(clazz, isPresent());
    assertEquals(minify, clazz.isRenamed());
    MethodSubject f1 = clazz.uniqueMethodWithName("f1");
    assertThat(f1, isPresent());
    assertThat(f1, not(isRenamed()));
    MethodSubject f2 = clazz.uniqueMethodWithName("f2");
    assertThat(f2, isPresent());
    assertThat(f2, not(isRenamed()));
    MethodSubject f3 = clazz.uniqueMethodWithName("f3");
    assertThat(f3, not(isPresent()));
    MethodSubject f4 = clazz.uniqueMethodWithName("f4");
    assertThat(f4, not(isPresent()));
  }

  @Test
  public void b120951621_noKeep() throws Exception {
    R8TestBuilder builder = testForR8(backend)
        .addProgramFiles(getKotlinJarFile(FOLDER))
        .addProgramFiles(getJavaJarFile(FOLDER))
        .addKeepMainRule(MAIN_CLASS_NAME);
    if (!minify) {
      builder.noMinification();
    }
    TestRunResult result = builder.run(MAIN_CLASS_NAME);
    result.assertSuccessWithOutput(OUTPUT_WITHOUT_ANNOTATION);

    CodeInspector inspector = result.inspector();
    ClassSubject clazz = inspector.clazz(ANNOTATION_NAME);
    assertThat(clazz, isPresent());
    assertEquals(minify, clazz.isRenamed());
    MethodSubject f1 = clazz.uniqueMethodWithName("f1");
    assertThat(f1, not(isPresent()));
    MethodSubject f2 = clazz.uniqueMethodWithName("f2");
    assertThat(f2, not(isPresent()));
    MethodSubject f3 = clazz.uniqueMethodWithName("f3");
    assertThat(f3, not(isPresent()));
    MethodSubject f4 = clazz.uniqueMethodWithName("f4");
    assertThat(f4, not(isPresent()));
  }

}