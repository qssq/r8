// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.ir.desugar.LambdaDescriptor;
import com.android.tools.r8.origin.Origin;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class AppInfoWithSubtyping extends AppInfo {

  // Set of missing classes, discovered during subtypeMap computation.
  private final Set<DexType> missingClasses = Sets.newIdentityHashSet();
  // Map from types to their subtypes.
  private final Map<DexType, ImmutableSet<DexType>> subtypeMap = new IdentityHashMap<>();

  public AppInfoWithSubtyping(DexApplication application) {
    super(application);
    // Recompute subtype map if we have modified the graph.
    populateSubtypeMap(application.asDirect(), application.dexItemFactory);
  }

  protected AppInfoWithSubtyping(AppInfoWithSubtyping previous) {
    super(previous);
    missingClasses.addAll(previous.missingClasses);
    subtypeMap.putAll(previous.subtypeMap);
    assert app() instanceof DirectMappedDexApplication;
  }

  private DirectMappedDexApplication getDirectApplication() {
    // TODO(herhut): Remove need for cast.
    return (DirectMappedDexApplication) app();
  }

  public Iterable<DexLibraryClass> libraryClasses() {
    assert checkIfObsolete();
    return getDirectApplication().libraryClasses();
  }

  public Set<DexType> getMissingClasses() {
    assert checkIfObsolete();
    return Collections.unmodifiableSet(missingClasses);
  }

  public Set<DexType> subtypes(DexType type) {
    assert checkIfObsolete();
    assert type.isClassType();
    ImmutableSet<DexType> subtypes = subtypeMap.get(type);
    return subtypes == null ? ImmutableSet.of() : subtypes;
  }

  private void populateSuperType(Map<DexType, Set<DexType>> map, DexType superType,
      DexClass baseClass, Function<DexType, DexClass> definitions) {
    if (superType != null) {
      Set<DexType> set = map.computeIfAbsent(superType, ignore -> new HashSet<>());
      if (set.add(baseClass.type)) {
        // Only continue recursion if type has been added to set.
        populateAllSuperTypes(map, superType, baseClass, definitions);
      }
    }
  }

  private void populateAllSuperTypes(Map<DexType, Set<DexType>> map, DexType holder,
      DexClass baseClass, Function<DexType, DexClass> definitions) {
    DexClass holderClass = definitions.apply(holder);
    // Skip if no corresponding class is found.
    if (holderClass != null) {
      populateSuperType(map, holderClass.superType, baseClass, definitions);
      if (holderClass.superType != null) {
        holderClass.superType.addDirectSubtype(holder);
      } else {
        // We found java.lang.Object
        assert dexItemFactory().objectType == holder;
      }
      for (DexType inter : holderClass.interfaces.values) {
        populateSuperType(map, inter, baseClass, definitions);
        inter.addInterfaceSubtype(holder);
      }
      if (holderClass.isInterface()) {
        holder.tagAsInteface();
      }
    } else {
      if (baseClass.isProgramClass() || baseClass.isClasspathClass()) {
        missingClasses.add(holder);
      }
      // The subtype chain is broken, at least make this type a subtype of Object.
      if (holder != dexItemFactory().objectType) {
        dexItemFactory().objectType.addDirectSubtype(holder);
      }
    }
  }

  private void populateSubtypeMap(DirectMappedDexApplication app, DexItemFactory dexItemFactory) {
    dexItemFactory.clearSubtypeInformation();
    dexItemFactory.objectType.tagAsSubtypeRoot();
    Map<DexType, Set<DexType>> map = new IdentityHashMap<>();
    for (DexClass clazz : app.allClasses()) {
      populateAllSuperTypes(map, clazz.type, clazz, app::definitionFor);
    }
    for (Map.Entry<DexType, Set<DexType>> entry : map.entrySet()) {
      subtypeMap.put(entry.getKey(), ImmutableSet.copyOf(entry.getValue()));
    }
    assert DexType.validateLevelsAreCorrect(app::definitionFor, dexItemFactory);
  }

  // For mapping invoke virtual instruction to target methods.
  public Set<DexEncodedMethod> lookupVirtualTargets(DexMethod method) {
    assert checkIfObsolete();
    if (method.holder.isArrayType()) {
      // For javac output this will only be clone(), but in general the methods from Object can
      // be invoked with an array type holder.
      return null;
    }
    DexClass root = definitionFor(method.holder);
    if (root == null) {
      // type specified in method does not have a materialized class.
      return null;
    }
    ResolutionResult topTargets = resolveMethodOnClass(method.holder, method);
    if (topTargets.asResultOfResolve() == null) {
      // This will fail at runtime.
      return null;
    }
    // First add the target for receiver type method.type.
    Set<DexEncodedMethod> result = new HashSet<>();
    topTargets.forEachTarget(result::add);
    // Add all matching targets from the subclass hierarchy.
    for (DexType type : subtypes(method.holder)) {
      DexClass clazz = definitionFor(type);
      if (!clazz.isInterface()) {
        ResolutionResult methods = resolveMethodOnClass(type, method);
        methods.forEachTarget(
            target -> {
              if (target.isVirtualMethod()) {
                result.add(target);
              }
            });
      }
    }
    return result;
  }

  /**
   * Lookup super method following the super chain from the holder of {@code method}.
   * <p>
   * This method will resolve the method on the holder of {@code method} and only return a non-null
   * value if the result of resolution was an instance (i.e. non-static) method.
   * <p>
   * Additionally, this will also verify that the invoke super is valid, i.e., it is on the same
   * type or a super type of the current context. See comment in {@link
   * com.android.tools.r8.ir.conversion.JarSourceCode#invokeType}.
   *
   * @param method the method to lookup
   * @param invocationContext the class the invoke is contained in, i.e., the holder of the caller.
   * @return The actual target for {@code method} or {@code null} if none found.
   */
  @Override
  public DexEncodedMethod lookupSuperTarget(DexMethod method, DexType invocationContext) {
    assert checkIfObsolete();
    if (!invocationContext.isSubtypeOf(method.holder, this)) {
      DexClass contextClass = definitionFor(invocationContext);
      throw new CompilationError(
          "Illegal invoke-super to " + method.toSourceString() + " from class " + invocationContext,
          contextClass != null ? contextClass.getOrigin() : Origin.unknown());
    }
    return super.lookupSuperTarget(method, invocationContext);
  }

  protected boolean hasAnyInstantiatedLambdas(DexType type) {
    assert checkIfObsolete();
    return true; // Don't know, there might be.
  }

  // For mapping invoke interface instruction to target methods.
  public Set<DexEncodedMethod> lookupInterfaceTargets(DexMethod method) {
    assert checkIfObsolete();
    // First check that there is a target for this invoke-interface to hit. If there is none,
    // this will fail at runtime.
    ResolutionResult topTarget = resolveMethodOnInterface(method.holder, method);
    if (topTarget.asResultOfResolve() == null) {
      return null;
    }

    Set<DexEncodedMethod> result = new HashSet<>();
    if (topTarget.hasSingleTarget()) {
      // Add default interface methods to the list of targets.
      //
      // This helps to make sure we take into account synthesized lambda classes
      // that we are not aware of. Like in the following example, we know that all
      // classes, XX in this case, override B::bar(), but there are also synthesized
      // classes for lambda which don't, so we still need default method to be live.
      //
      //   public static void main(String[] args) {
      //     X x = () -> {};
      //     x.bar();
      //   }
      //
      //   interface X {
      //     void foo();
      //     default void bar() { }
      //   }
      //
      //   class XX implements X {
      //     public void foo() { }
      //     public void bar() { }
      //   }
      //
      DexEncodedMethod singleTarget = topTarget.asSingleTarget();
      if (singleTarget.getCode() != null && hasAnyInstantiatedLambdas(singleTarget.method.holder)) {
        result.add(singleTarget);
      }
    }

    Set<DexType> set = subtypes(method.holder);
    for (DexType type : set) {
      DexClass clazz = definitionFor(type);
      // Default methods are looked up when looking at a specific subtype that does not
      // override them, so we ignore interfaces here. Otherwise, we would look up default methods
      // that are factually never used.
      if (!clazz.isInterface()) {
        ResolutionResult targetMethods = resolveMethodOnClass(type, method);
        targetMethods.forEachTarget(result::add);
      }
    }
    return result;
  }

  /**
   * Resolve the methods implemented by the lambda expression that created the {@code callSite}.
   *
   * <p>If {@code callSite} was not created as a result of a lambda expression (i.e. the metafactory
   * is not {@code LambdaMetafactory}), the empty set is returned.
   *
   * <p>If the metafactory is neither {@code LambdaMetafactory} nor {@code StringConcatFactory}, a
   * warning is issued.
   *
   * <p>The returned set of methods all have {@code callSite.methodName} as the method name.
   *
   * @param callSite Call site to resolve.
   * @return Methods implemented by the lambda expression that created the {@code callSite}.
   */
  public Set<DexEncodedMethod> lookupLambdaImplementedMethods(DexCallSite callSite) {
    assert checkIfObsolete();
    List<DexType> callSiteInterfaces = LambdaDescriptor.getInterfaces(callSite, this);
    if (callSiteInterfaces == null || callSiteInterfaces.isEmpty()) {
      return Collections.emptySet();
    }
    Set<DexEncodedMethod> result = new HashSet<>();
    Deque<DexType> worklist = new ArrayDeque<>(callSiteInterfaces);
    Set<DexType> visited = Sets.newIdentityHashSet();
    while (!worklist.isEmpty()) {
      DexType iface = worklist.removeFirst();
      if (iface.isUnknown()) {
        // Skip this interface. If the lambda only implements missing library interfaces and not any
        // program interfaces, then minification and tree shaking are not interested in this
        // DexCallSite anyway, so skipping this interface is harmless. On the other hand, if
        // minification is run on a program with a lambda interface that implements both a missing
        // library interface and a present program interface, then we might minify the method name
        // on the program interface even though it should be kept the same as the (missing) library
        // interface method. That is a shame, but minification is not suited for incomplete programs
        // anyway.
        continue;
      }
      if (!visited.add(iface)) {
        // Already visited previously. May happen due to "diamond shapes" in the interface
        // hierarchy.
        continue;
      }
      assert iface.isInterface();
      DexClass clazz = definitionFor(iface);
      if (clazz != null) {
        for (DexEncodedMethod method : clazz.virtualMethods()) {
          if (method.method.name == callSite.methodName && method.accessFlags.isAbstract()) {
            result.add(method);
          }
        }
        Collections.addAll(worklist, clazz.interfaces.values);
      }
    }
    return result;
  }

  public boolean isStringConcat(DexMethodHandle bootstrapMethod) {
    assert checkIfObsolete();
    return bootstrapMethod.type.isInvokeStatic()
        && (bootstrapMethod.asMethod() == dexItemFactory().stringConcatWithConstantsMethod
            || bootstrapMethod.asMethod() == dexItemFactory().stringConcatMethod);
  }

  @Override
  public void registerNewType(DexType newType, DexType superType) {
    assert checkIfObsolete();
    // Register the relationship between this type and its superType.
    superType.addDirectSubtype(newType);
  }

  @Override
  public boolean hasSubtyping() {
    assert checkIfObsolete();
    return true;
  }

  @Override
  public AppInfoWithSubtyping withSubtyping() {
    assert checkIfObsolete();
    return this;
  }
}
