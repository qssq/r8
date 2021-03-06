// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import static com.android.tools.r8.naming.IdentifierNameStringUtils.identifyIdentifier;
import static com.android.tools.r8.naming.IdentifierNameStringUtils.isReflectionMethod;
import static com.android.tools.r8.shaking.AnnotationRemover.shouldKeepAnnotation;
import static com.android.tools.r8.shaking.EnqueuerUtils.extractProgramFieldDefinitions;

import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.errors.Unimplemented;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.experimental.graphinfo.AnnotationGraphNode;
import com.android.tools.r8.experimental.graphinfo.ClassGraphNode;
import com.android.tools.r8.experimental.graphinfo.FieldGraphNode;
import com.android.tools.r8.experimental.graphinfo.GraphConsumer;
import com.android.tools.r8.experimental.graphinfo.GraphEdgeInfo;
import com.android.tools.r8.experimental.graphinfo.GraphEdgeInfo.EdgeKind;
import com.android.tools.r8.experimental.graphinfo.GraphNode;
import com.android.tools.r8.experimental.graphinfo.KeepRuleGraphNode;
import com.android.tools.r8.experimental.graphinfo.MethodGraphNode;
import com.android.tools.r8.graph.AppInfo.ResolutionResult;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.Descriptor;
import com.android.tools.r8.graph.DexAnnotation;
import com.android.tools.r8.graph.DexCallSite;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexDefinition;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItem;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexMethodHandle;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexReference;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.KeyedDexItem;
import com.android.tools.r8.graph.PresortedComparable;
import com.android.tools.r8.ir.code.ArrayPut;
import com.android.tools.r8.ir.code.ConstantValueUtils;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InvokeMethod;
import com.android.tools.r8.ir.code.InvokeVirtual;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.desugar.LambdaDescriptor;
import com.android.tools.r8.logging.Log;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.references.Reference;
import com.android.tools.r8.references.TypeReference;
import com.android.tools.r8.shaking.RootSetBuilder.ConsequentRootSet;
import com.android.tools.r8.shaking.RootSetBuilder.IfRuleEvaluator;
import com.android.tools.r8.shaking.RootSetBuilder.RootSet;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.StringDiagnostic;
import com.android.tools.r8.utils.Timing;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import it.unimi.dsi.fastutil.objects.Object2BooleanArrayMap;
import it.unimi.dsi.fastutil.objects.Object2BooleanMap;
import java.lang.reflect.InvocationHandler;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Approximates the runtime dependencies for the given set of roots.
 * <p>
 * <p>The implementation filters the static call-graph with liveness information on classes to
 * remove virtual methods that are reachable by their static type but are unreachable at runtime as
 * they are not visible from any instance.
 * <p>
 * <p>As result of the analysis, an instance of {@link AppInfoWithLiveness} is returned. See the
 * field descriptions for details.
 */
public class Enqueuer {

  private final boolean forceProguardCompatibility;
  private boolean tracingMainDex = false;

  private final AppInfoWithSubtyping appInfo;
  private final AppView<? extends AppInfoWithSubtyping> appView;
  private final InternalOptions options;
  private RootSet rootSet;
  private ProguardClassFilter dontWarnPatterns;

  private final Map<DexType, Set<TargetWithContext<DexMethod>>> virtualInvokes =
      Maps.newIdentityHashMap();
  private final Map<DexType, Set<TargetWithContext<DexMethod>>> interfaceInvokes =
      Maps.newIdentityHashMap();
  private final Map<DexType, Set<TargetWithContext<DexMethod>>> superInvokes =
      Maps.newIdentityHashMap();
  private final Map<DexType, Set<TargetWithContext<DexMethod>>> directInvokes =
      Maps.newIdentityHashMap();
  private final Map<DexType, Set<TargetWithContext<DexMethod>>> staticInvokes =
      Maps.newIdentityHashMap();
  private final Map<DexType, Set<TargetWithContext<DexField>>> instanceFieldsWritten =
      Maps.newIdentityHashMap();
  private final Map<DexType, Set<TargetWithContext<DexField>>> instanceFieldsRead =
      Maps.newIdentityHashMap();
  private final Map<DexType, Set<TargetWithContext<DexField>>> staticFieldsRead =
      Maps.newIdentityHashMap();
  private final Map<DexType, Set<TargetWithContext<DexField>>> staticFieldsWritten =
      Maps.newIdentityHashMap();
  private final Set<DexField> staticFieldsWrittenOutsideEnclosingStaticInitializer =
      Sets.newIdentityHashSet();
  private final Set<DexCallSite> callSites = Sets.newIdentityHashSet();

  private final Set<DexReference> identifierNameStrings = Sets.newIdentityHashSet();

  // Canonicalization of external graph-nodes and edge info.
  private final Map<DexItem, AnnotationGraphNode> annotationNodes = new IdentityHashMap<>();
  private final Map<DexType, ClassGraphNode> classNodes = new IdentityHashMap<>();
  private final Map<DexMethod, MethodGraphNode> methodNodes = new IdentityHashMap<>();
  private final Map<DexField, FieldGraphNode> fieldNodes = new IdentityHashMap<>();
  private final Map<ProguardKeepRule, KeepRuleGraphNode> ruleNodes = new IdentityHashMap<>();
  private final Map<EdgeKind, GraphEdgeInfo> reasonInfo = new IdentityHashMap<>();

  /**
   * Set of method signatures used in invoke-super instructions that either cannot be resolved or
   * resolve to a private method (leading to an IllegalAccessError).
   */
  private final Set<DexMethod> brokenSuperInvokes = Sets.newIdentityHashSet();
  /**
   * This map keeps a view of all virtual methods that are reachable from virtual invokes. A method
   * is reachable even if no live subtypes exist, so this is not sufficient for inclusion in the
   * live set.
   */
  private final Map<DexType, SetWithReason<DexEncodedMethod>> reachableVirtualMethods = Maps
      .newIdentityHashMap();
  /**
   * Tracks the dependency between a method and the super-method it calls, if any. Used to make
   * super methods become live when they become reachable from a live sub-method.
   */
  private final Map<DexEncodedMethod, Set<DexEncodedMethod>> superInvokeDependencies = Maps
      .newIdentityHashMap();
  /**
   * Set of instance fields that can be reached by read/write operations.
   */
  private final Map<DexType, SetWithReason<DexEncodedField>> reachableInstanceFields = Maps
      .newIdentityHashMap();

  /**
   * Set of types that are mentioned in the program. We at least need an empty abstract class item
   * for these.
   */
  private final Set<DexType> liveTypes = Sets.newIdentityHashSet();

  /** Set of annotation types that are instantiated. */
  private final SetWithReason<DexAnnotation> liveAnnotations =
      new SetWithReason<>(this::registerAnnotation);
  /** Set of types that are actually instantiated. These cannot be abstract. */
  private final SetWithReason<DexType> instantiatedTypes = new SetWithReason<>(this::registerType);
  /**
   * Set of methods that are the immediate target of an invoke. They might not actually be live but
   * are required so that invokes can find the method. If a method is only a target but not live,
   * its implementation may be removed and it may be marked abstract.
   */
  private final SetWithReason<DexEncodedMethod> targetedMethods =
      new SetWithReason<>(this::registerMethod);
  /**
   * Set of program methods that are used as the bootstrap method for an invoke-dynamic instruction.
   */
  private final Set<DexMethod> bootstrapMethods = Sets.newIdentityHashSet();
  /**
   * Set of direct methods that are the immediate target of an invoke-dynamic.
   */
  private final Set<DexMethod> methodsTargetedByInvokeDynamic = Sets.newIdentityHashSet();
  /**
   * Set of direct lambda methods that are the immediate target of an invoke-dynamic.
   */
  private final Set<DexMethod> lambdaMethodsTargetedByInvokeDynamic = Sets.newIdentityHashSet();
  /**
   * Set of virtual methods that are the immediate target of an invoke-direct.
   * */
  private final Set<DexMethod> virtualMethodsTargetedByInvokeDirect = Sets.newIdentityHashSet();
  /**
   * Set of methods that belong to live classes and can be reached by invokes. These need to be
   * kept.
   */
  private final SetWithReason<DexEncodedMethod> liveMethods =
      new SetWithReason<>(this::registerMethod);

  /**
   * Set of fields that belong to live classes and can be reached by invokes. These need to be kept.
   */
  private final SetWithReason<DexEncodedField> liveFields =
      new SetWithReason<>(this::registerField);

  /**
   * Set of service types (from META-INF/services/) that may have been instantiated reflectively via
   * ServiceLoader.load() or ServiceLoader.loadInstalled().
   */
  private final Set<DexType> instantiatedAppServices = Sets.newIdentityHashSet();

  /**
   * Set of interface types for which a lambda expression can be reached. These never have a single
   * interface implementation.
   */
  private final SetWithReason<DexType> instantiatedLambdas =
      new SetWithReason<>(this::registerType);

  /**
   * A queue of items that need processing. Different items trigger different actions:
   */
  private final Queue<Action> workList = Queues.newArrayDeque();

  /**
   * A queue of items that have been added to try to keep Proguard compatibility.
   */
  private final Queue<Action> proguardCompatibilityWorkList = Queues.newArrayDeque();

  /**
   * A set of methods that need code inspection for Java reflection in use.
   */
  private final Set<DexEncodedMethod> pendingReflectiveUses = Sets.newLinkedHashSet();

  /**
   * A cache for DexMethod that have been marked reachable.
   */
  private final Set<DexMethod> virtualTargetsMarkedAsReachable = Sets.newIdentityHashSet();

  /**
   * A set of references we have reported missing to dedupe warnings.
   */
  private final Set<DexReference> reportedMissing = Sets.newIdentityHashSet();

  /**
   * A set of references that we are keeping due to keep rules. This may differ from the root set
   * due to dependent keep rules.
   */
  private final Set<DexReference> pinnedItems = Sets.newIdentityHashSet();

  /**
   * A map from classes to annotations that need to be processed should the classes ever become
   * live.
   */
  private final Map<DexType, Set<DexAnnotation>> deferredAnnotations = new IdentityHashMap<>();

  /**
   * Set of keep rules generated for Proguard compatibility in Proguard compatibility mode.
   */
  private final ProguardConfiguration.Builder compatibility;

  private final GraphConsumer keptGraphConsumer;

  public Enqueuer(
      AppView<? extends AppInfoWithSubtyping> appView,
      InternalOptions options,
      GraphConsumer keptGraphConsumer) {
    this(appView, options, keptGraphConsumer, null);
  }

  public Enqueuer(
      AppView<? extends AppInfoWithSubtyping> appView,
      InternalOptions options,
      GraphConsumer keptGraphConsumer,
      ProguardConfiguration.Builder compatibility) {
    assert appView.appServices() != null;
    this.appInfo = appView.appInfo();
    this.appView = appView;
    this.compatibility = compatibility;
    this.forceProguardCompatibility = options.forceProguardCompatibility;
    this.keptGraphConsumer = keptGraphConsumer;
    this.options = options;
  }

  private Set<DexField> staticFieldsWrittenOnlyInEnclosingStaticInitializer() {
    Set<DexField> result = Sets.newIdentityHashSet();
    Set<DexField> visited = Sets.newIdentityHashSet();
    for (Set<TargetWithContext<DexField>> targetsWithContext : staticFieldsWritten.values()) {
      for (TargetWithContext<DexField> targetWithContext : targetsWithContext) {
        DexField staticFieldWritten = targetWithContext.target;
        if (!visited.add(staticFieldWritten)) {
          continue;
        }
        DexEncodedField encodedStaticFieldWritten = appInfo.resolveField(staticFieldWritten);
        if (encodedStaticFieldWritten != null
            && encodedStaticFieldWritten.isProgramField(appInfo)) {
          result.add(encodedStaticFieldWritten.field);
        }
      }
    }
    result.removeAll(staticFieldsWrittenOutsideEnclosingStaticInitializer);
    result.removeAll(
        pinnedItems.stream()
            .filter(DexReference::isDexField)
            .map(DexReference::asDexField)
            .collect(Collectors.toList()));
    return result;
  }

  private static <T> SetWithReason<T> newSetWithoutReasonReporter() {
    return new SetWithReason<>((f, r) -> {});
  }

