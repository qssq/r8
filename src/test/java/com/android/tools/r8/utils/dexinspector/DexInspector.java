// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils.dexinspector;

import static org.junit.Assert.assertTrue;

import com.android.tools.r8.StringResource;
import com.android.tools.r8.code.Instruction;
import com.android.tools.r8.dex.ApplicationReader;
import com.android.tools.r8.graph.Code;
import com.android.tools.r8.graph.DexAnnotation;
import com.android.tools.r8.graph.DexAnnotationElement;
import com.android.tools.r8.graph.DexAnnotationSet;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexValue;
import com.android.tools.r8.graph.DexValue.DexValueArray;
import com.android.tools.r8.graph.DexValue.DexValueString;
import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.naming.ClassNamingForNameMapper;
import com.android.tools.r8.naming.MemberNaming.MethodSignature;
import com.android.tools.r8.naming.signature.GenericSignatureAction;
import com.android.tools.r8.naming.signature.GenericSignatureParser;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.DescriptorUtils;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.Timing;
import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

public class DexInspector {

  private final DexApplication application;
  final DexItemFactory dexItemFactory;
  private final ClassNameMapper mapping;
  final BiMap<String, String> originalToObfuscatedMapping;

  public static MethodSignature MAIN =
      new MethodSignature("main", "void", new String[] {"java.lang.String[]"});

  public DexInspector(Path file, String mappingFile) throws IOException, ExecutionException {
    this(Collections.singletonList(file), mappingFile);
  }

  public DexInspector(Path file) throws IOException, ExecutionException {
    this(Collections.singletonList(file), null);
  }

  public DexInspector(List<Path> files) throws IOException, ExecutionException {
    this(files, null);
  }

  public DexInspector(List<Path> files, String mappingFile) throws IOException, ExecutionException {
    if (mappingFile != null) {
      this.mapping = ClassNameMapper.mapperFromFile(Paths.get(mappingFile));
      originalToObfuscatedMapping = this.mapping.getObfuscatedToOriginalMapping().inverse();
    } else {
      this.mapping = null;
      originalToObfuscatedMapping = null;
    }
    Timing timing = new Timing("DexInspector");
    InternalOptions options = new InternalOptions();
    dexItemFactory = options.itemFactory;
    AndroidApp input = AndroidApp.builder().addProgramFiles(files).build();
    application = new ApplicationReader(input, options, timing).read();
  }

  public DexInspector(AndroidApp app) throws IOException, ExecutionException {
    this(
        new ApplicationReader(app, new InternalOptions(), new Timing("DexInspector"))
            .read(app.getProguardMapOutputData()));
  }

  public DexInspector(AndroidApp app, Consumer<InternalOptions> optionsConsumer)
      throws IOException, ExecutionException {
    this(
        new ApplicationReader(app, runOptionsConsumer(optionsConsumer), new Timing("DexInspector"))
            .read(app.getProguardMapOutputData()));
  }

  private static InternalOptions runOptionsConsumer(Consumer<InternalOptions> optionsConsumer) {
    InternalOptions internalOptions = new InternalOptions();
    optionsConsumer.accept(internalOptions);
    return internalOptions;
  }

  public DexInspector(AndroidApp app, Path proguardMap) throws IOException, ExecutionException {
    this(
        new ApplicationReader(app, new InternalOptions(), new Timing("DexInspector"))
            .read(StringResource.fromFile(proguardMap)));
  }

  public DexInspector(DexApplication application) {
    dexItemFactory = application.dexItemFactory;
    this.application = application;
    this.mapping = application.getProguardMap();
    originalToObfuscatedMapping =
        mapping == null ? null : mapping.getObfuscatedToOriginalMapping().inverse();
  }

  public DexItemFactory getFactory() {
    return dexItemFactory;
  }

  DexType toDexType(String string) {
    return dexItemFactory.createType(DescriptorUtils.javaTypeToDescriptor(string));
  }

  private DexType toDexTypeIgnorePrimitives(String string) {
    return dexItemFactory.createType(DescriptorUtils.javaTypeToDescriptorIgnorePrimitives(string));
  }

  static <S, T extends Subject> void forAll(
      S[] items,
      BiFunction<S, FoundClassSubject, ? extends T> constructor,
      FoundClassSubject clazz,
      Consumer<T> consumer) {
    for (S item : items) {
      consumer.accept(constructor.apply(item, clazz));
    }
  }

  private static <S, T extends Subject> void forAll(
      Iterable<S> items, Function<S, T> constructor, Consumer<T> consumer) {
    for (S item : items) {
      consumer.accept(constructor.apply(item));
    }
  }

  DexAnnotation findAnnotation(String name, DexAnnotationSet annotations) {
    for (DexAnnotation annotation : annotations.annotations) {
      DexType type = annotation.annotation.type;
      String original = mapping == null ? type.toSourceString() : mapping.originalNameOf(type);
      if (original.equals(name)) {
        return annotation;
      }
    }
    return null;
  }

