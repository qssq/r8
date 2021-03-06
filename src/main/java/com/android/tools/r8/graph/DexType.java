// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import static com.android.tools.r8.ir.desugar.InterfaceMethodRewriter.COMPANION_CLASS_NAME_SUFFIX;
import static com.android.tools.r8.ir.desugar.InterfaceMethodRewriter.DISPATCH_CLASS_NAME_SUFFIX;
import static com.android.tools.r8.ir.desugar.LambdaRewriter.LAMBDA_CLASS_NAME_PREFIX;
import static com.android.tools.r8.ir.desugar.LambdaRewriter.LAMBDA_GROUP_CLASS_NAME_PREFIX;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.ir.desugar.Java8MethodRewriter;
import com.android.tools.r8.ir.desugar.TwrCloseResourceRewriter;
import com.android.tools.r8.naming.NamingLens;
import com.android.tools.r8.utils.DescriptorUtils;
import com.android.tools.r8.utils.InternalOptions.OutlineOptions;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public class DexType extends DexReference implements PresortedComparable<DexType> {
  public static final DexType[] EMPTY_ARRAY = {};
  private static final int ROOT_LEVEL = 0;
  private static final int UNKNOWN_LEVEL = -1;
  private static final int INTERFACE_LEVEL = -2;

  // Since most Java types has no sub-types, we can just share an empty immutable set until we need
  // to add to it.
  private static final Set<DexType> NO_DIRECT_SUBTYPE = ImmutableSet.of();

  public final DexString descriptor;
  private String toStringCache = null;
  private int hierarchyLevel = UNKNOWN_LEVEL;
  /**
   * Set of direct subtypes. This set has to remain sorted to ensure determinism. The actual sorting
   * is not important but {@link #slowCompareTo(DexType)} works well.
   */
  private Set<DexType> directSubtypes = NO_DIRECT_SUBTYPE;

  // Caching what interfaces this type is implementing. This includes super-interface hierarchy.
  private Set<DexType> implementedInterfaces;

  DexType(DexString descriptor) {
    assert !descriptor.toString().contains(".");
    this.descriptor = descriptor;
  }

  @Override
  public int computeHashCode() {
    return descriptor.hashCode();
  }

  @Override
  public boolean computeEquals(Object other) {
    if (other instanceof DexType) {
      return descriptor.equals(((DexType) other).descriptor);
    }
    return false;
  }

  private void ensureDirectSubTypeSet() {
    if (directSubtypes == NO_DIRECT_SUBTYPE) {
      directSubtypes = new TreeSet<>(DexType::slowCompareTo);
    }
  }

  private void setLevel(int level) {
    if (level == hierarchyLevel) {
      return;
    }
    if (hierarchyLevel == INTERFACE_LEVEL) {
      assert level == ROOT_LEVEL + 1;
    } else if (level == INTERFACE_LEVEL) {
      assert hierarchyLevel == ROOT_LEVEL + 1 || hierarchyLevel == UNKNOWN_LEVEL;
      hierarchyLevel = INTERFACE_LEVEL;
    } else {
      assert hierarchyLevel == UNKNOWN_LEVEL;
      hierarchyLevel = level;
    }
  }

  public synchronized void addDirectSubtype(DexType type) {
    assert hierarchyLevel != UNKNOWN_LEVEL;
    ensureDirectSubTypeSet();
    directSubtypes.add(type);
    type.setLevel(hierarchyLevel + 1);
  }

  void tagAsSubtypeRoot() {
    setLevel(ROOT_LEVEL);
  }

  void tagAsInteface() {
    setLevel(INTERFACE_LEVEL);
  }

  public boolean isMissingOrHasMissingSuperType(DexDefinitionSupplier definitions) {
    DexClass clazz = definitions.definitionFor(this);
    return clazz == null || clazz.hasMissingSuperType(definitions);
  }

  public boolean isInterface() {
    assert hierarchyLevel != UNKNOWN_LEVEL : "Program class missing: " + this;
    assert isClassType();
    return hierarchyLevel == INTERFACE_LEVEL;
  }

  public boolean isExternalizable(DexDefinitionSupplier definitions) {
    return implementedInterfaces(definitions)
        .contains(definitions.dexItemFactory().externalizableType);
  }

  public boolean isSerializable(DexDefinitionSupplier definitions) {
    return implementedInterfaces(definitions)
        .contains(definitions.dexItemFactory().serializableType);
  }

  public boolean classInitializationMayHaveSideEffects(DexDefinitionSupplier definitions) {
    return classInitializationMayHaveSideEffects(definitions, Predicates.alwaysFalse());
  }

  public boolean classInitializationMayHaveSideEffects(
      DexDefinitionSupplier definitions, Predicate<DexType> ignore) {
    DexClass clazz = definitions.definitionFor(this);
    return clazz == null || clazz.classInitializationMayHaveSideEffects(definitions, ignore);
  }

  public boolean initializationOfParentTypesMayHaveSideEffects(AppInfo appInfo) {
    return initializationOfParentTypesMayHaveSideEffects(appInfo, Predicates.alwaysFalse());
  }

  public boolean initializationOfParentTypesMayHaveSideEffects(
      AppInfo appInfo, Predicate<DexType> ignore) {
    DexClass clazz = appInfo.definitionFor(this);
    return clazz == null || clazz.initializationOfParentTypesMayHaveSideEffects(appInfo, ignore);
  }

  public boolean isUnknown() {
    return hierarchyLevel == UNKNOWN_LEVEL;
  }

  synchronized void addInterfaceSubtype(DexType type) {
    // Interfaces all inherit from java.lang.Object. However, we assign a special level to
    // identify them later on.
    setLevel(INTERFACE_LEVEL);
    ensureDirectSubTypeSet();
    directSubtypes.add(type);
  }

  static void clearSubtypeInformation(DexType type) {
    type.hierarchyLevel = UNKNOWN_LEVEL;
    type.directSubtypes = NO_DIRECT_SUBTYPE;
  }

  public boolean isSubtypeOf(DexType other, DexDefinitionSupplier definitions) {
    return this == other || isStrictSubtypeOf(other, definitions);
  }

  public boolean hasSubtypes() {
    return !directSubtypes.isEmpty();
  }

  public boolean isStrictSubtypeOf(DexType other, DexDefinitionSupplier definitions) {
    // For all erroneous cases, saying `no`---not a strict subtype---is conservative.
    return isStrictSubtypeOf(other, definitions, false);
  }

  // Depending on optimizations, conservative answer of subtype relation may vary.
  // Pass different `orElse` in that case.
  public boolean isStrictSubtypeOf(
      DexType other, DexDefinitionSupplier definitions, boolean orElse) {
    if (this == other) {
      return false;
    }
    // Treat the object class special as it is always the supertype, even in the case of broken
    // subtype chains.
    if (this == definitions.dexItemFactory().objectType) {
      return false;
    }
    if (other == definitions.dexItemFactory().objectType) {
      return true;
    }
    if (this.hierarchyLevel == INTERFACE_LEVEL) {
      return isInterfaceSubtypeOf(this, other, definitions);
    }
    if (other.hierarchyLevel == INTERFACE_LEVEL) {
      return other.directSubtypes.stream().anyMatch(subtype -> isSubtypeOf(subtype, definitions));
    }
    return isSubtypeOfClass(other, definitions, orElse);
  }

  private boolean isInterfaceSubtypeOf(
      DexType candidate, DexType other, DexDefinitionSupplier definitions) {
    if (candidate == other || other == definitions.dexItemFactory().objectType) {
      return true;
    }
    DexClass candidateHolder = definitions.definitionFor(candidate);
    if (candidateHolder == null) {
      return false;
    }
    for (DexType iface : candidateHolder.interfaces.values) {
      assert iface.hierarchyLevel == INTERFACE_LEVEL;
      if (isInterfaceSubtypeOf(iface, other, definitions)) {
        return true;
      }
    }
    return false;
  }

  private boolean isSubtypeOfClass(
      DexType other, DexDefinitionSupplier definitions, boolean orElse) {
    DexType self = this;
    if (other.hierarchyLevel == UNKNOWN_LEVEL) {
      // We have no definition for this class, hence it is not part of the hierarchy.
      return orElse;
    }
    while (other.hierarchyLevel < self.hierarchyLevel) {
      DexClass holder = definitions.definitionFor(self);
      assert holder != null && !holder.isInterface();
      self = holder.superType;
    }
    return self == other;
  }

  public Set<DexType> allImmediateSubtypes() {
    return directSubtypes;
  }

  /**
   * Apply the given function to all classes that directly extend this class.
   * <p>
   * If this class is an interface, then this method will visit all sub-interfaces. This deviates
   * from the dex-file encoding, where subinterfaces "implement" their super interfaces. However,
   * it is consistent with the source language.
   */
  public void forAllExtendsSubtypes(Consumer<DexType> f) {
    allExtendsSubtypes().forEach(f);
  }

  public Iterable<DexType> allExtendsSubtypes() {
    assert hierarchyLevel != UNKNOWN_LEVEL;
    if (hierarchyLevel == INTERFACE_LEVEL) {
      return Iterables.filter(directSubtypes, DexType::isInterface);
    } else if (hierarchyLevel == ROOT_LEVEL) {
      // This is the object type. Filter out interfaces
      return Iterables.filter(directSubtypes, t -> !t.isInterface());
    } else {
      return directSubtypes;
    }
  }

  /**
   * Apply the given function to all classes that directly implement this interface.
   * <p>
   * The implementation does not consider how the hierarchy is encoded in the dex file, where
   * interfaces "implement" their super interfaces. Instead it takes the view of the source
   * language, where interfaces "extend" their superinterface.
   */
  public void forAllImplementsSubtypes(Consumer<DexType> f) {
    allImplementsSubtypes().forEach(f);
  }

  public Iterable<DexType> allImplementsSubtypes() {
    if (hierarchyLevel == INTERFACE_LEVEL) {
      return Iterables.filter(directSubtypes, subtype -> !subtype.isInterface());
    }
    return ImmutableList.of();
  }

  public static Iterable<DexType> allInterfaces(DexItemFactory dexItemFactory) {
    assert dexItemFactory.objectType.hierarchyLevel == ROOT_LEVEL;
    return Iterables.filter(dexItemFactory.objectType.directSubtypes, DexType::isInterface);
  }

  public static void forAllInterfaces(DexItemFactory factory, Consumer<DexType> f) {
    allInterfaces(factory).forEach(f);
  }

  /**
   * Collect all interfaces that this type directly or indirectly implements.
   *
   * @param definitions where the definition of a certain {@link DexType} is looked up.
   * @return a set of interfaces of {@link DexType}.
   */
  public Set<DexType> implementedInterfaces(DexDefinitionSupplier definitions) {
    if (implementedInterfaces != null) {
      return implementedInterfaces;
    }
    synchronized (this) {
      if (implementedInterfaces == null) {
        Set<DexType> interfaces = Sets.newIdentityHashSet();
        implementedInterfaces(definitions, interfaces);
        implementedInterfaces = interfaces;
      }
    }
    return implementedInterfaces;
  }

  private void implementedInterfaces(DexDefinitionSupplier definitions, Set<DexType> interfaces) {
    DexClass dexClass = definitions.definitionFor(this);
    // Loop to traverse the super type hierarchy of the current type.
    while (dexClass != null) {
      if (dexClass.isInterface()) {
        interfaces.add(dexClass.type);
      }
      for (DexType itf : dexClass.interfaces.values) {
        itf.implementedInterfaces(definitions, interfaces);
      }
      if (dexClass.superType == null) {
        break;
      }
      dexClass = definitions.definitionFor(dexClass.superType);
    }
  }

  public boolean isSamePackage(DexType other) {
    return getPackageDescriptor().equals(other.getPackageDescriptor());
  }

  public String toDescriptorString() {
    return descriptor.toString();
  }

  @Override
  public String toSourceString() {
    if (toStringCache == null) {
      // TODO(ager): Pass in a ProguardMapReader to map names back to original names.
      if (DexItemFactory.isInternalSentinel(this)) {
        toStringCache = descriptor.toString();
      } else {
        toStringCache = DescriptorUtils.descriptorToJavaType(toDescriptorString());
      }
    }
    return toStringCache;
  }

  public char toShorty() {
    char c = (char) descriptor.content[0];
    return c == '[' ? 'L' : c;
  }

  @Override
  public String toSmaliString() {
    return toDescriptorString();
  }

  @Override
  public String toString() {
    return toSourceString();
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection collection,
      DexMethod method, int instructionOffset) {
    if (collection.addType(this)) {
      collection.getRenamedDescriptor(this).collectIndexedItems(collection, method,
          instructionOffset);
    }
  }

  @Override
  public void flushCachedValues() {
    super.flushCachedValues();
    toStringCache = null;
  }

  @Override
  public int getOffset(ObjectToOffsetMapping mapping) {
    return mapping.getOffsetFor(this);
  }

  @Override
  public boolean isDexType() {
    return true;
  }

  @Override
  public DexType asDexType() {
    return this;
  }

  @Override
  public int compareTo(DexType other) {
    return sortedCompareTo(other.getSortedIndex());
  }

  @Override
  public int slowCompareTo(DexType other) {
    return descriptor.slowCompareTo(other.descriptor);
  }

  @Override
  public int slowCompareTo(DexType other, NamingLens namingLens) {
    DexString thisDescriptor = namingLens.lookupDescriptor(this);
    DexString otherDescriptor = namingLens.lookupDescriptor(other);
    return thisDescriptor.slowCompareTo(otherDescriptor);
  }

  @Override
  public int layeredCompareTo(DexType other, NamingLens namingLens) {
    DexString thisDescriptor = namingLens.lookupDescriptor(this);
    DexString otherDescriptor = namingLens.lookupDescriptor(other);
    return thisDescriptor.compareTo(otherDescriptor);
  }

  public boolean isPrimitiveType() {
    return isPrimitiveType((char) descriptor.content[0]);
  }

  private boolean isPrimitiveType(char c) {
    return c == 'Z' || c == 'B' || c == 'S' || c == 'C' || c == 'I' || c == 'F' || c == 'J'
        || c == 'D';
  }

  public boolean isVoidType() {
    return (char) descriptor.content[0] == 'V';
  }

  public boolean isBooleanType() {
    return descriptor.content[0] == 'Z';
  }

  public boolean isByteType() {
    return descriptor.content[0] == 'B';
  }

  public boolean isCharType() {
    return descriptor.content[0] == 'C';
  }

  public boolean isShortType() {
    return descriptor.content[0] == 'S';
  }

  public boolean isIntType() {
    return descriptor.content[0] == 'I';
  }

  public boolean isFloatType() {
    return descriptor.content[0] == 'F';
  }

  public boolean isLongType() {
    return descriptor.content[0] == 'J';
  }

  public boolean isDoubleType() {
    return descriptor.content[0] == 'D';
  }

  public boolean isArrayType() {
    char firstChar = (char) descriptor.content[0];
    return firstChar == '[';
  }

  public boolean isClassType() {
    char firstChar = (char) descriptor.content[0];
    return firstChar == 'L';
  }

  public boolean isPrimitiveArrayType() {
    if (!isArrayType()) {
      return false;
    }
    return isPrimitiveType((char) descriptor.content[1]);
  }

  public boolean isD8R8SynthesizedClassType() {
    String name = toSourceString();
    return name.contains(COMPANION_CLASS_NAME_SUFFIX)
        || name.contains(DISPATCH_CLASS_NAME_SUFFIX)
        || name.contains(LAMBDA_CLASS_NAME_PREFIX)
        || name.contains(LAMBDA_GROUP_CLASS_NAME_PREFIX)
        || name.contains(OutlineOptions.CLASS_NAME)
        || name.contains(TwrCloseResourceRewriter.UTILITY_CLASS_NAME)
        || name.contains(Java8MethodRewriter.UTILITY_CLASS_NAME_PREFIX);
  }

  public int elementSizeForPrimitiveArrayType() {
    assert isPrimitiveArrayType();
    switch (descriptor.content[1]) {
      case 'Z':  // boolean
      case 'B':  // byte
        return 1;
      case 'S':  // short
      case 'C':  // char
        return 2;
      case 'I':  // int
      case 'F':  // float
        return 4;
      case 'J':  // long
      case 'D':  // double
        return 8;
      default:
        throw new Unreachable("Not array of primitives '" + descriptor + "'");
    }
  }

  public int getNumberOfLeadingSquareBrackets() {
    int leadingSquareBrackets = 0;
    while (descriptor.content[leadingSquareBrackets] == '[') {
      leadingSquareBrackets++;
    }
    return leadingSquareBrackets;
  }

  public DexType toBaseType(DexItemFactory dexItemFactory) {
    int leadingSquareBrackets = getNumberOfLeadingSquareBrackets();
    if (leadingSquareBrackets == 0) {
      return this;
    }
    DexString newDesc = dexItemFactory.createString(descriptor.size - leadingSquareBrackets,
        Arrays.copyOfRange(descriptor.content, leadingSquareBrackets, descriptor.content.length));
    return dexItemFactory.createType(newDesc);
  }

  public DexType replaceBaseType(DexType newBase, DexItemFactory dexItemFactory) {
    assert this.isArrayType();
    assert !newBase.isArrayType();
    int leadingSquareBrackets = getNumberOfLeadingSquareBrackets();
    byte[] content = new byte[newBase.descriptor.content.length + leadingSquareBrackets];
    Arrays.fill(content, 0, leadingSquareBrackets, (byte) '[');
    System.arraycopy(newBase.descriptor.content, 0, content, leadingSquareBrackets,
        newBase.descriptor.content.length);
    DexString newDesc = dexItemFactory
        .createString(newBase.descriptor.size + leadingSquareBrackets, content);
    return dexItemFactory.createType(newDesc);
  }

  public DexType toArrayElementType(DexItemFactory dexItemFactory) {
    assert this.isArrayType();
    DexString newDesc = dexItemFactory.createString(descriptor.size - 1,
        Arrays.copyOfRange(descriptor.content, 1, descriptor.content.length));
    return dexItemFactory.createType(newDesc);
  }

  static boolean validateLevelsAreCorrect(Function<DexType, DexClass> definitions,
      DexItemFactory dexItemFactory) {
    Set<DexType> seenTypes = Sets.newIdentityHashSet();
    Deque<DexType> worklist = new ArrayDeque<>();
    DexType objectType = dexItemFactory.objectType;
    worklist.add(objectType);
    while (!worklist.isEmpty()) {
      DexType next = worklist.pop();
      DexClass nextHolder = definitions.apply(next);
      DexType superType;
      if (nextHolder == null) {
        // We might lack the definition of Object, so guard against that.
        superType = next == dexItemFactory.objectType ? null : dexItemFactory.objectType;
      } else {
        superType = nextHolder.superType;
      }
      assert !seenTypes.contains(next);
      seenTypes.add(next);
      if (superType == null) {
        assert next.hierarchyLevel == ROOT_LEVEL;
      } else {
        assert superType.hierarchyLevel == next.hierarchyLevel - 1
            || (superType.hierarchyLevel == ROOT_LEVEL && next.hierarchyLevel == INTERFACE_LEVEL);
        assert superType.directSubtypes.contains(next);
      }
      if (next.hierarchyLevel != INTERFACE_LEVEL) {
        // Only traverse the class hierarchy subtypes, not interfaces.
        worklist.addAll(next.directSubtypes);
      } else if (nextHolder != null) {
        // Test that the interfaces of this class are interfaces and have this class as subtype.
        for (DexType iface : nextHolder.interfaces.values) {
          assert iface.directSubtypes.contains(next);
          assert iface.hierarchyLevel == INTERFACE_LEVEL;
        }
      }
    }
    return true;
  }

  private String getPackageOrName(boolean packagePart) {
    assert isClassType();
    String descriptor = toDescriptorString();
    int lastSeparator = descriptor.lastIndexOf('/');
    if (lastSeparator == -1) {
      return packagePart ? "" : descriptor.substring(1, descriptor.length() - 1);
    } else {
      return packagePart ? descriptor.substring(1, lastSeparator)
          : descriptor.substring(lastSeparator + 1, descriptor.length() - 1);
    }
  }

  public DexType getSingleSubtype() {
    assert hierarchyLevel != UNKNOWN_LEVEL;
    if (directSubtypes.size() == 1) {
      return Iterables.getFirst(directSubtypes, null);
    } else {
      return null;
    }
  }

  public String getPackageDescriptor() {
    return getPackageOrName(true);
  }

  public String getName() {
    if (isPrimitiveType()) {
      return toSourceString();
    }
    return getPackageOrName(false);
  }

  /** Get the fully qualified name using '/' in place of '.', aka the "internal type name" in ASM */
  public String getInternalName() {
    assert isClassType() || isArrayType();
    return DescriptorUtils.descriptorToInternalName(toDescriptorString());
  }

  public boolean isImmediateSubtypeOf(DexType type) {
    assert hierarchyLevel != UNKNOWN_LEVEL;
    return type.directSubtypes.contains(this);
  }

  public DexType computeLeastUpperBoundOfClasses(DexDefinitionSupplier definitions, DexType other) {
    if (this == other) {
      return this;
    }
    DexType objectType = definitions.dexItemFactory().objectType;
    // If we have no definition for either class, stop proceeding.
    if (hierarchyLevel == UNKNOWN_LEVEL || other.hierarchyLevel == UNKNOWN_LEVEL) {
      return objectType;
    }
    if (this == objectType || other == objectType) {
      return objectType;
    }
    DexType t1;
    DexType t2;
    if (other.hierarchyLevel < this.hierarchyLevel) {
      t1 = other;
      t2 = this;
    } else {
      t1 = this;
      t2 = other;
    }
    // From now on, t2.hierarchyLevel >= t1.hierarchyLevel
    DexClass dexClass;
    // Make both of other and this in the same level.
    while (t2.hierarchyLevel > t1.hierarchyLevel) {
      dexClass = definitions.definitionFor(t2);
      if (dexClass == null || dexClass.superType == null) {
        return objectType;
      }
      t2 = dexClass.superType;
    }
    // At this point, they are at the same level.
    // lubType starts from t1, and will move up; t2 starts from itself, and will move up, too.
    // They move up in their own hierarchy tree, and will repeat the process until they meet.
    // (It will stop at anytime when either one's definition is not found.)
    DexType lubType = t1;
    while (t2 != lubType) {
      dexClass = definitions.definitionFor(t2);
      if (dexClass == null) {
        lubType = objectType;
        break;
      }
      t2 = dexClass.superType;
      dexClass = definitions.definitionFor(lubType);
      if (dexClass == null) {
        lubType = objectType;
        break;
      }
      lubType = dexClass.superType;
    }
    return lubType;
  }
}