  private void enqueueRootItems(Map<DexReference, Set<ProguardKeepRule>> items) {
    items.entrySet().forEach(this::enqueueRootItem);
  }

  private void enqueueRootItem(Entry<DexReference, Set<ProguardKeepRule>> root) {
    DexDefinition item = appView.definitionFor(root.getKey());
    if (item != null) {
      enqueueRootItem(item, root.getValue());
    } else {
      // TODO(b/123923324): Verify that root items are present.
      // assert false : "Expected root item `" + root.getKey().toSourceString() + "` to be present";
    }
  }

  private void enqueueRootItem(DexDefinition item, Set<ProguardKeepRule> rules) {
    assert !rules.isEmpty();
    if (keptGraphConsumer != null) {
      GraphNode node = getGraphNode(item.toReference());
      for (ProguardKeepRule rule : rules) {
        registerEdge(node, KeepReason.dueToKeepRule(rule));
      }
    }
    internalEnqueueRootItem(item, KeepReason.dueToKeepRule(rules.iterator().next()));
  }

  private void enqueueRootItem(DexDefinition item, KeepReason reason) {
    if (keptGraphConsumer != null) {
      registerEdge(getGraphNode(item.toReference()), reason);
    }
    internalEnqueueRootItem(item, reason);
  }

  private void internalEnqueueRootItem(DexDefinition item, KeepReason reason) {
    // TODO(b/120959039): do we need to propagate the reason to the action now?
    if (item.isDexClass()) {
      DexClass clazz = item.asDexClass();
      workList.add(Action.markInstantiated(clazz, reason));
      if (clazz.hasDefaultInitializer()) {
        if (forceProguardCompatibility) {
          ProguardKeepRule compatRule =
            ProguardConfigurationUtils.buildDefaultInitializerKeepRule(clazz);
          proguardCompatibilityWorkList.add(
              Action.markMethodLive(
                  clazz.getDefaultInitializer(),
                  KeepReason.dueToProguardCompatibilityKeepRule(compatRule)));
        }
        if (clazz.isExternalizable(appView)) {
          workList.add(Action.markMethodLive(clazz.getDefaultInitializer(), reason));
        }
      }
    } else if (item.isDexEncodedField()) {
      workList.add(Action.markFieldKept(item.asDexEncodedField(), reason));
    } else if (item.isDexEncodedMethod()) {
      workList.add(Action.markMethodKept(item.asDexEncodedMethod(), reason));
    } else {
      throw new IllegalArgumentException(item.toString());
    }
    pinnedItems.add(item.toReference());
  }

  private void enqueueFirstNonSerializableClassInitializer(DexClass clazz, KeepReason reason) {
    assert clazz.isProgramClass() && clazz.isSerializable(appView);
    // Climb up the class hierarchy. Break out if the definition is not found, or hit the library
    // classes which are kept by definition, or encounter the first non-serializable class.
    while (clazz != null && clazz.isProgramClass() && clazz.isSerializable(appView)) {
      clazz = appView.definitionFor(clazz.superType);
    }
    if (clazz != null && clazz.isProgramClass() && clazz.hasDefaultInitializer()) {
      workList.add(Action.markMethodLive(clazz.getDefaultInitializer(), reason));
    }
  }

  private void enqueueHolderIfDependentNonStaticMember(
      DexClass holder, Map<DexReference, Set<ProguardKeepRule>> dependentItems) {
    // Check if any dependent members are not static, and in that case enqueue the class as well.
    // Having a dependent rule like -keepclassmembers with non static items indicates that class
    // instances will be present even if tracing do not find any instantiation. See b/115867670.
    for (Entry<DexReference, Set<ProguardKeepRule>> entry : dependentItems.entrySet()) {
      DexReference dependentItem = entry.getKey();
      if (dependentItem.isDexType()) {
        continue;
      }
      DexDefinition dependentDefinition = appView.definitionFor(dependentItem);
      if (dependentDefinition == null) {
        assert false;
        continue;
      }
      if (!dependentDefinition.isStaticMember()) {
        enqueueRootItem(holder, entry.getValue());
        // Enough to enqueue the known holder once.
        break;
      }
    }
  }

  //
  // Things to do with registering events. This is essentially the interface for byte-code
  // traversals.
  //

  private <S extends DexItem, T extends Descriptor<S, T>> boolean registerItemWithTarget(
      Map<DexType, Set<T>> seen, T item) {
    assert item.isDexField() || item.isDexMethod();
    DexType itemHolder =
        item.isDexField()
            ? item.asDexField().holder
            : item.asDexMethod().holder;
    DexType holder = itemHolder.toBaseType(appView.dexItemFactory());
    if (!holder.isClassType()) {
      return false;
    }
    markTypeAsLive(holder);
    return seen.computeIfAbsent(itemHolder, (ignore) -> Sets.newIdentityHashSet()).add(item);
  }

  private <S extends DexItem, T extends Descriptor<S, T>> boolean registerItemWithTargetAndContext(
      Map<DexType, Set<TargetWithContext<T>>> seen, T item, DexEncodedMethod context) {
    assert item.isDexField() || item.isDexMethod();
    DexType itemHolder =
        item.isDexField()
            ? item.asDexField().holder
            : item.asDexMethod().holder;
    DexType holder = itemHolder.toBaseType(appView.dexItemFactory());
    if (!holder.isClassType()) {
      return false;
    }
    markTypeAsLive(holder);
    return seen
        .computeIfAbsent(itemHolder, (ignore) -> new HashSet<>())
        .add(new TargetWithContext<>(item, context));
  }

  private class UseRegistry extends com.android.tools.r8.graph.UseRegistry {

    private final DexEncodedMethod currentMethod;

    private UseRegistry(DexItemFactory factory, DexEncodedMethod currentMethod) {
      super(factory);
      this.currentMethod = currentMethod;
    }

    @Override
    public boolean registerInvokeVirtual(DexMethod method) {
      return registerInvokeVirtual(method, KeepReason.invokedFrom(currentMethod));
    }

    boolean registerInvokeVirtual(DexMethod method, KeepReason keepReason) {
      if (method == appView.dexItemFactory().classMethods.newInstance
          || method == appView.dexItemFactory().constructorMethods.newInstance) {
        pendingReflectiveUses.add(currentMethod);
      } else if (appView.dexItemFactory().classMethods.isReflectiveMemberLookup(method)) {
        // Implicitly add -identifiernamestring rule for the Java reflection in use.
        identifierNameStrings.add(method);
        // Revisit the current method to implicitly add -keep rule for items with reflective access.
        pendingReflectiveUses.add(currentMethod);
      }
      if (!registerItemWithTargetAndContext(virtualInvokes, method, currentMethod)) {
        return false;
      }
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register invokeVirtual `%s`.", method);
      }
      workList.add(Action.markReachableVirtual(method, keepReason));
      return true;
    }

    @Override
    public boolean registerInvokeDirect(DexMethod method) {
      return registerInvokeDirect(method, KeepReason.invokedFrom(currentMethod));
    }