  public String getFinalSignatureAttribute(DexAnnotationSet annotations) {
    DexAnnotation annotation = findAnnotation("dalvik.annotation.Signature", annotations);
    if (annotation == null) {
      return null;
    }
    assert annotation.annotation.elements.length == 1;
    DexAnnotationElement element = annotation.annotation.elements[0];
    assert element.value instanceof DexValueArray;
    StringBuilder builder = new StringBuilder();
    DexValueArray valueArray = (DexValueArray) element.value;
    for (DexValue value : valueArray.getValues()) {
      assertTrue(value instanceof DexValueString);
      DexValueString s = (DexValueString) value;
      builder.append(s.getValue());
    }
    return builder.toString();
  }

  public String getOriginalSignatureAttribute(
      DexAnnotationSet annotations, BiConsumer<GenericSignatureParser, String> parse) {
    String finalSignature = getFinalSignatureAttribute(annotations);
    if (finalSignature == null || mapping == null) {
      return finalSignature;
    }

    GenericSignatureGenerator rewriter = new GenericSignatureGenerator();
    GenericSignatureParser<String> parser = new GenericSignatureParser<>(rewriter);
    parse.accept(parser, finalSignature);
    return rewriter.getSignature();
  }

  public ClassSubject clazz(Class clazz) {
    return clazz(clazz.getTypeName());
  }

  /** Lookup a class by name. This allows both original and obfuscated names. */
  public ClassSubject clazz(String name) {
    ClassNamingForNameMapper naming = null;
    if (mapping != null) {
      String obfuscated = originalToObfuscatedMapping.get(name);
      if (obfuscated != null) {
        naming = mapping.getClassNaming(obfuscated);
        name = obfuscated;
      } else {
        // Figure out if the name is an already obfuscated name.
        String original = originalToObfuscatedMapping.inverse().get(name);
        if (original != null) {
          naming = mapping.getClassNaming(name);
        }
      }
    }
    DexClass clazz = application.definitionFor(toDexTypeIgnorePrimitives(name));
    if (clazz == null) {
      return new AbsentClassSubject(this);
    }
    return new FoundClassSubject(this, clazz, naming);
  }

  public void forAllClasses(Consumer<FoundClassSubject> inspection) {
    forAll(
        application.classes(),
        cls -> {
          ClassSubject subject = clazz(cls.type.toSourceString());
          assert subject.isPresent();
          return (FoundClassSubject) subject;
        },
        inspection);
  }

  public List<FoundClassSubject> allClasses() {
    ImmutableList.Builder<FoundClassSubject> builder = ImmutableList.builder();
    forAllClasses(builder::add);
    return builder.build();
  }

  public MethodSubject method(Method method) {
    ClassSubject clazz = clazz(method.getDeclaringClass());
    if (!clazz.isPresent()) {
      return new AbsentMethodSubject();
    }
    return clazz.method(method);
  }

  String getObfuscatedTypeName(String originalTypeName) {
    String obfuscatedType = null;
    if (mapping != null) {
      obfuscatedType = originalToObfuscatedMapping.get(originalTypeName);
    }
    obfuscatedType = obfuscatedType == null ? originalTypeName : obfuscatedType;
    return obfuscatedType;
  }

  InstructionSubject createInstructionSubject(Instruction instruction) {
    DexInstructionSubject dexInst = new DexInstructionSubject(instruction);
    if (dexInst.isInvoke()) {
      return new InvokeDexInstructionSubject(this, instruction);
    } else if (dexInst.isFieldAccess()) {
      return new FieldAccessDexInstructionSubject(this, instruction);
    } else {
      return dexInst;
    }
  }

  InstructionIterator createInstructionIterator(MethodSubject method) {
    Code code = method.getMethod().getCode();
    assert code != null && code.isDexCode();
    return new DexInstructionIterator(this, method);
  }

  // Build the generic signature using the current mapping if any.
  class GenericSignatureGenerator implements GenericSignatureAction<String> {

    private StringBuilder signature;

    public String getSignature() {
      return signature.toString();
    }

    @Override
    public void parsedSymbol(char symbol) {
      signature.append(symbol);
    }

    @Override
    public void parsedIdentifier(String identifier) {
      signature.append(identifier);
    }

    @Override
    public String parsedTypeName(String name) {
      String type = name;
      if (originalToObfuscatedMapping != null) {
        String original = originalToObfuscatedMapping.inverse().get(name);
        type = original != null ? original : name;
      }
      signature.append(type);
      return type;
    }

    @Override
    public String parsedInnerTypeName(String enclosingType, String name) {
      String type;
      if (originalToObfuscatedMapping != null) {
        // The enclosingType has already been mapped if a mapping is present.
        String minifiedEnclosing = originalToObfuscatedMapping.get(enclosingType);
        type = originalToObfuscatedMapping.inverse().get(minifiedEnclosing + "$" + name);
        if (type != null) {
          assert type.startsWith(enclosingType + "$");
          name = type.substring(enclosingType.length() + 1);
        }
      } else {
        type = enclosingType + "$" + name;
      }
      signature.append(name);
      return type;
    }

    @Override
    public void start() {
      signature = new StringBuilder();
    }

    @Override
    public void stop() {
      // nothing to do
    }
  }
}