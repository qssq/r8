// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import static com.android.tools.r8.TestBase.Backend.DEX;
import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.junit.Assert.assertThat;

import com.android.tools.r8.ClassFileConsumer.ArchiveConsumer;
import com.android.tools.r8.TestBase.Backend;
import com.android.tools.r8.ToolHelper.DexVm;
import com.android.tools.r8.ToolHelper.ProcessResult;
import com.android.tools.r8.debug.CfDebugTestConfig;
import com.android.tools.r8.debug.DebugTestConfig;
import com.android.tools.r8.debug.DexDebugTestConfig;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.DescriptorUtils;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ObjectArrays;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.hamcrest.Matcher;

public abstract class TestCompileResult<
        CR extends TestCompileResult<CR, RR>, RR extends TestRunResult>
    extends TestBaseResult<CR, RR> {

  public final AndroidApp app;
  final List<Path> additionalRunClassPath = new ArrayList<>();
  final List<String> vmArguments = new ArrayList<>();

  TestCompileResult(TestState state, AndroidApp app) {
    super(state);
    this.app = app;
  }

  public abstract Backend getBackend();

  public abstract TestDiagnosticMessages getDiagnosticMessages();

  protected abstract RR createRunResult(ProcessResult result);

  @Deprecated
  public RR run(Class<?> mainClass) throws ExecutionException, IOException {
    return run(mainClass.getTypeName());
  }

  @Deprecated
  public RR run(String mainClass) throws ExecutionException, IOException {
    ClassSubject mainClassSubject = inspector().clazz(mainClass);
    assertThat(mainClassSubject, isPresent());
    switch (getBackend()) {
      case DEX:
        return runArt(null, additionalRunClassPath, mainClassSubject.getFinalName());
      case CF:
        return runJava(null, additionalRunClassPath, mainClassSubject.getFinalName());
      default:
        throw new Unreachable();
    }
  }

  public RR run(TestRuntime runtime, Class<?> mainClass) throws ExecutionException, IOException {
    return run(runtime, mainClass.getTypeName());
  }

  public RR run(TestRuntime runtime, String mainClass) throws ExecutionException, IOException {
    return run(runtime, mainClass, new String[] {});
  }

  public RR run(TestRuntime runtime, String mainClass, String... args)
      throws ExecutionException, IOException {
    assert getBackend() == runtime.getBackend();
    ClassSubject mainClassSubject = inspector().clazz(mainClass);
    assertThat(mainClassSubject, isPresent());
    if (runtime.isDex()) {
      return runArt(
          runtime.asDex().getVm(), additionalRunClassPath, mainClassSubject.getFinalName(), args);
    }
    assert runtime.isCf();
    return runJava(
        runtime,
        additionalRunClassPath,
        ObjectArrays.concat(mainClassSubject.getFinalName(), args));
  }

  public CR addRunClasspathFiles(Path... classpath) {
    return addRunClasspathFiles(Arrays.asList(classpath));
  }

  public CR addRunClasspathFiles(List<Path> classpath) {
    additionalRunClassPath.addAll(classpath);
    return self();
  }

  public CR addRunClasspathClasses(Class<?>... classpath) {
    return addRunClasspathClasses(Arrays.asList(classpath));
  }

  public CR addRunClasspathClasses(List<Class<?>> classpath) {
    try {
      Path path = state.getNewTempFolder().resolve("runtime-classes.jar");
      ArchiveConsumer consumer = new ArchiveConsumer(path);
      for (Class clazz : classpath) {
        consumer.accept(
            ByteDataView.of(ToolHelper.getClassAsBytes(clazz)),
            DescriptorUtils.javaTypeToDescriptor(clazz.getTypeName()),
            null);
      }
      consumer.finished(null);
      additionalRunClassPath.addAll(Collections.singletonList(path));
      return self();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public CR enableRuntimeAssertions() {
    assert getBackend() == Backend.CF;
    if (!this.vmArguments.contains("-ea")) {
      this.vmArguments.add("-ea");
    }
    return self();
  }

  public Path writeToZip() throws IOException {
    Path file = state.getNewTempFolder().resolve("out.zip");
    writeToZip(file);
    return file;
  }

  public CR writeToZip(Path file) throws IOException {
    app.writeToZip(file, getBackend() == DEX ? OutputMode.DexIndexed : OutputMode.ClassFile);
    return self();
  }

  public CodeInspector inspector() throws IOException, ExecutionException {
    return new CodeInspector(app);
  }

  public CR inspect(Consumer<CodeInspector> consumer) throws IOException, ExecutionException {
    consumer.accept(inspector());
    return self();
  }

  public CR assertNoMessages() {
    getDiagnosticMessages().assertNoMessages();
    return self();
  }

  public CR assertOnlyInfos() {
    getDiagnosticMessages().assertOnlyInfos();
    return self();
  }

  public CR assertOnlyWarnings() {
    getDiagnosticMessages().assertOnlyWarnings();
    return self();
  }

  public CR assertWarningMessageThatMatches(Matcher<String> matcher) {
    getDiagnosticMessages().assertWarningMessageThatMatches(matcher);
    return self();
  }

  public CR assertNoWarningMessageThatMatches(Matcher<String> matcher) {
    getDiagnosticMessages().assertNoWarningMessageThatMatches(matcher);
    return self();
  }

  public CR disassemble(PrintStream ps) throws IOException, ExecutionException {
    ToolHelper.disassemble(app, ps);
    return self();
  }

  public CR disassemble() throws IOException, ExecutionException {
    return disassemble(System.out);
  }

  public DebugTestConfig debugConfig() {
    // Rethrow exceptions since debug config is usually used in a delayed wrapper which
    // does not declare exceptions.
    try {
      Path out = state.getNewTempFolder().resolve("out.zip");
      switch (getBackend()) {
        case CF:
          {
            app.writeToZip(out, OutputMode.ClassFile);
            return new CfDebugTestConfig().addPaths(out);
          }
        case DEX:
          {
            app.writeToZip(out, OutputMode.DexIndexed);
            return new DexDebugTestConfig().addPaths(out);
          }
        default:
          throw new Unreachable();
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private RR runJava(TestRuntime runtime, List<Path> additionalClassPath, String... arguments)
      throws IOException {
    // TODO(b/127785410): Always assume a non-null runtime.
    assert runtime == null || TestParametersBuilder.isSystemJdk(runtime.asCf().getVm());
    Path out = state.getNewTempFolder().resolve("out.zip");
    app.writeToZip(out, OutputMode.ClassFile);
    List<Path> classPath = ImmutableList.<Path>builder()
        .addAll(additionalClassPath)
        .add(out)
        .build();
    ProcessResult result = ToolHelper.runJava(vmArguments, classPath, arguments);
    return createRunResult(result);
  }

  private RR runArt(DexVm vm, List<Path> additionalClassPath, String mainClass, String... arguments)
      throws IOException {
    // TODO(b/127785410): Always assume a non-null runtime.
    Path out = state.getNewTempFolder().resolve("out.zip");
    app.writeToZip(out, OutputMode.DexIndexed);
    List<String> classPath = ImmutableList.<String>builder()
        .addAll(additionalClassPath.stream().map(Path::toString).collect(Collectors.toList()))
        .add(out.toString())
        .build();
    ProcessResult result = ToolHelper.runArtRaw(classPath, mainClass, dummy -> {}, vm, arguments);
    return createRunResult(result);
  }

  public Dex2OatTestRunResult runDex2Oat() throws IOException {
    return runDex2Oat(ToolHelper.getDexVm());
  }

  public Dex2OatTestRunResult runDex2Oat(DexVm vm) throws IOException {
    assert getBackend() == DEX;
    Path tmp = state.getNewTempFolder();
    Path jarFile = tmp.resolve("out.jar");
    Path oatFile = tmp.resolve("out.oat");
    app.writeToZip(jarFile, OutputMode.DexIndexed);
    return new Dex2OatTestRunResult(app, ToolHelper.runDex2OatRaw(jarFile, oatFile, vm));
  }
}