    boolean registerInvokeDirect(DexMethod method, KeepReason keepReason) {
      if (!registerItemWithTargetAndContext(directInvokes, method, currentMethod)) {
        return false;
      }
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register invokeDirect `%s`.", method);
      }
      handleInvokeOfDirectTarget(method, keepReason);
      return true;
    }

    @Override
    public boolean registerInvokeStatic(DexMethod method) {
      return registerInvokeStatic(method, KeepReason.invokedFrom(currentMethod));
    }

    boolean registerInvokeStatic(DexMethod method, KeepReason keepReason) {
      DexItemFactory dexItemFactory = appView.dexItemFactory();
      if (method == dexItemFactory.classMethods.forName
          || dexItemFactory.atomicFieldUpdaterMethods.isFieldUpdater(method)) {
        // Implicitly add -identifiernamestring rule for the Java reflection in use.
        identifierNameStrings.add(method);
        // Revisit the current method to implicitly add -keep rule for items with reflective access.
        pendingReflectiveUses.add(currentMethod);
      }
      // See comment in handleJavaLangEnumValueOf.
      if (method == dexItemFactory.enumMethods.valueOf) {
        pendingReflectiveUses.add(currentMethod);
      }
      // Handling of application services.
      if (dexItemFactory.serviceLoaderMethods.isLoadMethod(method)) {
        pendingReflectiveUses.add(currentMethod);
      }
      if (method == dexItemFactory.proxyMethods.newProxyInstance) {
        pendingReflectiveUses.add(currentMethod);
      }
      if (!registerItemWithTargetAndContext(staticInvokes, method, currentMethod)) {
        return false;
      }
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register invokeStatic `%s`.", method);
      }
      handleInvokeOfStaticTarget(method, keepReason);
      return true;
    }

    @Override
    public boolean registerInvokeInterface(DexMethod method) {
      return registerInvokeInterface(method, KeepReason.invokedFrom(currentMethod));
    }

    boolean registerInvokeInterface(DexMethod method, KeepReason keepReason) {
      if (!registerItemWithTargetAndContext(interfaceInvokes, method, currentMethod)) {
        return false;
      }
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register invokeInterface `%s`.", method);
      }
      workList.add(Action.markReachableInterface(method, keepReason));
      return true;
    }

    @Override
    public boolean registerInvokeSuper(DexMethod method) {
      // We have to revisit super invokes based on the context they are found in. The same
      // method descriptor will hit different targets, depending on the context it is used in.
      DexMethod actualTarget = getInvokeSuperTarget(method, currentMethod);
      if (!registerItemWithTargetAndContext(superInvokes, method, currentMethod)) {
        return false;
      }
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register invokeSuper `%s`.", actualTarget);
      }
      workList.add(Action.markReachableSuper(method, currentMethod));
      return true;
    }

    @Override
    public boolean registerInstanceFieldWrite(DexField field) {
      if (!registerItemWithTargetAndContext(instanceFieldsWritten, field, currentMethod)) {
        return false;
      }
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register Iput `%s`.", field);
      }
      // TODO(herhut): We have to add this, but DCR should eliminate dead writes.
      workList.add(Action.markReachableField(field, KeepReason.fieldReferencedIn(currentMethod)));
      return true;
    }

    @Override
    public boolean registerInstanceFieldRead(DexField field) {
      if (!registerItemWithTargetAndContext(instanceFieldsRead, field, currentMethod)) {
        return false;
      }
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register Iget `%s`.", field);
      }
      workList.add(Action.markReachableField(field, KeepReason.fieldReferencedIn(currentMethod)));
      return true;
    }

    @Override
    public boolean registerNewInstance(DexType type) {
      return registerNewInstance(type, KeepReason.instantiatedIn(currentMethod));
    }

    public boolean registerNewInstance(DexType type, KeepReason keepReason) {
      markInstantiated(type, keepReason);
      return true;
    }

    @Override
    public boolean registerStaticFieldRead(DexField field) {
      if (!registerItemWithTargetAndContext(staticFieldsRead, field, currentMethod)) {
        return false;
      }
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register Sget `%s`.", field);
      }
      markStaticFieldAsLive(field, KeepReason.fieldReferencedIn(currentMethod));
      return true;
    }

    @Override
    public boolean registerStaticFieldWrite(DexField field) {
      if (!registerItemWithTargetAndContext(staticFieldsWritten, field, currentMethod)) {
        return false;
      }
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register Sput `%s`.", field);
      }

      DexEncodedField encodedField = appInfo.resolveField(field);
      if (encodedField != null && encodedField.isProgramField(appView)) {
        boolean isWrittenOutsideEnclosingStaticInitializer =
            currentMethod.method.holder != encodedField.field.holder
                || !currentMethod.isClassInitializer();
        if (isWrittenOutsideEnclosingStaticInitializer) {
          staticFieldsWrittenOutsideEnclosingStaticInitializer.add(encodedField.field);
        }
      }

      // TODO(herhut): We have to add this, but DCR should eliminate dead writes.
      markStaticFieldAsLive(field, KeepReason.fieldReferencedIn(currentMethod), encodedField);

      return true;
    }

    @Override
    public boolean registerConstClass(DexType type) {
      return registerConstClassOrCheckCast(type);
    }

    @Override
    public boolean registerCheckCast(DexType type) {
      return registerConstClassOrCheckCast(type);
    }

    @Override
    public boolean registerTypeReference(DexType type) {
      markTypeAsLive(type);
      return true;
    }

    @Override
    public void registerMethodHandle(DexMethodHandle methodHandle, MethodHandleUse use) {
      super.registerMethodHandle(methodHandle, use);
      // If a method handle is not an argument to a lambda metafactory it could flow to a
      // MethodHandle.invokeExact invocation. For that to work, the receiver type cannot have
      // changed and therefore we cannot perform member rebinding. For these handles, we maintain
      // the receiver for the method handle. Therefore, we have to make sure that the receiver
      // stays in the output (and is not class merged). To ensure that we treat the receiver
      // as instantiated.
      if (methodHandle.isMethodHandle() && use != MethodHandleUse.ARGUMENT_TO_LAMBDA_METAFACTORY) {
        DexClass holder = appView.definitionFor(methodHandle.asMethod().holder);
        if (holder != null) {
          markInstantiated(holder.type, KeepReason.methodHandleReferencedIn(currentMethod));
        }
      }
    }

    @Override
    public void registerCallSite(DexCallSite callSite) {
      callSites.add(callSite);
      super.registerCallSite(callSite);

      List<DexType> directInterfaces = LambdaDescriptor.getInterfaces(callSite, appInfo);
      if (directInterfaces != null) {
        for (DexType lambdaInstantiatedInterface : directInterfaces) {
          markLambdaInstantiated(lambdaInstantiatedInterface, currentMethod);
        }
      } else {
        if (!appInfo.isStringConcat(callSite.bootstrapMethod)) {
          if (options.reporter != null) {
            Diagnostic message =
                new StringDiagnostic(
                    "Unknown bootstrap method " + callSite.bootstrapMethod,
                    appInfo.originFor(currentMethod.method.holder));
            options.reporter.warning(message);
          }
        }
      }

      DexClass bootstrapClass = appView.definitionFor(callSite.bootstrapMethod.asMethod().holder);
      if (bootstrapClass != null && bootstrapClass.isProgramClass()) {
        bootstrapMethods.add(callSite.bootstrapMethod.asMethod());
      }

      LambdaDescriptor descriptor = LambdaDescriptor.tryInfer(callSite, appInfo);
      if (descriptor == null) {
        return;
      }

      // For call sites representing a lambda, we link the targeted method
      // or field as if it were referenced from the current method.

      DexMethodHandle implHandle = descriptor.implHandle;
      assert implHandle != null;

      DexMethod method = implHandle.asMethod();
      if (descriptor.delegatesToLambdaImplMethod()) {
        lambdaMethodsTargetedByInvokeDynamic.add(method);
      }

      if (!methodsTargetedByInvokeDynamic.add(method)) {
        return;
      }

      switch (implHandle.type) {
        case INVOKE_STATIC:
          registerInvokeStatic(method, KeepReason.invokedFromLambdaCreatedIn(currentMethod));
          break;
        case INVOKE_INTERFACE:
          registerInvokeInterface(method, KeepReason.invokedFromLambdaCreatedIn(currentMethod));
          break;
        case INVOKE_INSTANCE:
          registerInvokeVirtual(method, KeepReason.invokedFromLambdaCreatedIn(currentMethod));
          break;
        case INVOKE_DIRECT:
          registerInvokeDirect(method, KeepReason.invokedFromLambdaCreatedIn(currentMethod));
          break;
        case INVOKE_CONSTRUCTOR:
          registerNewInstance(method.holder, KeepReason.invokedFromLambdaCreatedIn(currentMethod));
          break;
        default:
          throw new Unreachable();
      }

      // In similar way as what transitionMethodsForInstantiatedClass does for existing
      // classes we need to process classes dynamically created by runtime for lambdas.
      // We make an assumption that such classes are inherited directly from java.lang.Object
      // and implement all lambda interfaces.

      ScopedDexMethodSet seen = new ScopedDexMethodSet();
      if (directInterfaces == null) {
        return;
      }

      Set<DexType> allInterfaces = Sets.newHashSet(directInterfaces);
      DexType instantiatedType = appView.dexItemFactory().objectType;
      DexClass clazz = appView.definitionFor(instantiatedType);
      if (clazz == null) {
        reportMissingClass(instantiatedType);
        return;
      }

      // We only have to look at virtual methods here, as only those can actually be executed at
      // runtime. Illegal dispatch situations and the corresponding exceptions are already handled
      // by the reachability logic.
      SetWithReason<DexEncodedMethod> reachableMethods =
          reachableVirtualMethods.get(instantiatedType);
      if (reachableMethods != null) {
        transitionNonAbstractMethodsToLiveAndShadow(
            reachableMethods.getItems(), instantiatedType, seen);
      }
      Collections.addAll(allInterfaces, clazz.interfaces.values);

      // The set now contains all virtual methods on the type and its supertype that are reachable.
      // In a second step, we now look at interfaces. We have to do this in this order due to JVM
      // semantics for default methods. A default method is only reachable if it is not overridden
      // in any superclass. Also, it is not defined which default method is chosen if multiple
      // interfaces define the same default method. Hence, for every interface (direct or indirect),
      // we have to look at the interface chain and mark default methods as reachable, not taking
      // the shadowing of other interface chains into account.
      // See https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.3.3
      for (DexType iface : allInterfaces) {
        DexClass ifaceClazz = appView.definitionFor(iface);
        if (ifaceClazz == null) {
          reportMissingClass(iface);
          return;
        }
        transitionDefaultMethodsForInstantiatedClass(iface, instantiatedType, seen);
      }
    }

    private boolean registerConstClassOrCheckCast(DexType type) {
      if (forceProguardCompatibility) {
        DexType baseType = type.toBaseType(appView.dexItemFactory());
        if (baseType.isClassType()) {
          DexClass baseClass = appView.definitionFor(baseType);
          if (baseClass != null && baseClass.isProgramClass()) {
            // Don't require any constructor, see b/112386012.
            markClassAsInstantiatedWithCompatRule(baseClass);
          } else {
            // This also handles reporting of missing classes.
            markTypeAsLive(baseType);
          }
          return true;
        }
        return false;
      } else {
        return registerTypeReference(type);
      }
    }
  }

  private DexMethod getInvokeSuperTarget(DexMethod method, DexEncodedMethod currentMethod) {
    DexClass methodHolderClass = appView.definitionFor(method.holder);
    if (methodHolderClass != null && methodHolderClass.isInterface()) {
      return method;
    }
    DexClass holderClass = appView.definitionFor(currentMethod.method.holder);
    if (holderClass == null || holderClass.superType == null || holderClass.isInterface()) {
      // We do not know better or this call is made from an interface.
      return method;
    }
    // Return the invoked method on the supertype.
    return appView.dexItemFactory().createMethod(holderClass.superType, method.proto, method.name);
  }

  //
  // Actual actions performed.
  //

  private void markTypeAsLive(DexType type) {
    type = type.toBaseType(appView.dexItemFactory());
    if (!type.isClassType()) {
      // Ignore primitive types.
      return;
    }
    if (liveTypes.add(type)) {
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Type `%s` has become live.", type);
      }
      DexClass holder = appView.definitionFor(type);
      if (holder == null) {
        reportMissingClass(type);
        return;
      }
      for (DexType iface : holder.interfaces.values) {
        markTypeAsLive(iface);
      }
      if (holder.superType != null) {
        markTypeAsLive(holder.superType);
        if (holder.isNotProgramClass()) {
          // Library classes may only extend other implement library classes.
          ensureFromLibraryOrThrow(holder.superType, type);
          for (DexType iface : holder.interfaces.values) {
            ensureFromLibraryOrThrow(iface, type);
          }
        }
      }
      // We also need to add the corresponding <clinit> to the set of live methods, as otherwise
      // static field initialization (and other class-load-time sideeffects) will not happen.
      KeepReason reason = KeepReason.reachableFromLiveType(type);
      if (holder.isProgramClass() && holder.hasNonTrivialClassInitializer()) {
        DexEncodedMethod clinit = holder.getClassInitializer();
        if (clinit != null) {
          assert clinit.method.holder == holder.type;
          markDirectStaticOrConstructorMethodAsLive(clinit, reason);
        }
      }

      if (holder.isProgramClass() && holder.isSerializable(appView)) {
        enqueueFirstNonSerializableClassInitializer(holder, reason);
      }

      if (holder.isProgramClass()) {
        if (!holder.annotations.isEmpty()) {
          processAnnotations(holder, holder.annotations.annotations);
        }
        // If this type has deferred annotations, we have to process those now, too.
        Set<DexAnnotation> annotations = deferredAnnotations.remove(type);
        if (annotations != null && !annotations.isEmpty()) {
          assert holder.accessFlags.isAnnotation();
          assert annotations.stream().allMatch(a -> a.annotation.type == holder.type);
          annotations.forEach(annotation -> handleAnnotation(holder, annotation));
        }
      } else {
        assert !deferredAnnotations.containsKey(holder.type);
      }

      Map<DexReference, Set<ProguardKeepRule>> dependentItems = rootSet.getDependentItems(holder);
      enqueueHolderIfDependentNonStaticMember(holder, dependentItems);
      // Add all dependent members to the workqueue.
      enqueueRootItems(dependentItems);
    }
  }

  private void processAnnotations(DexDefinition holder, DexAnnotation[] annotations) {
    for (DexAnnotation annotation : annotations) {
      processAnnotation(holder, annotation);
    }
  }

  private void processAnnotation(DexDefinition holder, DexAnnotation annotation) {
    handleAnnotation(holder, annotation);
  }

  private void handleAnnotation(DexDefinition holder, DexAnnotation annotation) {
    assert !holder.isDexClass() || holder.asDexClass().isProgramClass();
    DexType type = annotation.annotation.type;
    boolean annotationTypeIsLibraryClass =
        appView.definitionFor(type) == null || appView.definitionFor(type).isNotProgramClass();
    boolean isLive = annotationTypeIsLibraryClass || liveTypes.contains(type);
    if (!shouldKeepAnnotation(annotation, isLive, appView.dexItemFactory(), options)) {
      // Remember this annotation for later.
      if (!annotationTypeIsLibraryClass) {
        deferredAnnotations.computeIfAbsent(type, ignore -> new HashSet<>()).add(annotation);
      }
      return;
    }
    liveAnnotations.add(annotation, KeepReason.annotatedOn(holder));
    AnnotationReferenceMarker referenceMarker =
        new AnnotationReferenceMarker(annotation.annotation.type, appView.dexItemFactory());
    annotation.annotation.collectIndexedItems(referenceMarker);
  }

  private void handleInvokeOfStaticTarget(DexMethod method, KeepReason reason) {
    // We have to mark the resolved method as targeted even if it cannot actually be invoked
    // to make sure the invocation will keep failing in the appropriate way.
    ResolutionResult resolutionResult = appInfo.resolveMethod(method.holder, method);
    if (resolutionResult == null) {
      reportMissingMethod(method);
      return;
    }
    resolutionResult.forEachTarget(m -> markMethodAsTargeted(m, reason));
    // Only mark methods for which invocation will succeed at runtime live.
    DexEncodedMethod targetMethod = appInfo.dispatchStaticInvoke(resolutionResult);
    if (targetMethod != null) {
      markDirectStaticOrConstructorMethodAsLive(targetMethod, reason);
    }
  }

  private void handleInvokeOfDirectTarget(DexMethod method, KeepReason reason) {
    // We have to mark the resolved method as targeted even if it cannot actually be invoked
    // to make sure the invocation will keep failing in the appropriate way.
    ResolutionResult resolutionResult = appInfo.resolveMethod(method.holder, method);
    if (resolutionResult == null) {
      reportMissingMethod(method);
      return;
    }
    resolutionResult.forEachTarget(m -> markMethodAsTargeted(m, reason));
    // Only mark methods for which invocation will succeed at runtime live.
    DexEncodedMethod target = appInfo.dispatchDirectInvoke(resolutionResult);
    if (target != null) {
      markDirectStaticOrConstructorMethodAsLive(target, reason);

      // It is valid to have an invoke-direct instruction in a default interface method that
      // targets another default method in the same interface (see testInvokeSpecialToDefault-
      // Method). In a class, that would lead to a verification error.
      if (target.isVirtualMethod()) {
        virtualMethodsTargetedByInvokeDirect.add(target.method);
      }
    }
  }

  private void ensureFromLibraryOrThrow(DexType type, DexType context) {
    if (tracingMainDex) {
      // b/72312389: android.jar contains parts of JUnit and most developers include JUnit in
      // their programs. This leads to library classes extending program classes. When tracing
      // main dex lists we allow this.
      return;
    }

    DexClass holder = appView.definitionFor(type);
    if (holder != null && !holder.isLibraryClass()) {
      if (!dontWarnPatterns.matches(context)) {
        Diagnostic message =
            new StringDiagnostic(
                "Library class "
                    + context.toSourceString()
                    + (holder.isInterface() ? " implements " : " extends ")
                    + "program class "
                    + type.toSourceString());
        if (tracingMainDex || forceProguardCompatibility) {
          options.reporter.warning(message);
        } else {
          options.reporter.error(message);
        }
      }
    }
  }

  private void reportMissingClass(DexType clazz) {
    if (Log.ENABLED && reportedMissing.add(clazz)) {
      Log.verbose(Enqueuer.class, "Class `%s` is missing.", clazz);
    }
  }

  private void reportMissingMethod(DexMethod method) {
    if (Log.ENABLED && reportedMissing.add(method)) {
      Log.verbose(Enqueuer.class, "Method `%s` is missing.", method);
    }
  }

  private void reportMissingField(DexField field) {
    if (Log.ENABLED && reportedMissing.add(field)) {
      Log.verbose(Enqueuer.class, "Field `%s` is missing.", field);
    }
  }

  private void markMethodAsTargeted(DexEncodedMethod method, KeepReason reason) {
    if (!targetedMethods.add(method, reason)) {
      return;
    }
    markTypeAsLive(method.method.holder);
    markParameterAndReturnTypesAsLive(method);
    if (appView.definitionFor(method.method.holder).isProgramClass()) {
      processAnnotations(method, method.annotations.annotations);
      method.parameterAnnotationsList.forEachAnnotation(
          annotation -> processAnnotation(method, annotation));
    }
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Method `%s` is targeted.", method.method);
    }
    if (forceProguardCompatibility) {
      // Keep targeted default methods in compatibility mode. The tree pruner will otherwise make
      // these methods abstract, whereas Proguard does not (seem to) touch their code.
      DexClass clazz = appView.definitionFor(method.method.holder);
      if (!method.accessFlags.isAbstract() && clazz.isInterface() && clazz.isProgramClass()) {
        markMethodAsKeptWithCompatRule(method);
      }
    }
  }

  /**
   * Adds the class to the set of instantiated classes and marks its fields and methods live
   * depending on the currently seen invokes and field reads.
   */
  private void processNewlyInstantiatedClass(DexClass clazz, KeepReason reason) {
    if (!instantiatedTypes.add(clazz.type, reason)) {
      return;
    }
    collectProguardCompatibilityRule(reason);
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Class `%s` is instantiated, processing...", clazz);
    }
    // This class becomes live, so it and all its supertypes become live types.
    markTypeAsLive(clazz.type);
    // For all methods of the class, if we have seen a call, mark the method live.
    // We only do this for virtual calls, as the other ones will be done directly.
    transitionMethodsForInstantiatedClass(clazz.type);
    // For all instance fields visible from the class, mark them live if we have seen a read.
    transitionFieldsForInstantiatedClass(clazz.type);
    // Add all dependent members to the workqueue.
    enqueueRootItems(rootSet.getDependentItems(clazz));
  }

  /**
   * Marks all methods live that can be reached by calls previously seen.
   * <p>
   * <p>This should only be invoked if the given type newly becomes instantiated. In essence, this
   * method replays all the invokes we have seen so far that could apply to this type and marks the
   * corresponding methods live.
   * <p>
   * <p>Only methods that are visible in this type are considered. That is, only those methods that
   * are either defined directly on this type or that are defined on a supertype but are not
   * shadowed by another inherited method. Furthermore, default methods from implemented interfaces
   * that are not otherwise shadowed are considered, too.
   */
  private void transitionMethodsForInstantiatedClass(DexType instantiatedType) {
    ScopedDexMethodSet seen = new ScopedDexMethodSet();
    Set<DexType> interfaces = Sets.newIdentityHashSet();
    DexType type = instantiatedType;
    do {
      DexClass clazz = appView.definitionFor(type);
      if (clazz == null) {
        reportMissingClass(type);
        // TODO(herhut): In essence, our subtyping chain is broken here. Handle that case better.
        break;
      }
      // We only have to look at virtual methods here, as only those can actually be executed at
      // runtime. Illegal dispatch situations and the corresponding exceptions are already handled
      // by the reachability logic.
      SetWithReason<DexEncodedMethod> reachableMethods = reachableVirtualMethods.get(type);
      if (reachableMethods != null) {
        transitionNonAbstractMethodsToLiveAndShadow(reachableMethods.getItems(), instantiatedType,
            seen);
      }
      Collections.addAll(interfaces, clazz.interfaces.values);
      type = clazz.superType;
    } while (type != null && !instantiatedTypes.contains(type));
    // The set now contains all virtual methods on the type and its supertype that are reachable.
    // In a second step, we now look at interfaces. We have to do this in this order due to JVM
    // semantics for default methods. A default method is only reachable if it is not overridden in
    // any superclass. Also, it is not defined which default method is chosen if multiple
    // interfaces define the same default method. Hence, for every interface (direct or indirect),
    // we have to look at the interface chain and mark default methods as reachable, not taking
    // the shadowing of other interface chains into account.
    // See https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.3.3
    for (DexType iface : interfaces) {
      DexClass clazz = appView.definitionFor(iface);
      if (clazz == null) {
        reportMissingClass(iface);
        // TODO(herhut): In essence, our subtyping chain is broken here. Handle that case better.
        break;
      }
      transitionDefaultMethodsForInstantiatedClass(iface, instantiatedType, seen);
    }
  }

  private void transitionDefaultMethodsForInstantiatedClass(DexType iface, DexType instantiatedType,
      ScopedDexMethodSet seen) {
    DexClass clazz = appView.definitionFor(iface);
    if (clazz == null) {
      reportMissingClass(iface);
      return;
    }
    assert clazz.accessFlags.isInterface();
    SetWithReason<DexEncodedMethod> reachableMethods = reachableVirtualMethods.get(iface);
    if (reachableMethods != null) {
      transitionNonAbstractMethodsToLiveAndShadow(
          reachableMethods.getItems(), instantiatedType, seen.newNestedScope());
    }
    for (DexType subInterface : clazz.interfaces.values) {
      transitionDefaultMethodsForInstantiatedClass(subInterface, instantiatedType, seen);
    }
  }

  private void transitionNonAbstractMethodsToLiveAndShadow(Iterable<DexEncodedMethod> reachable,
      DexType instantiatedType, ScopedDexMethodSet seen) {
    for (DexEncodedMethod encodedMethod : reachable) {
      if (seen.addMethod(encodedMethod)) {
        // Abstract methods do shadow implementations but they cannot be live, as they have no
        // code.
        if (!encodedMethod.accessFlags.isAbstract()) {
          markVirtualMethodAsLive(encodedMethod,
              KeepReason.reachableFromLiveType(instantiatedType));
        }
      }
    }
  }

  /**
   * Marks all fields live that can be reached by a read assuming that the given type or one of its
   * subtypes is instantiated.
   */
  private void transitionFieldsForInstantiatedClass(DexType type) {
    do {
      DexClass clazz = appView.definitionFor(type);
      if (clazz == null) {
        // TODO(herhut) The subtype chain is broken. We need a way to deal with this better.
        reportMissingClass(type);
        break;
      }
      if (!clazz.isProgramClass()) {
        break;
      }
      SetWithReason<DexEncodedField> reachableFields = reachableInstanceFields.get(type);
      if (reachableFields != null) {
        for (DexEncodedField field : reachableFields.getItems()) {
          // TODO(b/120959039): Should the reason this field is reachable come from the set?
          markInstanceFieldAsLive(field, KeepReason.reachableFromLiveType(type));
        }
      }
      type = clazz.superType;
    } while (type != null && !instantiatedTypes.contains(type));
  }

  private void markStaticFieldAsLive(DexField field, KeepReason reason) {
    markStaticFieldAsLive(field, reason, appInfo.resolveField(field));
  }

  private void markStaticFieldAsLive(
      DexField field, KeepReason reason, DexEncodedField encodedField) {
    // Mark the type live here, so that the class exists at runtime. Note that this also marks all
    // supertypes as live, so even if the field is actually on a supertype, its class will be live.
    markTypeAsLive(field.holder);
    markTypeAsLive(field.type);

    // Find the actual field.
    if (encodedField == null) {
      reportMissingField(field);
      return;
    }

    if (!encodedField.isProgramField(appView)) {
      return;
    }

    // This field might be an instance field reachable from a static context, e.g. a getStatic that
    // resolves to an instance field. We have to keep the instance field nonetheless, as otherwise
    // we might unmask a shadowed static field and hence change semantics.

    if (encodedField.accessFlags.isStatic()) {
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Adding static field `%s` to live set.", encodedField.field);
      }
    } else {
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Adding instance field `%s` to live set (static context).",
            encodedField.field);
      }
    }
    processAnnotations(encodedField, encodedField.annotations.annotations);
    liveFields.add(encodedField, reason);
    collectProguardCompatibilityRule(reason);

    // Add all dependent members to the workqueue.
    enqueueRootItems(rootSet.getDependentItems(encodedField));
  }

  private void markInstanceFieldAsLive(DexEncodedField field, KeepReason reason) {
    assert field != null;
    assert field.isProgramField(appView);
    markTypeAsLive(field.field.holder);
    markTypeAsLive(field.field.type);
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Adding instance field `%s` to live set.", field.field);
    }
    processAnnotations(field, field.annotations.annotations);
    liveFields.add(field, reason);
    collectProguardCompatibilityRule(reason);
    // Add all dependent members to the workqueue.
    enqueueRootItems(rootSet.getDependentItems(field));
  }

  private void markInstantiated(DexType type, KeepReason keepReason) {
    if (instantiatedTypes.contains(type)) {
      return;
    }
    DexClass clazz = appView.definitionFor(type);
    if (clazz == null) {
      reportMissingClass(type);
      return;
    }
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Register new instantiation of `%s`.", clazz);
    }
    workList.add(Action.markInstantiated(clazz, keepReason));
  }

  private void markLambdaInstantiated(DexType itf, DexEncodedMethod method) {
    DexClass clazz = appView.definitionFor(itf);
    if (clazz == null) {
      if (options.reporter != null) {
        StringDiagnostic message =
            new StringDiagnostic(
                "Lambda expression implements missing interface `" + itf.toSourceString() + "`",
                appInfo.originFor(method.method.holder));
        options.reporter.warning(message);
      }
      return;
    }
    if (!clazz.isInterface()) {
      if (options.reporter != null) {
        StringDiagnostic message =
            new StringDiagnostic(
                "Lambda expression expected to implement an interface, but found "
                    + "`" + itf.toSourceString() + "`",
                appInfo.originFor(method.method.holder));
        options.reporter.warning(message);
      }
      return;
    }
    if (clazz.isProgramClass()) {
      instantiatedLambdas.add(itf, KeepReason.instantiatedIn(method));
    }
  }

  private void markDirectStaticOrConstructorMethodAsLive(
      DexEncodedMethod encodedMethod, KeepReason reason) {
    assert encodedMethod != null;
    markMethodAsTargeted(encodedMethod, reason);
    if (!liveMethods.contains(encodedMethod)) {
      markTypeAsLive(encodedMethod.method.holder);
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Method `%s` has become live due to direct invoke",
            encodedMethod.method);
      }
      workList.add(Action.markMethodLive(encodedMethod, reason));
    }
  }

  private void markVirtualMethodAsLive(DexEncodedMethod method, KeepReason reason) {
    assert method != null;
    // Only explicit keep rules or reflective use should make abstract methods live.
    assert !method.accessFlags.isAbstract()
        || reason.isDueToKeepRule()
        || reason.isDueToReflectiveUse();
    if (!liveMethods.contains(method)) {
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Adding virtual method `%s` to live set.", method.method);
      }
      workList.add(Action.markMethodLive(method, reason));
    }
  }

  private boolean isInstantiatedOrHasInstantiatedSubtype(DexType type) {
    return instantiatedTypes.contains(type)
        || appInfo.subtypes(type).stream().anyMatch(instantiatedTypes::contains);
  }

  private void markInstanceFieldAsReachable(DexField field, KeepReason reason) {
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Marking instance field `%s` as reachable.", field);
    }
    DexEncodedField encodedField = appInfo.resolveField(field);
    if (encodedField == null) {
      reportMissingField(field);
      return;
    }
    if (!encodedField.isProgramField(appView)) {
      return;
    }
    // We might have a instance field access that is dispatched to a static field. In such case,
    // we have to keep the static field, so that the dispatch fails at runtime in the same way that
    // it did before. We have to keep the field even if the receiver has no live inhabitants, as
    // field resolution happens before the receiver is inspected.
    if (encodedField.accessFlags.isStatic()) {
      markStaticFieldAsLive(encodedField.field, reason);
    } else {
      if (isInstantiatedOrHasInstantiatedSubtype(encodedField.field.holder)) {
        // We have at least one live subtype, so mark it as live.
        markInstanceFieldAsLive(encodedField, reason);
      } else {
        // Add the field to the reachable set if the type later becomes instantiated.
        reachableInstanceFields
            .computeIfAbsent(encodedField.field.holder, ignore -> newSetWithoutReasonReporter())
            .add(encodedField, reason);
      }
    }
  }

  private void markVirtualMethodAsReachable(DexMethod method, boolean interfaceInvoke,
      KeepReason reason) {
    if (!virtualTargetsMarkedAsReachable.add(method)) {
      return;
    }
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Marking virtual method `%s` as reachable.", method);
    }
    if (method.holder.isArrayType()) {
      // This is an array type, so the actual class will be generated at runtime. We treat this
      // like an invoke on a direct subtype of java.lang.Object that has no further subtypes.
      // As it has no subtypes, it cannot affect liveness of the program we are processing.
      // Ergo, we can ignore it. We need to make sure that the element type is available, though.
      markTypeAsLive(method.holder);
      return;
    }
    DexClass holder = appView.definitionFor(method.holder);
    if (holder == null) {
      reportMissingClass(method.holder);
      return;
    }
    DexEncodedMethod topTarget = interfaceInvoke
        ? appInfo.resolveMethodOnInterface(method.holder, method).asResultOfResolve()
        : appInfo.resolveMethodOnClass(method.holder, method).asResultOfResolve();
    if (topTarget == null) {
      reportMissingMethod(method);
      return;
    }
    // We have to mark this as targeted, as even if this specific instance never becomes live, we
    // need at least an abstract version of it so that we have a target for the corresponding
    // invoke.
    markMethodAsTargeted(topTarget, reason);
    Set<DexEncodedMethod> targets = interfaceInvoke
        ? appInfo.lookupInterfaceTargets(method)
        : appInfo.lookupVirtualTargets(method);
    for (DexEncodedMethod encodedMethod : targets) {
      // TODO(b/120959039): The reachable.add test might be hiding other paths to the method.
      SetWithReason<DexEncodedMethod> reachable =
          reachableVirtualMethods.computeIfAbsent(
              encodedMethod.method.holder, ignore -> newSetWithoutReasonReporter());
      if (reachable.add(encodedMethod, reason)) {
        // Abstract methods cannot be live.
        if (!encodedMethod.accessFlags.isAbstract()) {
          // If the holder type is instantiated, the method is live. Otherwise check whether we find
          // a subtype that does not shadow this methods but is instantiated.
          // Note that library classes are always considered instantiated, as we do not know where
          // they are instantiated.
          if (isInstantiatedOrHasInstantiatedSubtype(encodedMethod.method.holder)) {
            if (instantiatedTypes.contains(encodedMethod.method.holder)) {
              markVirtualMethodAsLive(encodedMethod,
                  KeepReason.reachableFromLiveType(encodedMethod.method.holder));
            } else {
              Deque<DexType> worklist = new ArrayDeque<>();
              fillWorkList(worklist, encodedMethod.method.holder);
              while (!worklist.isEmpty()) {
                DexType current = worklist.pollFirst();
                DexClass currentHolder = appView.definitionFor(current);
                // If this class overrides the virtual, abort the search. Note that, according to
                // the JVM spec, private methods cannot override a virtual method.
                if (currentHolder == null
                    || currentHolder.lookupVirtualMethod(encodedMethod.method) != null) {
                  continue;
                }
                if (instantiatedTypes.contains(current)) {
                  markVirtualMethodAsLive(encodedMethod, KeepReason.reachableFromLiveType(current));
                  break;
                }
                fillWorkList(worklist, current);
              }
            }
          }
        }
      }
    }
  }

  private DexMethod generatedEnumValuesMethod(DexClass enumClass) {
    DexType arrayOfEnumClass =
        appView
            .dexItemFactory()
            .createType(
                appView.dexItemFactory().createString("[" + enumClass.type.toDescriptorString()));
    DexProto proto = appView.dexItemFactory().createProto(arrayOfEnumClass);
    return appView
        .dexItemFactory()
        .createMethod(enumClass.type, proto, appView.dexItemFactory().createString("values"));
  }

  private void markEnumValuesAsReachable(DexClass clazz, KeepReason reason) {
    DexEncodedMethod valuesMethod = clazz.lookupMethod(generatedEnumValuesMethod(clazz));
    if (valuesMethod != null) {
      // TODO(sgjesse): Does this have to be enqueued as a root item? Right now it is done as the
      // marking of not renaming is in the root set.
      enqueueRootItem(valuesMethod, reason);
      rootSet.noObfuscation.add(valuesMethod.toReference());
    }
  }

  private static void fillWorkList(Deque<DexType> worklist, DexType type) {
    if (type.isInterface()) {
      // We need to check if the method is shadowed by a class that directly implements
      // the interface and go recursively down to the sub interfaces to reach class
      // implementing the interface
      type.forAllImplementsSubtypes(worklist::addLast);
      type.forAllExtendsSubtypes(worklist::addLast);
    } else {
      type.forAllExtendsSubtypes(worklist::addLast);
    }
  }

  private void markSuperMethodAsReachable(DexMethod method, DexEncodedMethod from) {
    // We have to mark the immediate target of the descriptor as targeted, as otherwise
    // the invoke super will fail in the resolution step with a NSM error.
    // See <a
    // href="https://docs.oracle.com/javase/specs/jvms/se7/html/jvms-6.html#jvms-6.5.invokespecial">
    // the JVM spec for invoke-special.
    DexEncodedMethod resolutionTarget = appInfo.resolveMethod(method.holder, method)
        .asResultOfResolve();
    if (resolutionTarget == null) {
      brokenSuperInvokes.add(method);
      reportMissingMethod(method);
      return;
    }
    if (resolutionTarget.accessFlags.isPrivate() || resolutionTarget.accessFlags.isStatic()) {
      brokenSuperInvokes.add(method);
    }
    markMethodAsTargeted(resolutionTarget, KeepReason.targetedBySuperFrom(from));
    // Now we need to compute the actual target in the context.
    DexEncodedMethod target = appInfo.lookupSuperTarget(method, from.method.holder);
    if (target == null) {
      // The actual implementation in the super class is missing.
      reportMissingMethod(method);
      return;
    }
    if (target.accessFlags.isPrivate()) {
      brokenSuperInvokes.add(method);
    }
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Adding super constraint from `%s` to `%s`", from.method,
          target.method);
    }
    if (superInvokeDependencies.computeIfAbsent(
        from, ignore -> Sets.newIdentityHashSet()).add(target)) {
      if (liveMethods.contains(from)) {
        markMethodAsTargeted(target, KeepReason.invokedViaSuperFrom(from));
        if (!target.accessFlags.isAbstract()) {
          markVirtualMethodAsLive(target, KeepReason.invokedViaSuperFrom(from));
        }
      }
    }
  }

  // Returns the set of live types.
  public SortedSet<DexType> traceMainDex(
      RootSet rootSet, ExecutorService executorService, Timing timing) throws ExecutionException {
    this.tracingMainDex = true;
    this.rootSet = rootSet;
    // Translate the result of root-set computation into enqueuer actions.
    enqueueRootItems(rootSet.noShrinking);
    trace(executorService, timing);
    options.reporter.failIfPendingErrors();
    return ImmutableSortedSet.copyOf(PresortedComparable::slowCompareTo, liveTypes);
  }

  public AppInfoWithLiveness traceApplication(
      RootSet rootSet,
      ProguardClassFilter dontWarnPatterns,
      ExecutorService executorService,
      Timing timing)
      throws ExecutionException {
    this.rootSet = rootSet;
    this.dontWarnPatterns = dontWarnPatterns;
    // Translate the result of root-set computation into enqueuer actions.
    enqueueRootItems(rootSet.noShrinking);
    appInfo.libraryClasses().forEach(this::markAllLibraryVirtualMethodsReachable);
    trace(executorService, timing);
    options.reporter.failIfPendingErrors();
    return createAppInfo(appInfo, this);
  }

  private static AppInfoWithLiveness createAppInfo(
      AppInfoWithSubtyping appInfo, Enqueuer enqueuer) {
    ImmutableSortedSet.Builder<DexType> builder =
        ImmutableSortedSet.orderedBy(PresortedComparable::slowCompareTo);
    enqueuer.liveAnnotations.items.forEach(annotation -> builder.add(annotation.annotation.type));
    SortedMap<DexField, Set<DexEncodedMethod>> instanceFieldReads =
        enqueuer.collectDescriptors(enqueuer.instanceFieldsRead);
    SortedMap<DexField, Set<DexEncodedMethod>> instanceFieldWrites =
        enqueuer.collectDescriptors(enqueuer.instanceFieldsWritten);
    SortedMap<DexField, Set<DexEncodedMethod>> staticFieldReads =
        enqueuer.collectDescriptors(enqueuer.staticFieldsRead);
    SortedMap<DexField, Set<DexEncodedMethod>> staticFieldWrites =
        enqueuer.collectDescriptors(enqueuer.staticFieldsWritten);
    AppInfoWithLiveness appInfoWithLiveness =
        new AppInfoWithLiveness(
            appInfo,
            ImmutableSortedSet.copyOf(PresortedComparable::slowCompareTo, enqueuer.liveTypes),
            builder.build(),
            ImmutableSortedSet.copyOf(
                PresortedComparable::slowCompareTo, enqueuer.instantiatedAppServices),
            ImmutableSortedSet.copyOf(
                PresortedComparable::slowCompareTo, enqueuer.instantiatedTypes.getItems()),
            Enqueuer.toSortedDescriptorSet(enqueuer.targetedMethods.getItems()),
            ImmutableSortedSet.copyOf(DexMethod::slowCompareTo, enqueuer.bootstrapMethods),
            ImmutableSortedSet.copyOf(
                DexMethod::slowCompareTo, enqueuer.methodsTargetedByInvokeDynamic),
            ImmutableSortedSet.copyOf(
                DexMethod::slowCompareTo, enqueuer.virtualMethodsTargetedByInvokeDirect),
            toSortedDescriptorSet(enqueuer.liveMethods.getItems()),
            // Filter out library fields and pinned fields, because these are read by default.
            extractProgramFieldDefinitions(
                instanceFieldReads.keySet(),
                staticFieldReads.keySet(),
                appInfo,
                field -> !enqueuer.pinnedItems.contains(field.field)),
            extractProgramFieldDefinitions(
                instanceFieldWrites.keySet(),
                staticFieldWrites.keySet(),
                appInfo,
                field -> !enqueuer.pinnedItems.contains(field.field)),
            ImmutableSortedSet.copyOf(
                DexField::slowCompareTo,
                enqueuer.staticFieldsWrittenOnlyInEnclosingStaticInitializer()),
            instanceFieldReads,
            instanceFieldWrites,
            staticFieldReads,
            staticFieldWrites,
            enqueuer.collectDescriptors(enqueuer.virtualInvokes),
            enqueuer.collectDescriptors(enqueuer.interfaceInvokes),
            enqueuer.collectDescriptors(enqueuer.superInvokes),
            enqueuer.collectDescriptors(enqueuer.directInvokes),
            enqueuer.collectDescriptors(enqueuer.staticInvokes),
            enqueuer.callSites,
            ImmutableSortedSet.copyOf(DexMethod::slowCompareTo, enqueuer.brokenSuperInvokes),
            enqueuer.pinnedItems,
            enqueuer.rootSet.mayHaveSideEffects,
            enqueuer.rootSet.noSideEffects,
            enqueuer.rootSet.assumedValues,
            enqueuer.rootSet.alwaysInline,
            enqueuer.rootSet.forceInline,
            enqueuer.rootSet.neverInline,
            enqueuer.rootSet.keepConstantArguments,
            enqueuer.rootSet.keepUnusedArguments,
            enqueuer.rootSet.neverClassInline,
            enqueuer.rootSet.neverMerge,
            enqueuer.rootSet.neverPropagateValue,
            joinIdentifierNameStrings(
                enqueuer.rootSet.identifierNameStrings, enqueuer.identifierNameStrings),
            Collections.emptySet(),
            Collections.emptyMap(),
            Collections.emptyMap(),
            ImmutableSortedSet.copyOf(
                PresortedComparable::slowCompareTo, enqueuer.instantiatedLambdas.getItems()));
    appInfo.markObsolete();
    return appInfoWithLiveness;
  }

  private static <T extends PresortedComparable<T>> SortedSet<T> toSortedDescriptorSet(
      Set<? extends KeyedDexItem<T>> set) {
    ImmutableSortedSet.Builder<T> builder =
        new ImmutableSortedSet.Builder<>(PresortedComparable<T>::slowCompareTo);
    for (KeyedDexItem<T> item : set) {
      builder.add(item.getKey());
    }
    return builder.build();
  }

  private static Object2BooleanMap<DexReference> joinIdentifierNameStrings(
      Set<DexReference> explicit, Set<DexReference> implicit) {
    Object2BooleanMap<DexReference> result = new Object2BooleanArrayMap<>();
    for (DexReference e : explicit) {
      result.putIfAbsent(e, true);
    }
    for (DexReference i : implicit) {
      result.putIfAbsent(i, false);
    }
    return result;
  }

  private void trace(ExecutorService executorService, Timing timing) throws ExecutionException {
    timing.begin("Grow the tree.");
    try {
      while (true) {
        long numOfLiveItems = (long) liveTypes.size();
        numOfLiveItems += (long) liveMethods.items.size();
        numOfLiveItems += (long) liveFields.items.size();
        while (!workList.isEmpty()) {
          Action action = workList.poll();
          switch (action.kind) {
            case MARK_INSTANTIATED:
              processNewlyInstantiatedClass((DexClass) action.target, action.reason);
              break;
            case MARK_REACHABLE_FIELD:
              markInstanceFieldAsReachable((DexField) action.target, action.reason);
              break;
            case MARK_REACHABLE_VIRTUAL:
              markVirtualMethodAsReachable((DexMethod) action.target, false, action.reason);
              break;
            case MARK_REACHABLE_INTERFACE:
              markVirtualMethodAsReachable((DexMethod) action.target, true, action.reason);
              break;
            case MARK_REACHABLE_SUPER:
              markSuperMethodAsReachable((DexMethod) action.target,
                  (DexEncodedMethod) action.context);
              break;
            case MARK_METHOD_KEPT:
              markMethodAsKept((DexEncodedMethod) action.target, action.reason);
              break;
            case MARK_FIELD_KEPT:
              markFieldAsKept((DexEncodedField) action.target, action.reason);
              break;
            case MARK_METHOD_LIVE:
              processNewlyLiveMethod(((DexEncodedMethod) action.target), action.reason);
              break;
            default:
              throw new IllegalArgumentException(action.kind.toString());
          }
        }

        // Continue fix-point processing if -if rules are enabled by items that newly became live.
        long numOfLiveItemsAfterProcessing = (long) liveTypes.size();
        numOfLiveItemsAfterProcessing += (long) liveMethods.items.size();
        numOfLiveItemsAfterProcessing += (long) liveFields.items.size();
        if (numOfLiveItemsAfterProcessing > numOfLiveItems) {
          RootSetBuilder consequentSetBuilder = new RootSetBuilder(appView, rootSet.ifRules);
          IfRuleEvaluator ifRuleEvaluator =
              consequentSetBuilder.getIfRuleEvaluator(
                  liveFields.getItems(),
                  liveMethods.getItems(),
                  targetedMethods.getItems(),
                  executorService);
          ConsequentRootSet consequentRootSet = ifRuleEvaluator.run(liveTypes);
          rootSet.addConsequentRootSet(consequentRootSet);
          enqueueRootItems(consequentRootSet.noShrinking);
          // Check if any newly dependent members are not static, and in that case find the holder
          // and enqueue it as well. This is -if version of workaround for b/115867670.
          consequentRootSet.dependentNoShrinking.forEach(
              (precondition, dependentItems) -> {
                if (precondition.isDexType()) {
                  DexClass preconditionHolder = appView.definitionFor(precondition.asDexType());
                  enqueueHolderIfDependentNonStaticMember(preconditionHolder, dependentItems);
                }
                // Add all dependent members to the workqueue.
                enqueueRootItems(dependentItems);
              });
          if (!workList.isEmpty()) {
            continue;
          }
        }

        // Continue fix-point processing while there are additional work items to ensure
        // items that are passed to Java reflections are traced.
        if (proguardCompatibilityWorkList.isEmpty()
            && pendingReflectiveUses.isEmpty()) {
          break;
        }
        pendingReflectiveUses.forEach(this::handleReflectiveBehavior);
        workList.addAll(proguardCompatibilityWorkList);
        proguardCompatibilityWorkList.clear();
        pendingReflectiveUses.clear();
      }
      if (Log.ENABLED) {
        Set<DexEncodedMethod> allLive = Sets.newIdentityHashSet();
        for (Entry<DexType, SetWithReason<DexEncodedMethod>> entry : reachableVirtualMethods
            .entrySet()) {
          allLive.addAll(entry.getValue().getItems());
        }
        Set<DexEncodedMethod> reachableNotLive = Sets.difference(allLive, liveMethods.getItems());
        Log.debug(getClass(), "%s methods are reachable but not live", reachableNotLive.size());
        Log.info(getClass(), "Only reachable: %s", reachableNotLive);
        Set<DexType> liveButNotInstantiated =
            Sets.difference(liveTypes, instantiatedTypes.getItems());
        Log.debug(getClass(), "%s classes are live but not instantiated",
            liveButNotInstantiated.size());
        Log.info(getClass(), "Live but not instantiated: %s", liveButNotInstantiated);
        SetView<DexEncodedMethod> targetedButNotLive = Sets
            .difference(targetedMethods.getItems(), liveMethods.getItems());
        Log.debug(getClass(), "%s methods are targeted but not live", targetedButNotLive.size());
        Log.info(getClass(), "Targeted but not live: %s", targetedButNotLive);
      }
      assert liveTypes.stream().allMatch(DexType::isClassType);
      assert instantiatedTypes.getItems().stream().allMatch(DexType::isClassType);
    } finally {
      timing.end();
    }
    unpinLambdaMethods();
  }

  private void unpinLambdaMethods() {
    for (DexMethod method : lambdaMethodsTargetedByInvokeDynamic) {
      pinnedItems.remove(method);
      rootSet.prune(method);
    }
    lambdaMethodsTargetedByInvokeDynamic.clear();
  }

  private void markMethodAsKept(DexEncodedMethod target, KeepReason reason) {
    DexClass holder = appView.definitionFor(target.method.holder);
    // If this method no longer has a corresponding class then we have shaken it away before.
    if (holder == null) {
      return;
    }
    if (target.isVirtualMethod()) {
      // A virtual method. Mark it as reachable so that subclasses, if instantiated, keep
      // their overrides. However, we don't mark it live, as a keep rule might not imply that
      // the corresponding class is live.
      markVirtualMethodAsReachable(target.method, holder.accessFlags.isInterface(), reason);
      // Reachability for default methods is based on live subtypes in general. For keep rules,
      // we need special handling as we essentially might have live subtypes that are outside of
      // the current compilation unit. Keep either the default-method or its implementation method.
      // TODO(b/120959039): Codify the kept-graph expectations for these cases in tests.
      if (holder.isInterface() && target.isVirtualMethod()) {
        if (target.isNonAbstractVirtualMethod()) {
          markVirtualMethodAsLive(target, reason);
        } else {
          DexEncodedMethod implementation = target.getDefaultInterfaceMethodImplementation();
          if (implementation != null) {
            DexClass companion = appView.definitionFor(implementation.method.holder);
            markTypeAsLive(companion.type);
            markVirtualMethodAsLive(implementation, reason);
          }
        }
      }
    } else {
      markDirectStaticOrConstructorMethodAsLive(target, reason);
    }
  }

  private void markFieldAsKept(DexEncodedField target, KeepReason reason) {
    // If this field no longer has a corresponding class, then we have shaken it away before.
    if (appView.definitionFor(target.field.holder) == null) {
      return;
    }
    if (target.accessFlags.isStatic()) {
      markStaticFieldAsLive(target.field, reason);
    } else {
      markInstanceFieldAsReachable(target.field, reason);
    }
  }

  private void markAllLibraryVirtualMethodsReachable(DexClass clazz) {
    assert clazz.isNotProgramClass();
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Marking all methods of library class `%s` as reachable.",
          clazz.type);
    }
    for (DexEncodedMethod encodedMethod : clazz.virtualMethods()) {
      markMethodAsTargeted(encodedMethod, KeepReason.isLibraryMethod());
      markVirtualMethodAsReachable(encodedMethod.method, clazz.isInterface(),
          KeepReason.isLibraryMethod());
    }
  }

  private void processNewlyLiveMethod(DexEncodedMethod method, KeepReason reason) {
    if (liveMethods.add(method, reason)) {
      collectProguardCompatibilityRule(reason);
      DexClass holder = appView.definitionFor(method.method.holder);
      assert holder != null;
      if (holder.isNotProgramClass()) {
        // We do not process library classes.
        return;
      }
      Set<DexEncodedMethod> superCallTargets = superInvokeDependencies.get(method);
      if (superCallTargets != null) {
        for (DexEncodedMethod superCallTarget : superCallTargets) {
          if (Log.ENABLED) {
            Log.verbose(getClass(), "Found super invoke constraint on `%s`.",
                superCallTarget.method);
          }
          markMethodAsTargeted(superCallTarget, KeepReason.invokedViaSuperFrom(method));
          markVirtualMethodAsLive(superCallTarget, KeepReason.invokedViaSuperFrom(method));
        }
      }
      markParameterAndReturnTypesAsLive(method);
      if (appView.definitionFor(method.method.holder).isProgramClass()) {
        processAnnotations(method, method.annotations.annotations);
        method.parameterAnnotationsList.forEachAnnotation(
            annotation -> processAnnotation(method, annotation));
      }
      method.registerCodeReferences(new UseRegistry(options.itemFactory, method));
      // Add all dependent members to the workqueue.
      enqueueRootItems(rootSet.getDependentItems(method));
    }
  }

  private void markParameterAndReturnTypesAsLive(DexEncodedMethod method) {
    for (DexType parameterType : method.method.proto.parameters.values) {
      markTypeAsLive(parameterType);
    }
    markTypeAsLive(method.method.proto.returnType);
  }

  private void collectProguardCompatibilityRule(KeepReason reason) {
    if (reason.isDueToProguardCompatibility() && compatibility != null) {
      compatibility.addRule(reason.getProguardKeepRule());
    }
  }

  private <T extends Descriptor<?, T>> SortedMap<T, Set<DexEncodedMethod>> collectDescriptors(
      Map<DexType, Set<TargetWithContext<T>>> map) {
    SortedMap<T, Set<DexEncodedMethod>> result = new TreeMap<>(PresortedComparable::slowCompare);
    for (Entry<DexType, Set<TargetWithContext<T>>> entry : map.entrySet()) {
      for (TargetWithContext<T> descriptorWithContext : entry.getValue()) {
        T descriptor = descriptorWithContext.getTarget();
        DexEncodedMethod context = descriptorWithContext.getContext();
        result.computeIfAbsent(descriptor, k -> Sets.newIdentityHashSet())
            .add(context);
      }
    }
    return Collections.unmodifiableSortedMap(result);
  }

  private void markClassAsInstantiatedWithReason(DexClass clazz, KeepReason reason) {
    assert clazz.isProgramClass();
    workList.add(Action.markInstantiated(clazz, reason));
    if (clazz.hasDefaultInitializer()) {
      workList.add(Action.markMethodLive(clazz.getDefaultInitializer(), reason));
    }
  }

  private void markClassAsInstantiatedWithCompatRule(DexClass clazz) {
    ProguardKeepRule rule = ProguardConfigurationUtils.buildDefaultInitializerKeepRule(clazz);
    proguardCompatibilityWorkList.add(
        Action.markInstantiated(clazz, KeepReason.dueToProguardCompatibilityKeepRule(rule)));
    if (clazz.hasDefaultInitializer()) {
      proguardCompatibilityWorkList.add(
          Action.markMethodLive(
              clazz.getDefaultInitializer(), KeepReason.dueToProguardCompatibilityKeepRule(rule)));
    }
  }

  private void markMethodAsKeptWithCompatRule(DexEncodedMethod method) {
    DexClass holderClass = appView.definitionFor(method.method.holder);
    ProguardKeepRule rule =
        ProguardConfigurationUtils.buildMethodKeepRule(holderClass, method);
    proguardCompatibilityWorkList.add(
        Action.markMethodLive(method, KeepReason.dueToProguardCompatibilityKeepRule(rule)));
  }

  private void handleReflectiveBehavior(DexEncodedMethod method) {
    DexType originHolder = method.method.holder;
    Origin origin = appInfo.originFor(originHolder);
    IRCode code = method.buildIR(appView, origin);
    Iterator<Instruction> iterator = code.instructionIterator();
    while (iterator.hasNext()) {
      Instruction instruction = iterator.next();
      handleReflectiveBehavior(method, instruction);
    }
  }

  private void handleReflectiveBehavior(DexEncodedMethod method, Instruction instruction) {
    if (!instruction.isInvokeMethod()) {
      return;
    }
    InvokeMethod invoke = instruction.asInvokeMethod();
    DexMethod invokedMethod = invoke.getInvokedMethod();
    DexItemFactory dexItemFactory = appView.dexItemFactory();
    if (invokedMethod == dexItemFactory.classMethods.newInstance) {
      handleJavaLangClassNewInstance(method, invoke);
      return;
    }
    if (invokedMethod == dexItemFactory.constructorMethods.newInstance) {
      handleJavaLangReflectConstructorNewInstance(method, invoke);
      return;
    }
    if (invokedMethod == dexItemFactory.enumMethods.valueOf) {
      handleJavaLangEnumValueOf(method, invoke);
      return;
    }
    if (invokedMethod == dexItemFactory.proxyMethods.newProxyInstance) {
      handleJavaLangReflectProxyNewProxyInstance(method, invoke);
      return;
    }
    if (dexItemFactory.serviceLoaderMethods.isLoadMethod(invokedMethod)) {
      handleServiceLoaderInvocation(method, invoke);
      return;
    }
    if (!isReflectionMethod(dexItemFactory, invokedMethod)) {
      return;
    }
    DexReference identifierItem = identifyIdentifier(invoke, appView);
    if (identifierItem == null) {
      return;
    }
    if (identifierItem.isDexType()) {
      DexClass clazz = appView.definitionFor(identifierItem.asDexType());
      if (clazz != null) {
        markInstantiated(clazz.type, KeepReason.reflectiveUseIn(method));
        if (clazz.hasDefaultInitializer()) {
          markDirectStaticOrConstructorMethodAsLive(
              clazz.getDefaultInitializer(), KeepReason.reflectiveUseIn(method));
        }
      }
    } else if (identifierItem.isDexField()) {
      DexEncodedField encodedField = appView.definitionFor(identifierItem.asDexField());
      if (encodedField != null) {
        // Normally, we generate a -keepclassmembers rule for the field, such that the field is only
        // kept if it is a static field, or if the holder or one of its subtypes are instantiated.
        // However, if the invoked method is a field updater, then we always need to keep instance
        // fields since the creation of a field updater throws a NoSuchFieldException if the field
        // is not present.
        boolean keepClass =
            !encodedField.accessFlags.isStatic()
                && dexItemFactory.atomicFieldUpdaterMethods.isFieldUpdater(invokedMethod);
        if (keepClass) {
          DexClass holderClass = appView.definitionFor(encodedField.field.holder);
          markInstantiated(holderClass.type, KeepReason.reflectiveUseIn(method));
        }
        markFieldAsKept(encodedField, KeepReason.reflectiveUseIn(method));
        // Fields accessed by reflection is marked as both read and written.
        if (encodedField.isStatic()) {
          registerItemWithTargetAndContext(staticFieldsRead, encodedField.field, method);
          registerItemWithTargetAndContext(staticFieldsWritten, encodedField.field, method);
        } else {
          registerItemWithTargetAndContext(instanceFieldsRead, encodedField.field, method);
          registerItemWithTargetAndContext(instanceFieldsWritten, encodedField.field, method);
        }
      }
    } else {
      assert identifierItem.isDexMethod();
      DexEncodedMethod encodedMethod = appView.definitionFor(identifierItem.asDexMethod());
      if (encodedMethod != null) {
        if (encodedMethod.accessFlags.isStatic() || encodedMethod.accessFlags.isConstructor()) {
          markDirectStaticOrConstructorMethodAsLive(
              encodedMethod, KeepReason.reflectiveUseIn(method));
        } else {
          markVirtualMethodAsLive(encodedMethod, KeepReason.reflectiveUseIn(method));
        }
      }
    }
  }

  /** Handles reflective uses of {@link Class#newInstance()}. */
  private void handleJavaLangClassNewInstance(DexEncodedMethod method, InvokeMethod invoke) {
    if (!invoke.isInvokeVirtual()) {
      assert false;
      return;
    }

    DexType instantiatedType =
        ConstantValueUtils.getDexTypeRepresentedByValue(
            invoke.asInvokeVirtual().getReceiver(), appView);
    if (instantiatedType == null || !instantiatedType.isClassType()) {
      // Give up, we can't tell which class is being instantiated, or the type is not a class type.
      // The latter should not happen in practice.
      return;
    }

    DexClass clazz = appView.definitionFor(instantiatedType);
    if (clazz != null && clazz.isProgramClass()) {
      DexEncodedMethod defaultInitializer = clazz.getDefaultInitializer();
      if (defaultInitializer != null) {
        KeepReason reason = KeepReason.reflectiveUseIn(method);
        markClassAsInstantiatedWithReason(clazz, reason);
        markDirectStaticOrConstructorMethodAsLive(defaultInitializer, reason);
      }
    }
  }

  /** Handles reflective uses of {@link java.lang.reflect.Constructor#newInstance(Object...)}. */
  private void handleJavaLangReflectConstructorNewInstance(
      DexEncodedMethod method, InvokeMethod invoke) {
    if (!invoke.isInvokeVirtual()) {
      assert false;
      return;
    }

    Value constructorValue = invoke.asInvokeVirtual().getReceiver().getAliasedValue();
    if (constructorValue.isPhi() || !constructorValue.definition.isInvokeVirtual()) {
      // Give up, we can't tell which class is being instantiated.
      return;
    }

    InvokeVirtual constructorDefinition = constructorValue.definition.asInvokeVirtual();
    if (constructorDefinition.getInvokedMethod()
        != appView.dexItemFactory().classMethods.getDeclaredConstructor) {
      // Give up, we can't tell which constructor is being invoked.
      return;
    }

    DexType instantiatedType =
        ConstantValueUtils.getDexTypeRepresentedByValue(
            constructorDefinition.getReceiver(), appView);
    if (instantiatedType == null || !instantiatedType.isClassType()) {
      // Give up, we can't tell which constructor is being invoked, or the type is not a class type.
      // The latter should not happen in practice.
      return;
    }

    DexClass clazz = appView.definitionFor(instantiatedType);
    if (clazz != null && clazz.isProgramClass()) {
      Value parametersValue = constructorDefinition.inValues().get(1);
      if (parametersValue.isPhi() || !parametersValue.definition.isNewArrayEmpty()) {
        // Give up, we can't tell which constructor is being invoked.
        return;
      }

      Value parametersSizeValue = parametersValue.definition.asNewArrayEmpty().size();
      if (parametersSizeValue.isPhi() || !parametersSizeValue.definition.isConstNumber()) {
        // Give up, we can't tell which constructor is being invoked.
        return;
      }

      DexEncodedMethod initializer = null;

      int parametersSize = parametersSizeValue.definition.asConstNumber().getIntValue();
      if (parametersSize == 0) {
        initializer = clazz.getDefaultInitializer();
      } else {
        DexType[] parameterTypes = new DexType[parametersSize];
        int missingIndices = parametersSize;
        for (Instruction user : parametersValue.uniqueUsers()) {
          if (user.isArrayPut()) {
            ArrayPut arrayPutInstruction = user.asArrayPut();
            if (arrayPutInstruction.array() != parametersValue) {
              return;
            }

            Value indexValue = arrayPutInstruction.index();
            if (indexValue.isPhi() || !indexValue.definition.isConstNumber()) {
              return;
            }
            int index = indexValue.definition.asConstNumber().getIntValue();
            if (index >= parametersSize) {
              return;
            }

            DexType type =
                ConstantValueUtils.getDexTypeRepresentedByValue(
                    arrayPutInstruction.value(), appView);
            if (type == null) {
              return;
            }

            if (parameterTypes[index] == type) {
              continue;
            }
            if (parameterTypes[index] != null) {
              return;
            }
            parameterTypes[index] = type;
            missingIndices--;
          }
        }

        if (missingIndices == 0) {
          initializer = clazz.getInitializer(parameterTypes);
        }
      }

      if (initializer != null) {
        KeepReason reason = KeepReason.reflectiveUseIn(method);
        markClassAsInstantiatedWithReason(clazz, reason);
        markDirectStaticOrConstructorMethodAsLive(initializer, reason);
      }
    }
  }

  /**
   * Handles reflective uses of {@link java.lang.reflect.Proxy#newProxyInstance(ClassLoader,
   * Class[], InvocationHandler)}.
   */
  private void handleJavaLangReflectProxyNewProxyInstance(
      DexEncodedMethod method, InvokeMethod invoke) {
    if (!invoke.isInvokeStatic()) {
      assert false;
      return;
    }

    Value interfacesValue = invoke.arguments().get(1);
    if (interfacesValue.isPhi() || !interfacesValue.definition.isNewArrayEmpty()) {
      // Give up, we can't tell which interfaces the proxy implements.
      return;
    }

    for (Instruction user : interfacesValue.uniqueUsers()) {
      if (!user.isArrayPut()) {
        continue;
      }

      ArrayPut arrayPut = user.asArrayPut();
      DexType type = ConstantValueUtils.getDexTypeRepresentedByValue(arrayPut.value(), appView);
      if (type == null || !type.isClassType()) {
        continue;
      }

      DexClass clazz = appView.definitionFor(type);
      if (clazz != null && clazz.isProgramClass() && clazz.isInterface()) {
        // Add this interface to the set of pinned items to ensure that we do not merge the
        // interface into its subtype and to ensure that the devirtualizer does not perform illegal
        // rewritings of invoke-interface instructions into invoke-virtual instructions.
        pinnedItems.add(clazz.type);
      }
    }
  }

  private void handleJavaLangEnumValueOf(DexEncodedMethod method, InvokeMethod invoke) {
    // The use of java.lang.Enum.valueOf(java.lang.Class, java.lang.String) will indirectly
    // access the values() method of the enum class passed as the first argument. The method
    // SomeEnumClass.valueOf(java.lang.String) which is generated by javac for all enums will
    // call this method.
    if (invoke.inValues().get(0).isConstClass()) {
      DexClass clazz =
          appView.definitionFor(invoke.inValues().get(0).definition.asConstClass().getValue());
      if (clazz.accessFlags.isEnum() && clazz.superType == appView.dexItemFactory().enumType) {
        markEnumValuesAsReachable(clazz, KeepReason.invokedFrom(method));
      }
    }
  }

  private void handleServiceLoaderInvocation(DexEncodedMethod method, InvokeMethod invoke) {
    if (invoke.inValues().size() == 0) {
      // Should never happen.
      return;
    }

    Value argument = invoke.inValues().get(0).getAliasedValue();
    if (!argument.isPhi() && argument.definition.isConstClass()) {
      DexType serviceType = argument.definition.asConstClass().getValue();
      if (!appView.appServices().allServiceTypes().contains(serviceType)) {
        // Should never happen.
        if (Log.ENABLED) {
          options.reporter.warning(
              new StringDiagnostic(
                  "The type `"
                      + serviceType.toSourceString()
                      + "` is being passed to the method `"
                      + invoke.getInvokedMethod().toSourceString()
                      + "`, but was not found in `META-INF/services/`.",
                  appInfo.originFor(method.method.holder)));
        }
        return;
      }

      handleServiceInstantiation(serviceType, KeepReason.reflectiveUseIn(method));
    } else {
      KeepReason reason = KeepReason.reflectiveUseIn(method);
      for (DexType serviceType : appView.appServices().allServiceTypes()) {
        handleServiceInstantiation(serviceType, reason);
      }
    }
  }

  private void handleServiceInstantiation(DexType serviceType, KeepReason reason) {
    instantiatedAppServices.add(serviceType);

    Set<DexType> serviceImplementationTypes =
        appView.appServices().serviceImplementationsFor(serviceType);
    for (DexType serviceImplementationType : serviceImplementationTypes) {
      if (!serviceImplementationType.isClassType()) {
        // Should never happen.
        continue;
      }

      DexClass serviceImplementationClass = appView.definitionFor(serviceImplementationType);
      if (serviceImplementationClass != null && serviceImplementationClass.isProgramClass()) {
        markClassAsInstantiatedWithReason(serviceImplementationClass, reason);
      }
    }
  }

  private static class Action {

    final Kind kind;
    final DexItem target;
    final DexItem context;
    final KeepReason reason;

    private Action(Kind kind, DexItem target, DexItem context, KeepReason reason) {
      this.kind = kind;
      this.target = target;
      this.context = context;
      this.reason = reason;
    }

    public static Action markReachableVirtual(DexMethod method, KeepReason reason) {
      return new Action(Kind.MARK_REACHABLE_VIRTUAL, method, null, reason);
    }

    public static Action markReachableInterface(DexMethod method, KeepReason reason) {
      return new Action(Kind.MARK_REACHABLE_INTERFACE, method, null, reason);
    }

    public static Action markReachableSuper(DexMethod method, DexEncodedMethod from) {
      return new Action(Kind.MARK_REACHABLE_SUPER, method, from, null);
    }

    public static Action markReachableField(DexField field, KeepReason reason) {
      return new Action(Kind.MARK_REACHABLE_FIELD, field, null, reason);
    }

    public static Action markInstantiated(DexClass clazz, KeepReason reason) {
      return new Action(Kind.MARK_INSTANTIATED, clazz, null, reason);
    }

    public static Action markMethodLive(DexEncodedMethod method, KeepReason reason) {
      return new Action(Kind.MARK_METHOD_LIVE, method, null, reason);
    }

    public static Action markMethodKept(DexEncodedMethod method, KeepReason reason) {
      return new Action(Kind.MARK_METHOD_KEPT, method, null, reason);
    }

    public static Action markFieldKept(DexEncodedField field, KeepReason reason) {
      return new Action(Kind.MARK_FIELD_KEPT, field, null, reason);
    }

    private enum Kind {
      MARK_REACHABLE_VIRTUAL,
      MARK_REACHABLE_INTERFACE,
      MARK_REACHABLE_SUPER,
      MARK_REACHABLE_FIELD,
      MARK_INSTANTIATED,
      MARK_METHOD_LIVE,
      MARK_METHOD_KEPT,
      MARK_FIELD_KEPT
    }
  }

  private static class SetWithReason<T> {

    private final Set<T> items = Sets.newIdentityHashSet();

    private final BiConsumer<T, KeepReason> register;

    public SetWithReason(BiConsumer<T, KeepReason> register) {
      this.register = register;
    }

    boolean add(T item, KeepReason reason) {
      register.accept(item, reason);
      return items.add(item);
    }

    boolean contains(T item) {
      return items.contains(item);
    }

    Set<T> getItems() {
      return ImmutableSet.copyOf(items);
    }
  }

  private static final class TargetWithContext<T extends Descriptor<?, T>> {

    private final T target;
    private final DexEncodedMethod context;

    private TargetWithContext(T target, DexEncodedMethod context) {
      this.target = target;
      this.context = context;
    }

    public T getTarget() {
      return target;
    }

    public DexEncodedMethod getContext() {
      return context;
    }

    @Override
    public int hashCode() {
      return target.hashCode() * 31 + context.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof TargetWithContext)) {
        return false;
      }
      TargetWithContext other = (TargetWithContext) obj;
      return (this.target == other.target) && (this.context == other.context);
    }
  }

  private class AnnotationReferenceMarker implements IndexedItemCollection {

    private final DexItem annotationHolder;
    private final DexItemFactory dexItemFactory;

    private AnnotationReferenceMarker(DexItem annotationHolder, DexItemFactory dexItemFactory) {
      this.annotationHolder = annotationHolder;
      this.dexItemFactory = dexItemFactory;
    }

    @Override
    public boolean addClass(DexProgramClass dexProgramClass) {
      return false;
    }

    @Override
    public boolean addField(DexField field) {
      DexClass holder = appView.definitionFor(field.holder);
      if (holder == null) {
        return false;
      }
      DexEncodedField target = holder.lookupStaticField(field);
      if (target != null) {
        // There is no dispatch on annotations, so only keep what is directly referenced.
        if (target.field == field) {
          markStaticFieldAsLive(field, KeepReason.referencedInAnnotation(annotationHolder));
        }
      } else {
        target = holder.lookupInstanceField(field);
        // There is no dispatch on annotations, so only keep what is directly referenced.
        if (target != null && target.field != field) {
          markInstanceFieldAsReachable(field, KeepReason.referencedInAnnotation(annotationHolder));
        }
      }
      return false;
    }

    @Override
    public boolean addMethod(DexMethod method) {
      DexClass holder = appView.definitionFor(method.holder);
      if (holder == null) {
        return false;
      }
      DexEncodedMethod target = holder.lookupDirectMethod(method);
      if (target != null) {
        // There is no dispatch on annotations, so only keep what is directly referenced.
        if (target.method == method) {
          markDirectStaticOrConstructorMethodAsLive(
              target, KeepReason.referencedInAnnotation(annotationHolder));
        }
      } else {
        target = holder.lookupVirtualMethod(method);
        // There is no dispatch on annotations, so only keep what is directly referenced.
        if (target != null && target.method == method) {
          markMethodAsTargeted(target, KeepReason.referencedInAnnotation(annotationHolder));
        }
      }
      return false;
    }

    @Override
    public boolean addString(DexString string) {
      return false;
    }

    @Override
    public boolean addProto(DexProto proto) {
      return false;
    }

    @Override
    public boolean addCallSite(DexCallSite callSite) {
      return false;
    }

    @Override
    public boolean addMethodHandle(DexMethodHandle methodHandle) {
      return false;
    }

    @Override
    public boolean addType(DexType type) {
      // Annotations can also contain the void type, which is not a class type, so filter it out
      // here.
      if (type != dexItemFactory.voidType) {
        markTypeAsLive(type);
      }
      return false;
    }
  }

  private void registerType(DexType type, KeepReason reason) {
    assert getSourceNode(reason) != null;
    if (keptGraphConsumer == null) {
      return;
    }
    registerEdge(getClassGraphNode(type), reason);
  }

  private void registerAnnotation(DexAnnotation annotation, KeepReason reason) {
    assert getSourceNode(reason) != null;
    if (keptGraphConsumer == null) {
      return;
    }
    registerEdge(getAnnotationGraphNode(annotation.annotation.type), reason);
  }

  private void registerMethod(DexEncodedMethod method, KeepReason reason) {
    if (reason.edgeKind() == EdgeKind.IsLibraryMethod) {
      // Don't report edges to actual library methods.
      // TODO(b/120959039): Make sure we do have edges to methods overwriting library methods!
      return;
    }
    assert getSourceNode(reason) != null;
    if (keptGraphConsumer == null) {
      return;
    }
    registerEdge(getMethodGraphNode(method.method), reason);
  }

  private void registerField(DexEncodedField field, KeepReason reason) {
    assert getSourceNode(reason) != null;
    if (keptGraphConsumer == null) {
      return;
    }
    registerEdge(getFieldGraphNode(field.field), reason);
  }

  private void registerEdge(GraphNode target, KeepReason reason) {
    GraphNode sourceNode = getSourceNode(reason);
    // TODO(b/120959039): Make sure we do have edges to nodes deriving library nodes!
    if (!sourceNode.isLibraryNode()) {
      keptGraphConsumer.acceptEdge(sourceNode, target, getEdgeInfo(reason));
    }
  }

  private GraphNode getSourceNode(KeepReason reason) {
    return reason.getSourceNode(this);
  }

  public GraphNode getGraphNode(DexReference reference) {
    if (reference.isDexType()) {
      return getClassGraphNode(reference.asDexType());
    }
    if (reference.isDexMethod()) {
      return getMethodGraphNode(reference.asDexMethod());
    }
    if (reference.isDexField()) {
      return getFieldGraphNode(reference.asDexField());
    }
    throw new Unreachable();
  }

  GraphEdgeInfo getEdgeInfo(KeepReason reason) {
    return reasonInfo.computeIfAbsent(reason.edgeKind(), k -> new GraphEdgeInfo(k));
  }

  AnnotationGraphNode getAnnotationGraphNode(DexItem type) {
    return annotationNodes.computeIfAbsent(type, t -> {
      if (t instanceof DexType) {
        return new AnnotationGraphNode(getClassGraphNode(((DexType) t)));
      }
      throw new Unimplemented("Incomplete support for annotation node on item: " + type.getClass());
    });
  }

  ClassGraphNode getClassGraphNode(DexType type) {
    return classNodes.computeIfAbsent(
        type,
        t -> {
          DexClass definition = appView.definitionFor(t);
          return new ClassGraphNode(
              definition != null && definition.isNotProgramClass(),
              Reference.classFromDescriptor(t.toDescriptorString()));
        });
  }

  MethodGraphNode getMethodGraphNode(DexMethod context) {
    return methodNodes.computeIfAbsent(
        context,
        m -> {
          DexClass holderDefinition = appView.definitionFor(context.holder);
          Builder<TypeReference> builder = ImmutableList.builder();
          for (DexType param : m.proto.parameters.values) {
            builder.add(Reference.typeFromDescriptor(param.toDescriptorString()));
          }
          return new MethodGraphNode(
              holderDefinition != null && holderDefinition.isNotProgramClass(),
              Reference.method(
                  Reference.classFromDescriptor(m.holder.toDescriptorString()),
                  m.name.toString(),
                  builder.build(),
                  m.proto.returnType.isVoidType()
                      ? null
                      : Reference.typeFromDescriptor(m.proto.returnType.toDescriptorString())));
        });
  }

  FieldGraphNode getFieldGraphNode(DexField context) {
    return fieldNodes.computeIfAbsent(
        context,
        f -> {
          DexClass holderDefinition = appView.definitionFor(context.holder);
          return new FieldGraphNode(
              holderDefinition != null && holderDefinition.isNotProgramClass(),
              Reference.field(
                  Reference.classFromDescriptor(f.holder.toDescriptorString()),
                  f.name.toString(),
                  Reference.typeFromDescriptor(f.type.toDescriptorString())));
        });
  }

  KeepRuleGraphNode getKeepRuleGraphNode(ProguardKeepRule rule) {
    return ruleNodes.computeIfAbsent(rule, KeepRuleGraphNode::new);
  }
}
