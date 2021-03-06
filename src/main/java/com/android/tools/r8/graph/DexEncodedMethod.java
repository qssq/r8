// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import static com.android.tools.r8.graph.DexEncodedMethod.CompilationState.PROCESSED_INLINING_CANDIDATE_ANY;
import static com.android.tools.r8.graph.DexEncodedMethod.CompilationState.PROCESSED_INLINING_CANDIDATE_SAME_CLASS;
import static com.android.tools.r8.graph.DexEncodedMethod.CompilationState.PROCESSED_INLINING_CANDIDATE_SAME_PACKAGE;
import static com.android.tools.r8.graph.DexEncodedMethod.CompilationState.PROCESSED_INLINING_CANDIDATE_SUBCLASS;
import static com.android.tools.r8.graph.DexEncodedMethod.CompilationState.PROCESSED_NOT_INLINING_CANDIDATE;

import com.android.tools.r8.cf.code.CfConstNull;
import com.android.tools.r8.cf.code.CfInstruction;
import com.android.tools.r8.cf.code.CfThrow;
import com.android.tools.r8.code.Const;
import com.android.tools.r8.code.ConstString;
import com.android.tools.r8.code.ConstStringJumbo;
import com.android.tools.r8.code.Instruction;
import com.android.tools.r8.code.InvokeDirect;
import com.android.tools.r8.code.InvokeStatic;
import com.android.tools.r8.code.NewInstance;
import com.android.tools.r8.code.Throw;
import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.dex.JumboStringRewriter;
import com.android.tools.r8.dex.MethodToCodeObjectMapping;
import com.android.tools.r8.dex.MixedSectionCollection;
import com.android.tools.r8.errors.InternalCompilerError;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppInfo.ResolutionResult;
import com.android.tools.r8.graph.ParameterUsagesInfo.ParameterUsage;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Invoke;
import com.android.tools.r8.ir.code.Position;
import com.android.tools.r8.ir.code.ValueNumberGenerator;
import com.android.tools.r8.ir.code.ValueType;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.ConstraintWithTarget;
import com.android.tools.r8.ir.optimize.Inliner.Reason;
import com.android.tools.r8.ir.regalloc.RegisterAllocator;
import com.android.tools.r8.ir.synthetic.ForwardMethodSourceCode;
import com.android.tools.r8.ir.synthetic.SynthesizedCode;
import com.android.tools.r8.logging.Log;
import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.naming.MemberNaming.MethodSignature;
import com.android.tools.r8.naming.MemberNaming.Signature;
import com.android.tools.r8.naming.NamingLens;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.utils.InternalOptions;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public class DexEncodedMethod extends KeyedDexItem<DexMethod> implements ResolutionResult {

  /**
   * Encodes the processing state of a method.
   *
   * <p>We also use this enum to encode under what constraints a method may be inlined.
   */
  // TODO(b/128967328): Need to extend this to a state with the context.
  public enum CompilationState {
    /**
     * Has not been processed, yet.
     */
    NOT_PROCESSED,
    /**
     * Has been processed but cannot be inlined due to instructions that are not supported.
     */
    PROCESSED_NOT_INLINING_CANDIDATE,
    /**
     * Code only contains instructions that access public entities and can this be inlined into any
     * context.
     */
    PROCESSED_INLINING_CANDIDATE_ANY,
    /**
     * Code also contains instructions that access protected entities that reside in a different
     * package and hence require subclass relationship to be visible.
     */
    PROCESSED_INLINING_CANDIDATE_SUBCLASS,
    /**
     * Code contains instructions that reference package private entities or protected entities from
     * the same package.
     */
    PROCESSED_INLINING_CANDIDATE_SAME_PACKAGE,
    /**
     * Code contains instructions that reference private entities.
     */
    PROCESSED_INLINING_CANDIDATE_SAME_CLASS,
  }

  public static final DexEncodedMethod[] EMPTY_ARRAY = {};
  public static final DexEncodedMethod SENTINEL =
      new DexEncodedMethod(null, null, null, null, null);

  public final DexMethod method;
  public final MethodAccessFlags accessFlags;
  public DexAnnotationSet annotations;
  public ParameterAnnotationsList parameterAnnotationsList;
  private Code code;
  // TODO(b/128967328): towards finer-grained inlining constraints,
  //   we need to maintain a set of states with (potentially different) contexts.
  private CompilationState compilationState = CompilationState.NOT_PROCESSED;
  private OptimizationInfo optimizationInfo = DefaultOptimizationInfoImpl.DEFAULT_INSTANCE;
  private int classFileVersion = -1;

  private DexEncodedMethod defaultInterfaceMethodImplementation = null;

  // This flag indicates the current instance is no longer up-to-date as another instance was
  // created based on this. Any further (public) operations on this instance will raise an error
  // to catch potential bugs due to the inconsistency (e.g., http://b/111893131)
  // Any newly added `public` method should check if `this` instance is obsolete.
  private boolean obsolete = false;

  private void checkIfObsolete() {
    assert !obsolete;
  }

  public boolean isObsolete() {
    // Do not be cheating. This util can be used only if you're going to do appropriate action,
    // e.g., using GraphLense#mapDexEncodedMethod to look up the correct, up-to-date instance.
    return obsolete;
  }

  public void setObsolete() {
    // By assigning an Exception, you can see when/how this instance becomes obsolete v.s.
    // when/how such obsolete instance is used.
    obsolete = true;
  }

  public DexEncodedMethod getDefaultInterfaceMethodImplementation() {
    return defaultInterfaceMethodImplementation;
  }

  public void setDefaultInterfaceMethodImplementation(DexEncodedMethod implementation) {
    assert defaultInterfaceMethodImplementation == null;
    assert implementation != null;
    assert code != null;
    assert code == implementation.getCode();
    assert code.getOwner() == implementation;
    accessFlags.setAbstract();
    removeCode();
    // Reset the code ownership to the implementation method as the above removeCode cleared it.
    implementation.setCodeOwnership();
    defaultInterfaceMethodImplementation = implementation;
  }

  /**
   * Flags this method as no longer being obsolete.
   *
   * Example use case: The vertical class merger optimistically merges two classes before it is
   * guaranteed that the two classes can be merged. In this process, methods are moved from the
   * source class to the target class using {@link #toTypeSubstitutedMethod(DexMethod)}, which
   * causes the original methods of the source class to become obsolete. If vertical class merging
   * is aborted, the original methods of the source class needs to be marked as not being obsolete.
   */
  public void unsetObsolete() {
    obsolete = false;
  }

  public DexEncodedMethod(
      DexMethod method,
      MethodAccessFlags accessFlags,
      DexAnnotationSet annotations,
      ParameterAnnotationsList parameterAnnotationsList,
      Code code) {
    this.method = method;
    this.accessFlags = accessFlags;
    this.annotations = annotations;
    this.parameterAnnotationsList = parameterAnnotationsList;
    this.code = code;
    assert code == null || !shouldNotHaveCode();
    setCodeOwnership();
  }

  public DexEncodedMethod(
      DexMethod method,
      MethodAccessFlags flags,
      DexAnnotationSet annotationSet,
      ParameterAnnotationsList annotationsList,
      Code code,
      int classFileVersion) {
    this(method, flags, annotationSet, annotationsList, code);
    this.classFileVersion = classFileVersion;
  }

  public boolean isProcessed() {
    checkIfObsolete();
    return compilationState != CompilationState.NOT_PROCESSED;
  }

  public boolean isInitializer() {
    checkIfObsolete();
    return isInstanceInitializer() || isClassInitializer();
  }

  public boolean isInstanceInitializer() {
    checkIfObsolete();
    return accessFlags.isConstructor() && !accessFlags.isStatic();
  }

  public boolean isDefaultInitializer() {
    checkIfObsolete();
    return isInstanceInitializer() && method.proto.parameters.isEmpty();
  }

  public boolean isClassInitializer() {
    checkIfObsolete();
    return accessFlags.isConstructor() && accessFlags.isStatic();
  }

  /**
   * Returns true if this method can be invoked via invoke-virtual, invoke-super or
   * invoke-interface.
   */
  public boolean isVirtualMethod() {
    checkIfObsolete();
    return !accessFlags.isStatic() && !accessFlags.isPrivate() && !accessFlags.isConstructor();
  }

  /**
   * Returns true if this method can be invoked via invoke-virtual, invoke-super or invoke-interface
   * and is non-abstract.
   */
  public boolean isNonAbstractVirtualMethod() {
    checkIfObsolete();
    return isVirtualMethod() && !accessFlags.isAbstract();
  }

  public boolean isPublicized() {
    checkIfObsolete();
    return accessFlags.isPromotedToPublic();
  }

  public boolean isPublicMethod() {
    checkIfObsolete();
    return accessFlags.isPublic();
  }

  public boolean isPrivateMethod() {
    checkIfObsolete();
    return accessFlags.isPrivate();
  }

  /**
   * Returns true if this method can be invoked via invoke-direct.
   */
  public boolean isDirectMethod() {
    checkIfObsolete();
    return (accessFlags.isPrivate() || accessFlags.isConstructor()) && !accessFlags.isStatic();
  }

  @Override
  public boolean isStatic() {
    checkIfObsolete();
    return accessFlags.isStatic();
  }

  @Override
  public boolean isStaticMember() {
    checkIfObsolete();
    return isStatic();
  }

  /**
   * Returns true if this method is synthetic.
   */
  public boolean isSyntheticMethod() {
    checkIfObsolete();
    return accessFlags.isSynthetic();
  }

  public boolean isInliningCandidate(
      DexEncodedMethod container, Reason inliningReason, AppInfoWithSubtyping appInfo) {
    checkIfObsolete();
    return isInliningCandidate(container.method.holder, inliningReason, appInfo);
  }

  public boolean isInliningCandidate(
      DexType containerType, Reason inliningReason, AppInfoWithSubtyping appInfo) {
    checkIfObsolete();
    if (isClassInitializer()) {
      // This will probably never happen but never inline a class initializer.
      return false;
    }
    if (inliningReason == Reason.FORCE) {
      // Make sure we would be able to inline this normally.
      if (!isInliningCandidate(containerType, Reason.SIMPLE, appInfo)) {
        // If not, raise a flag, because some optimizations that depend on force inlining would
        // silently produce an invalid code, which is worse than an internal error.
        throw new InternalCompilerError("FORCE inlining on non-inlinable: " + toSourceString());
      }
      return true;
    }
    // TODO(b/128967328): inlining candidate should satisfy all states if multiple states are there.
    switch (compilationState) {
      case PROCESSED_INLINING_CANDIDATE_ANY:
        return true;
      case PROCESSED_INLINING_CANDIDATE_SUBCLASS:
        return containerType.isSubtypeOf(method.holder, appInfo);
      case PROCESSED_INLINING_CANDIDATE_SAME_PACKAGE:
        return containerType.isSamePackage(method.holder);
      case PROCESSED_INLINING_CANDIDATE_SAME_CLASS:
        return containerType == method.holder;
      default:
        return false;
    }
  }

  public boolean markProcessed(ConstraintWithTarget state) {
    checkIfObsolete();
    CompilationState prevCompilationState = compilationState;
    switch (state.constraint) {
      case ALWAYS:
        compilationState = PROCESSED_INLINING_CANDIDATE_ANY;
        break;
      case SUBCLASS:
        compilationState = PROCESSED_INLINING_CANDIDATE_SUBCLASS;
        break;
      case PACKAGE:
        compilationState = PROCESSED_INLINING_CANDIDATE_SAME_PACKAGE;
        break;
      case SAMECLASS:
        compilationState = PROCESSED_INLINING_CANDIDATE_SAME_CLASS;
        break;
      case NEVER:
        compilationState = PROCESSED_NOT_INLINING_CANDIDATE;
        break;
    }
    return prevCompilationState != compilationState;
  }

  public void markNotProcessed() {
    checkIfObsolete();
    compilationState = CompilationState.NOT_PROCESSED;
  }

  public IRCode buildIR(AppView<? extends AppInfo> appView, Origin origin) {
    checkIfObsolete();
    return code == null ? null : code.buildIR(this, appView, origin);
  }

  public IRCode buildInliningIR(
      DexEncodedMethod context,
      AppView<? extends AppInfo> appView,
      ValueNumberGenerator valueNumberGenerator,
      Position callerPosition,
      Origin origin) {
    checkIfObsolete();
    return code.buildInliningIR(
        context, this, appView, valueNumberGenerator, callerPosition, origin);
  }

  public void setCode(Code code) {
    checkIfObsolete();
    voidCodeOwnership();
    this.code = code;
    setCodeOwnership();
  }

  public void setCode(IRCode ir, RegisterAllocator registerAllocator, InternalOptions options) {
    checkIfObsolete();
    final DexBuilder builder = new DexBuilder(ir, registerAllocator);
    setCode(builder.build());
  }

  @Override
  public String toString() {
    checkIfObsolete();
    return "Encoded method " + method;
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems,
      DexMethod method, int instructionOffset) {
    checkIfObsolete();
    this.method.collectIndexedItems(indexedItems);
    if (code != null) {
      code.collectIndexedItems(indexedItems, this.method);
    }
    annotations.collectIndexedItems(indexedItems);
    parameterAnnotationsList.collectIndexedItems(indexedItems);
  }

  @Override
  void collectMixedSectionItems(MixedSectionCollection mixedItems) {
    mixedItems.visit(this);
  }

  public void collectMixedSectionItemsWithCodeMapping(
      MixedSectionCollection mixedItems, MethodToCodeObjectMapping mapping) {
    DexCode code = mapping.getCode(this);
    if (code != null) {
      code.collectMixedSectionItems(mixedItems);
    }
    annotations.collectMixedSectionItems(mixedItems);
    parameterAnnotationsList.collectMixedSectionItems(mixedItems);
  }

  public boolean shouldNotHaveCode() {
    return accessFlags.isAbstract() || accessFlags.isNative();
  }

  public boolean hasCode() {
    return code != null;
  }

  public Code getCode() {
    checkIfObsolete();
    return code;
  }

  public void removeCode() {
    checkIfObsolete();
    voidCodeOwnership();
    code = null;
  }

  private void setCodeOwnership() {
    if (code != null) {
      code.setOwner(this);
    }
  }

  public void voidCodeOwnership() {
    if (code != null) {
      code.setOwner(null);
    }
  }

  public int getClassFileVersion() {
    checkIfObsolete();
    assert classFileVersion >= 0;
    return classFileVersion;
  }

  public boolean hasClassFileVersion() {
    checkIfObsolete();
    return classFileVersion >= 0;
  }

  public void upgradeClassFileVersion(int version) {
    checkIfObsolete();
    assert version >= 0;
    assert !hasClassFileVersion() || version >= getClassFileVersion();
    classFileVersion = version;
  }

  public String qualifiedName() {
    checkIfObsolete();
    return method.qualifiedName();
  }

  public String descriptor() {
    checkIfObsolete();
    return descriptor(NamingLens.getIdentityLens());
  }

  public String descriptor(NamingLens namingLens) {
    checkIfObsolete();
    StringBuilder builder = new StringBuilder();
    builder.append("(");
    for (DexType type : method.proto.parameters.values) {
      builder.append(namingLens.lookupDescriptor(type).toString());
    }
    builder.append(")");
    builder.append(namingLens.lookupDescriptor(method.proto.returnType).toString());
    return builder.toString();
  }

  public String toSmaliString(ClassNameMapper naming) {
    checkIfObsolete();
    StringBuilder builder = new StringBuilder();
    builder.append(".method ");
    builder.append(accessFlags.toSmaliString());
    builder.append(" ");
    builder.append(method.name.toSmaliString());
    builder.append(method.proto.toSmaliString());
    builder.append("\n");
    if (code != null) {
      DexCode dexCode = code.asDexCode();
      builder.append("    .registers ");
      builder.append(dexCode.registerSize);
      builder.append("\n\n");
      builder.append(dexCode.toSmaliString(naming));
    }
    builder.append(".end method\n");
    return builder.toString();
  }

  @Override
  public String toSourceString() {
    checkIfObsolete();
    return method.toSourceString();
  }

  public DexEncodedMethod toAbstractMethod() {
    checkIfObsolete();
    // 'final' wants this to be *not* overridden, while 'abstract' wants this to be implemented in
    // a subtype, i.e., self contradict.
    assert !accessFlags.isFinal();
    accessFlags.setAbstract();
    voidCodeOwnership();
    this.code = null;
    return this;
  }

  /**
   * Generates a {@link DexCode} object for the given instructions.
   * <p>
   * As the code object is produced outside of the normal compilation cycle, it has to use {@link
   * ConstStringJumbo} to reference string constants. Hence, code produced form these templates
   * might incur a size overhead.
   */
  private DexCode generateCodeFromTemplate(
      int numberOfRegisters, int outRegisters, Instruction... instructions) {
    int offset = 0;
    for (Instruction instruction : instructions) {
      assert !(instruction instanceof ConstString);
      instruction.setOffset(offset);
      offset += instruction.getSize();
    }
    int requiredArgRegisters = accessFlags.isStatic() ? 0 : 1;
    for (DexType type : method.proto.parameters.values) {
      requiredArgRegisters += ValueType.fromDexType(type).requiredRegisters();
    }
    return new DexCode(
        Math.max(numberOfRegisters, requiredArgRegisters),
        requiredArgRegisters,
        outRegisters,
        instructions,
        new DexCode.Try[0],
        new DexCode.TryHandler[0],
        null);
  }

  public DexCode buildEmptyThrowingDexCode() {
    Instruction insn[] = {new Const(0, 0), new Throw(0)};
    return generateCodeFromTemplate(1, 0, insn);
  }

  public DexEncodedMethod toEmptyThrowingMethodDex() {
    checkIfObsolete();
    assert !shouldNotHaveCode();
    Builder builder = builder(this);
    builder.setCode(buildEmptyThrowingDexCode());
    // Note that we are not marking this instance obsolete, since this util is only used by
    // TreePruner while keeping non-live yet targeted, empty method. Such method can be retrieved
    // again only during the 2nd round of tree sharking, and seeing an obsolete empty body v.s.
    // seeing this empty throwing code do not matter.
    // If things are changed, the cure point is obsolete instances inside RootSet.
    return builder.build();
  }

  public CfCode buildEmptyThrowingCfCode() {
    CfInstruction insn[] = {new CfConstNull(), new CfThrow()};
    return new CfCode(
        method,
        1,
        method.proto.parameters.size() + 1,
        Arrays.asList(insn),
        Collections.emptyList(),
        Collections.emptyList());
  }

  public DexEncodedMethod toEmptyThrowingMethodCf() {
    checkIfObsolete();
    assert !shouldNotHaveCode();
    Builder builder = builder(this);
    builder.setCode(buildEmptyThrowingCfCode());
    // Note that we are not marking this instance obsolete:
    // refer to Dex-backend version of this method above.
    return builder.build();
  }

  public DexEncodedMethod toMethodThatLogsError(DexItemFactory itemFactory) {
    checkIfObsolete();
    Signature signature = MethodSignature.fromDexMethod(method);
    // TODO(herhut): Construct this out of parts to enable reuse, maybe even using descriptors.
    DexString message = itemFactory.createString(
        "Shaking error: Missing method in " + method.holder.toSourceString() + ": "
            + signature);
    DexString tag = itemFactory.createString("TOIGHTNESS");
    DexType[] args = {itemFactory.stringType, itemFactory.stringType};
    DexProto proto = itemFactory.createProto(itemFactory.intType, args);
    DexMethod logMethod = itemFactory
        .createMethod(itemFactory.createType("Landroid/util/Log;"), proto,
            itemFactory.createString("e"));
    DexType exceptionType = itemFactory.createType("Ljava/lang/RuntimeException;");
    DexMethod exceptionInitMethod = itemFactory
        .createMethod(exceptionType, itemFactory.createProto(itemFactory.voidType,
            itemFactory.stringType),
            itemFactory.constructorMethodName);
    DexCode code;
    if (isInstanceInitializer()) {
      // The Java VM Spec requires that a constructor calls an initializer from the super class
      // or another constructor from the current class. For simplicity we do the latter by just
      // calling ourself. This is ok, as the constructor always throws before the recursive call.
      code = generateCodeFromTemplate(3, 2, new ConstStringJumbo(0, tag),
          new ConstStringJumbo(1, message),
          new InvokeStatic(2, logMethod, 0, 1, 0, 0, 0),
          new NewInstance(0, exceptionType),
          new InvokeDirect(2, exceptionInitMethod, 0, 1, 0, 0, 0),
          new Throw(0),
          new InvokeDirect(1, method, 2, 0, 0, 0, 0));

    } else {
      // These methods might not get registered for jumbo string processing, therefore we always
      // use the jumbo string encoding for the const string instruction.
      code = generateCodeFromTemplate(2, 2, new ConstStringJumbo(0, tag),
          new ConstStringJumbo(1, message),
          new InvokeStatic(2, logMethod, 0, 1, 0, 0, 0),
          new NewInstance(0, exceptionType),
          new InvokeDirect(2, exceptionInitMethod, 0, 1, 0, 0, 0),
          new Throw(0));
    }
    Builder builder = builder(this);
    builder.setCode(code);
    setObsolete();
    return builder.build();
  }

  public DexEncodedMethod toTypeSubstitutedMethod(DexMethod method) {
    checkIfObsolete();
    if (this.method == method) {
      return this;
    }
    Builder builder = builder(this);
    builder.setMethod(method);
    // TODO(b/112847660): Fix type fixers that use this method: Staticizer
    // TODO(b/112847660): Fix type fixers that use this method: VerticalClassMerger
    // setObsolete();
    return builder.build();
  }

  public DexEncodedMethod toRenamedMethod(DexString name, DexItemFactory factory) {
    checkIfObsolete();
    if (method.name == name) {
      return this;
    }
    DexMethod newMethod = factory.createMethod(method.holder, method.proto, name);
    Builder builder = builder(this);
    builder.setMethod(newMethod);
    setObsolete();
    return builder.build();
  }

  public DexEncodedMethod toForwardingMethod(DexClass holder, DexDefinitionSupplier definitions) {
    checkIfObsolete();
    // Clear the final flag, as this method is now overwritten. Do this before creating the builder
    // for the forwarding method, as the forwarding method will copy the access flags from this,
    // and if different forwarding methods are created in different subclasses the first could be
    // final.
    accessFlags.demoteFromFinal();
    DexMethod newMethod =
        definitions.dexItemFactory().createMethod(holder.type, method.proto, method.name);
    Invoke.Type type = accessFlags.isStatic() ? Invoke.Type.STATIC : Invoke.Type.SUPER;
    Builder builder = builder(this);
    builder.setMethod(newMethod);
    if (accessFlags.isAbstract()) {
      // If the forwarding target is abstract, we can just create an abstract method. While it
      // will not actually forward, it will create the same exception when hit at runtime.
      builder.accessFlags.setAbstract();
    } else {
      // Create code that forwards the call to the target.
      DexClass target = definitions.definitionFor(method.holder);
      builder.setCode(
          new SynthesizedCode(
              callerPosition ->
                  new ForwardMethodSourceCode(
                      accessFlags.isStatic() ? null : holder.type,
                      newMethod,
                      newMethod,
                      accessFlags.isStatic() ? null : method.holder,
                      method,
                      type,
                      callerPosition,
                      target.isInterface()),
              registry -> {
                if (accessFlags.isStatic()) {
                  registry.registerInvokeStatic(method);
                } else {
                  registry.registerInvokeSuper(method);
                }
              }));
      builder.accessFlags.setBridge();
    }
    builder.accessFlags.setSynthetic();
    // Note that we are not marking this instance obsolete, since it is not: the newly synthesized
    // forwarding method has a separate code that literally forwards to the current method.
    return builder.build();
  }

  public DexEncodedMethod toStaticMethodWithoutThis() {
    checkIfObsolete();
    assert !accessFlags.isStatic();
    Builder builder =
        builder(this).promoteToStatic().unsetOptimizationInfo().withoutThisParameter();
    DexEncodedMethod method = builder.build();
    method.copyMetadata(this);
    setObsolete();
    return method;
  }

  /** Rewrites the code in this method to have JumboString bytecode if required by mapping. */
  public DexCode rewriteCodeWithJumboStrings(
      ObjectToOffsetMapping mapping, DexItemFactory factory, boolean force) {
    checkIfObsolete();
    assert code == null || code.isDexCode();
    if (code == null) {
      return null;
    }
    DexCode code = this.code.asDexCode();
    DexString firstJumboString = null;
    if (force) {
      firstJumboString = mapping.getFirstString();
    } else {
      assert code.highestSortingString != null
          || Arrays.stream(code.instructions).noneMatch(Instruction::isConstString);
      assert Arrays.stream(code.instructions).noneMatch(Instruction::isDexItemBasedConstString);
      if (code.highestSortingString != null
          && mapping.getOffsetFor(code.highestSortingString) > Constants.MAX_NON_JUMBO_INDEX) {
        firstJumboString = mapping.getFirstJumboString();
      }
    }
    if (firstJumboString != null) {
      JumboStringRewriter rewriter = new JumboStringRewriter(this, firstJumboString, factory);
      return rewriter.rewrite();
    }
    return code;
  }

  public String codeToString() {
    checkIfObsolete();
    return code == null ? "<no code>" : code.toString(this, null);
  }

  @Override
  public DexMethod getKey() {
    // Here, we can't check if the current instance of DexEncodedMethod is obsolete
    // because itself can be used as a key while making mappings to avoid using obsolete instances.
    return method;
  }

  @Override
  public DexReference toReference() {
    checkIfObsolete();
    return method;
  }

  @Override
  public boolean isDexEncodedMethod() {
    checkIfObsolete();
    return true;
  }

  @Override
  public DexEncodedMethod asDexEncodedMethod() {
    checkIfObsolete();
    return this;
  }

  public boolean hasAnnotation() {
    checkIfObsolete();
    return !annotations.isEmpty() || !parameterAnnotationsList.isEmpty();
  }

  public void registerCodeReferences(UseRegistry registry) {
    checkIfObsolete();
    if (code != null) {
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Registering definitions reachable from `%s`.", method);
      }
      code.registerCodeReferences(registry);
    }
  }

  public static int slowCompare(DexEncodedMethod m1, DexEncodedMethod m2) {
    return m1.method.slowCompareTo(m2.method);
  }

  public static class ClassInlinerEligibility {
    public final boolean returnsReceiver;

    public ClassInlinerEligibility(boolean returnsReceiver) {
      this.returnsReceiver = returnsReceiver;
    }
  }

  public static class TrivialInitializer {
    private TrivialInitializer() {
    }

    // Defines instance trivial initialized, see details in comments
    // to CodeRewriter::computeInstanceInitializerInfo(...)
    public static final class TrivialInstanceInitializer extends TrivialInitializer {
      public static final TrivialInstanceInitializer INSTANCE =
          new TrivialInstanceInitializer();
    }

    // Defines class trivial initialized, see details in comments
    // to CodeRewriter::computeClassInitializerInfo(...)
    public static final class TrivialClassInitializer extends TrivialInitializer {
      public final DexField field;

      public TrivialClassInitializer(DexField field) {
        this.field = field;
      }
    }
  }

  public static class DefaultOptimizationInfoImpl implements OptimizationInfo {
    public static final OptimizationInfo DEFAULT_INSTANCE = new DefaultOptimizationInfoImpl();

    public static int UNKNOWN_RETURNED_ARGUMENT = -1;
    public static boolean UNKNOWN_NEVER_RETURNS_NULL = false;
    public static boolean UNKNOWN_NEVER_RETURNS_NORMALLY = false;
    public static boolean UNKNOWN_RETURNS_CONSTANT = false;
    public static long UNKNOWN_RETURNED_CONSTANT_NUMBER = 0;
    public static DexString UNKNOWN_RETURNED_CONSTANT_STRING = null;
    public static boolean DOES_NOT_USE_IDNETIFIER_NAME_STRING = false;
    public static boolean UNKNOWN_CHECKS_NULL_RECEIVER_BEFORE_ANY_SIDE_EFFECT = false;
    public static boolean UNKNOWN_TRIGGERS_CLASS_INIT_BEFORE_ANY_SIDE_EFFECT = false;
    public static ClassInlinerEligibility UNKNOWN_CLASS_INLINER_ELIGIBILITY = null;
    public static TrivialInitializer UNKNOWN_TRIVIAL_INITIALIZER = null;
    public static boolean UNKNOWN_INITIALIZER_ENABLING_JAVA_ASSERTIONS = false;
    public static ParameterUsagesInfo UNKNOWN_PARAMETER_USAGE_INFO = null;
    public static boolean UNKNOWN_MAY_HAVE_SIDE_EFFECTS = true;
    public static BitSet NO_NULL_PARAMETER_OR_THROW_FACTS = null;
    public static BitSet NO_NULL_PARAMETER_ON_NORMAL_EXITS_FACTS = null;

    private DefaultOptimizationInfoImpl() {}

    @Override
    public TrivialInitializer getTrivialInitializerInfo() {
      return UNKNOWN_TRIVIAL_INITIALIZER;
    }

    @Override
    public ParameterUsage getParameterUsages(int parameter) {
      assert UNKNOWN_PARAMETER_USAGE_INFO == null;
      return null;
    }

    @Override
    public BitSet getNonNullParamOrThrow() {
      return NO_NULL_PARAMETER_OR_THROW_FACTS;
    }

    @Override
    public BitSet getNonNullParamOnNormalExits() {
      return NO_NULL_PARAMETER_ON_NORMAL_EXITS_FACTS;
    }

    @Override
    public boolean isReachabilitySensitive() {
      return false;
    }

    @Override
    public boolean returnsArgument() {
      return false;
    }

    @Override
    public int getReturnedArgument() {
      assert returnsArgument();
      return UNKNOWN_RETURNED_ARGUMENT;
    }

    @Override
    public boolean neverReturnsNull() {
      return UNKNOWN_NEVER_RETURNS_NULL;
    }

    @Override
    public boolean neverReturnsNormally() {
      return UNKNOWN_NEVER_RETURNS_NORMALLY;
    }

    @Override
    public boolean returnsConstant() {
      return UNKNOWN_RETURNS_CONSTANT;
    }

    @Override
    public boolean returnsConstantNumber() {
      return UNKNOWN_RETURNS_CONSTANT;
    }

    @Override
    public boolean returnsConstantString() {
      return UNKNOWN_RETURNS_CONSTANT;
    }

    @Override
    public ClassInlinerEligibility getClassInlinerEligibility() {
      return UNKNOWN_CLASS_INLINER_ELIGIBILITY;
    }

    @Override
    public long getReturnedConstantNumber() {
      assert returnsConstantNumber();
      return UNKNOWN_RETURNED_CONSTANT_NUMBER;
    }

    @Override
    public DexString getReturnedConstantString() {
      assert returnsConstantString();
      return UNKNOWN_RETURNED_CONSTANT_STRING;
    }

    @Override
    public boolean isInitializerEnablingJavaAssertions() {
      return UNKNOWN_INITIALIZER_ENABLING_JAVA_ASSERTIONS;
    }

    @Override
    public boolean useIdentifierNameString() {
      return DOES_NOT_USE_IDNETIFIER_NAME_STRING;
    }

    @Override
    public boolean forceInline() {
      return false;
    }

    @Override
    public boolean neverInline() {
      return false;
    }

    @Override
    public boolean checksNullReceiverBeforeAnySideEffect() {
      return UNKNOWN_CHECKS_NULL_RECEIVER_BEFORE_ANY_SIDE_EFFECT;
    }

    @Override
    public boolean triggersClassInitBeforeAnySideEffect() {
      return UNKNOWN_TRIGGERS_CLASS_INIT_BEFORE_ANY_SIDE_EFFECT;
    }

    @Override
    public boolean mayHaveSideEffects() {
      return UNKNOWN_MAY_HAVE_SIDE_EFFECTS;
    }

    @Override
    public UpdatableOptimizationInfo mutableCopy() {
      return new OptimizationInfoImpl();
    }
  }

  public static class OptimizationInfoImpl implements UpdatableOptimizationInfo {

    private int returnedArgument = DefaultOptimizationInfoImpl.UNKNOWN_RETURNED_ARGUMENT;
    private boolean mayHaveSideEffects = DefaultOptimizationInfoImpl.UNKNOWN_MAY_HAVE_SIDE_EFFECTS;
    private boolean neverReturnsNull = DefaultOptimizationInfoImpl.UNKNOWN_NEVER_RETURNS_NULL;
    private boolean neverReturnsNormally =
        DefaultOptimizationInfoImpl.UNKNOWN_NEVER_RETURNS_NORMALLY;
    private boolean returnsConstantNumber = DefaultOptimizationInfoImpl.UNKNOWN_RETURNS_CONSTANT;
    private long returnedConstantNumber =
        DefaultOptimizationInfoImpl.UNKNOWN_RETURNED_CONSTANT_NUMBER;
    private boolean returnsConstantString = DefaultOptimizationInfoImpl.UNKNOWN_RETURNS_CONSTANT;
    private DexString returnedConstantString =
        DefaultOptimizationInfoImpl.UNKNOWN_RETURNED_CONSTANT_STRING;
    private InlinePreference inlining = InlinePreference.Default;
    private boolean useIdentifierNameString =
        DefaultOptimizationInfoImpl.DOES_NOT_USE_IDNETIFIER_NAME_STRING;
    private boolean checksNullReceiverBeforeAnySideEffect =
        DefaultOptimizationInfoImpl.UNKNOWN_CHECKS_NULL_RECEIVER_BEFORE_ANY_SIDE_EFFECT;
    private boolean triggersClassInitBeforeAnySideEffect =
        DefaultOptimizationInfoImpl.UNKNOWN_TRIGGERS_CLASS_INIT_BEFORE_ANY_SIDE_EFFECT;
    // Stores information about instance methods and constructors for
    // class inliner, null value indicates that the method is not eligible.
    private ClassInlinerEligibility classInlinerEligibility =
        DefaultOptimizationInfoImpl.UNKNOWN_CLASS_INLINER_ELIGIBILITY;
    private TrivialInitializer trivialInitializerInfo =
        DefaultOptimizationInfoImpl.UNKNOWN_TRIVIAL_INITIALIZER;
    private boolean initializerEnablingJavaAssertions =
        DefaultOptimizationInfoImpl.UNKNOWN_INITIALIZER_ENABLING_JAVA_ASSERTIONS;
    private ParameterUsagesInfo parametersUsages =
        DefaultOptimizationInfoImpl.UNKNOWN_PARAMETER_USAGE_INFO;
    // Stores information about nullability hint per parameter. If set, that means, the method
    // somehow (e.g., null check, such as arg != null, or using checkParameterIsNotNull) ensures
    // the corresponding parameter is not null, or throws NPE before any other side effects.
    // This info is used by {@link UninstantiatedTypeOptimization#rewriteInvoke} that replaces an
    // invocation with null throwing code if an always-null argument is passed. Also used by Inliner
    // to give a credit to null-safe code, e.g., Kotlin's null safe argument.
    // Note that this bit set takes into account the receiver for instance methods.
    private BitSet nonNullParamOrThrow = null;
    // Stores information about nullability facts per parameter. If set, that means, the method
    // somehow (e.g., null check, such as arg != null, or NPE-throwing instructions such as array
    // access or another invocation) ensures the corresponding parameter is not null, and that is
    // guaranteed until the normal exits. That is, if the invocation of this method is finished
    // normally, the recorded parameter is definitely not null. These facts are used to propagate
    // non-null information through {@link NonNullTracker}.
    // Note that this bit set takes into account the receiver for instance methods.
    private BitSet nonNullParamOnNormalExits = null;
    private boolean reachabilitySensitive = false;

    private OptimizationInfoImpl() {
      // Intentionally left empty, just use the default values.
    }

    private OptimizationInfoImpl(OptimizationInfoImpl template) {
      returnedArgument = template.returnedArgument;
      neverReturnsNull = template.neverReturnsNull;
      neverReturnsNormally = template.neverReturnsNormally;
      returnsConstantNumber = template.returnsConstantNumber;
      returnedConstantNumber = template.returnedConstantNumber;
      returnsConstantString = template.returnsConstantString;
      returnedConstantString = template.returnedConstantString;
      inlining = template.inlining;
      useIdentifierNameString = template.useIdentifierNameString;
      checksNullReceiverBeforeAnySideEffect = template.checksNullReceiverBeforeAnySideEffect;
      triggersClassInitBeforeAnySideEffect = template.triggersClassInitBeforeAnySideEffect;
      classInlinerEligibility = template.classInlinerEligibility;
      trivialInitializerInfo = template.trivialInitializerInfo;
      initializerEnablingJavaAssertions = template.initializerEnablingJavaAssertions;
      parametersUsages = template.parametersUsages;
      nonNullParamOrThrow = template.nonNullParamOrThrow;
      nonNullParamOnNormalExits = template.nonNullParamOnNormalExits;
      reachabilitySensitive = template.reachabilitySensitive;
    }

    @Override
    public TrivialInitializer getTrivialInitializerInfo() {
      return trivialInitializerInfo;
    }

    @Override
    public ParameterUsage getParameterUsages(int parameter) {
      return parametersUsages == null ? null : parametersUsages.getParameterUsage(parameter);
    }

    @Override
    public BitSet getNonNullParamOrThrow() {
      return nonNullParamOrThrow;
    }

    @Override
    public BitSet getNonNullParamOnNormalExits() {
      return nonNullParamOnNormalExits;
    }

    @Override
    public boolean isReachabilitySensitive() {
      return reachabilitySensitive;
    }

    @Override
    public boolean returnsArgument() {
      return returnedArgument != -1;
    }

    @Override
    public int getReturnedArgument() {
      assert returnsArgument();
      return returnedArgument;
    }

    @Override
    public boolean neverReturnsNull() {
      return neverReturnsNull;
    }

    @Override
    public boolean neverReturnsNormally() {
      return neverReturnsNormally;
    }

    @Override
    public boolean returnsConstant() {
      assert !(returnsConstantNumber && returnsConstantString);
      return returnsConstantNumber || returnsConstantString;
    }

    @Override
    public boolean returnsConstantNumber() {
      return returnsConstantNumber;
    }

    @Override
    public boolean returnsConstantString() {
      return returnsConstantString;
    }

    @Override
    public ClassInlinerEligibility getClassInlinerEligibility() {
      return classInlinerEligibility;
    }

    @Override
    public long getReturnedConstantNumber() {
      assert returnsConstant();
      return returnedConstantNumber;
    }

    @Override
    public DexString getReturnedConstantString() {
      assert returnsConstant();
      return returnedConstantString;
    }

    @Override
    public boolean isInitializerEnablingJavaAssertions() {
      return initializerEnablingJavaAssertions;
    }

    @Override
    public boolean useIdentifierNameString() {
      return useIdentifierNameString;
    }

    @Override
    public boolean forceInline() {
      return inlining == InlinePreference.ForceInline;
    }

    @Override
    public boolean neverInline() {
      return inlining == InlinePreference.NeverInline;
    }

    @Override
    public boolean checksNullReceiverBeforeAnySideEffect() {
      return checksNullReceiverBeforeAnySideEffect;
    }

    @Override
    public boolean triggersClassInitBeforeAnySideEffect() {
      return triggersClassInitBeforeAnySideEffect;
    }

    @Override
    public boolean mayHaveSideEffects() {
      return mayHaveSideEffects;
    }

    @Override
    public void setParameterUsages(ParameterUsagesInfo parametersUsages) {
      this.parametersUsages = parametersUsages;
    }

    @Override
    public void setNonNullParamOrThrow(BitSet facts) {
      this.nonNullParamOrThrow = facts;
    }

    @Override
    public void setNonNullParamOnNormalExits(BitSet facts) {
      this.nonNullParamOnNormalExits = facts;
    }

    @Override
    public void setReachabilitySensitive(boolean reachabilitySensitive) {
      this.reachabilitySensitive = reachabilitySensitive;
    }

    @Override
    public void setClassInlinerEligibility(ClassInlinerEligibility eligibility) {
      this.classInlinerEligibility = eligibility;
    }

    @Override
    public void setTrivialInitializer(TrivialInitializer info) {
      this.trivialInitializerInfo = info;
    }

    @Override
    public void setInitializerEnablingJavaAssertions() {
      this.initializerEnablingJavaAssertions = true;
    }

    @Override
    public void markReturnsArgument(int argument) {
      assert argument >= 0;
      assert returnedArgument == -1 || returnedArgument == argument;
      returnedArgument = argument;
    }

    @Override
    public void markMayNotHaveSideEffects() {
      mayHaveSideEffects = false;
    }

    @Override
    public void markNeverReturnsNull() {
      neverReturnsNull = true;
    }

    @Override
    public void markNeverReturnsNormally() {
      neverReturnsNormally = true;
    }

    @Override
    public void markReturnsConstantNumber(long value) {
      assert !returnsConstantString;
      assert !returnsConstantNumber || returnedConstantNumber == value;
      returnsConstantNumber = true;
      returnedConstantNumber = value;
    }

    @Override
    public void markReturnsConstantString(DexString value) {
      assert !returnsConstantNumber;
      assert !returnsConstantString || returnedConstantString == value;
      returnsConstantString = true;
      returnedConstantString = value;
    }

    @Override
    public void markForceInline() {
      // For concurrent scenarios we should allow the flag to be already set
      assert inlining == InlinePreference.Default || inlining == InlinePreference.ForceInline;
      inlining = InlinePreference.ForceInline;
    }

    @Override
    public void unsetForceInline() {
      // For concurrent scenarios we should allow the flag to be already unset
      assert inlining == InlinePreference.Default || inlining == InlinePreference.ForceInline;
      inlining = InlinePreference.Default;
    }

    @Override
    public void markNeverInline() {
      // For concurrent scenarios we should allow the flag to be already set
      assert inlining == InlinePreference.Default || inlining == InlinePreference.NeverInline;
      inlining = InlinePreference.NeverInline;
    }

    @Override
    public void markUseIdentifierNameString() {
      useIdentifierNameString = true;
    }

    @Override
    public void markCheckNullReceiverBeforeAnySideEffect(boolean mark) {
      checksNullReceiverBeforeAnySideEffect = mark;
    }

    @Override
    public void markTriggerClassInitBeforeAnySideEffect(boolean mark) {
      triggersClassInitBeforeAnySideEffect = mark;
    }

    @Override
    public UpdatableOptimizationInfo mutableCopy() {
      assert this != DefaultOptimizationInfoImpl.DEFAULT_INSTANCE;
      return new OptimizationInfoImpl(this);
    }
  }

  public OptimizationInfo getOptimizationInfo() {
    checkIfObsolete();
    return optimizationInfo;
  }

  public synchronized UpdatableOptimizationInfo getMutableOptimizationInfo() {
    checkIfObsolete();
    if (optimizationInfo == DefaultOptimizationInfoImpl.DEFAULT_INSTANCE) {
      optimizationInfo = optimizationInfo.mutableCopy();
    }
    return (UpdatableOptimizationInfo) optimizationInfo;
  }

  public void setOptimizationInfo(UpdatableOptimizationInfo info) {
    checkIfObsolete();
    optimizationInfo = info;
  }

  public void copyMetadata(DexEncodedMethod from) {
    checkIfObsolete();
    // Record that the current method uses identifier name string if the inlinee did so.
    if (from.getOptimizationInfo().useIdentifierNameString()) {
      getMutableOptimizationInfo().markUseIdentifierNameString();
    }
    if (from.classFileVersion > classFileVersion) {
      upgradeClassFileVersion(from.getClassFileVersion());
    }
  }

  private static Builder builder(DexEncodedMethod from) {
    return new Builder(from);
  }

  private static class Builder {

    private DexMethod method;
    private final MethodAccessFlags accessFlags;
    private final DexAnnotationSet annotations;
    private final ParameterAnnotationsList parameterAnnotations;
    private Code code;
    private CompilationState compilationState;
    private OptimizationInfo optimizationInfo;
    private final int classFileVersion;

    private Builder(DexEncodedMethod from) {
      // Copy all the mutable state of a DexEncodedMethod here.
      method = from.method;
      accessFlags = from.accessFlags.copy();
      annotations = from.annotations;
      parameterAnnotations = from.parameterAnnotationsList;
      code = from.code;
      compilationState = from.compilationState;
      optimizationInfo = from.optimizationInfo.mutableCopy();
      classFileVersion = from.classFileVersion;
    }

    public void setMethod(DexMethod method) {
      this.method = method;
    }

    public Builder promoteToStatic() {
      this.accessFlags.promoteToStatic();
      return this;
    }

    public Builder unsetOptimizationInfo() {
      optimizationInfo = DefaultOptimizationInfoImpl.DEFAULT_INSTANCE;
      return this;
    }

    public Builder withoutThisParameter() {
      assert code != null;
      if (code.isDexCode()) {
        code = code.asDexCode().withoutThisParameter();
      } else {
        throw new Unreachable("Code " + code.getClass().getSimpleName() + " is not supported.");
      }
      return this;
    }

    public void setCode(Code code) {
      this.code = code;
    }

    public DexEncodedMethod build() {
      assert method != null;
      assert accessFlags != null;
      assert annotations != null;
      assert parameterAnnotations != null;
      DexEncodedMethod result =
          new DexEncodedMethod(
              method, accessFlags, annotations, parameterAnnotations, code, classFileVersion);
      result.compilationState = compilationState;
      result.optimizationInfo = optimizationInfo;
      return result;
    }
  }

  @Override
  public DexEncodedMethod asResultOfResolve() {
    checkIfObsolete();
    return this;
  }

  @Override
  public DexEncodedMethod asSingleTarget() {
    checkIfObsolete();
    return this;
  }

  @Override
  public boolean hasSingleTarget() {
    checkIfObsolete();
    return true;
  }

  @Override
  public List<DexEncodedMethod> asListOfTargets() {
    checkIfObsolete();
    return Collections.singletonList(this);
  }

  @Override
  public void forEachTarget(Consumer<DexEncodedMethod> consumer) {
    checkIfObsolete();
    consumer.accept(this);
  }

}
