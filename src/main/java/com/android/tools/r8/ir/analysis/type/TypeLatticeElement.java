// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.analysis.type;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.DexDefinitionSupplier;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.code.Value;

/**
 * The base abstraction of lattice elements for local type analysis.
 */
public abstract class TypeLatticeElement {
  public static final BottomTypeLatticeElement BOTTOM = BottomTypeLatticeElement.getInstance();
  public static final TopTypeLatticeElement TOP = TopTypeLatticeElement.getInstance();
  static final BooleanTypeLatticeElement BOOLEAN = BooleanTypeLatticeElement.getInstance();
  static final ByteTypeLatticeElement BYTE = ByteTypeLatticeElement.getInstance();
  static final ShortTypeLatticeElement SHORT = ShortTypeLatticeElement.getInstance();
  static final CharTypeLatticeElement CHAR = CharTypeLatticeElement.getInstance();
  public static final IntTypeLatticeElement INT = IntTypeLatticeElement.getInstance();
  public static final FloatTypeLatticeElement FLOAT = FloatTypeLatticeElement.getInstance();
  public static final SingleTypeLatticeElement SINGLE = SingleTypeLatticeElement.getInstance();
  public static final LongTypeLatticeElement LONG = LongTypeLatticeElement.getInstance();
  public static final DoubleTypeLatticeElement DOUBLE = DoubleTypeLatticeElement.getInstance();
  public static final WideTypeLatticeElement WIDE = WideTypeLatticeElement.getInstance();
  public static final ReferenceTypeLatticeElement NULL =
      ReferenceTypeLatticeElement.getNullTypeLatticeElement();

  public boolean isNullable() {
    return nullability().isNullable();
  }

  public abstract Nullability nullability();

  /**
   * Defines how to join with null or switch to nullable lattice element.
   *
   * @return {@link TypeLatticeElement} a result of joining with null.
   */
  public TypeLatticeElement asNullable() {
    return isNullable() ? this : TOP;
  }

  /**
   * Defines how to switch to non-nullable lattice element.
   *
   * @return {@link TypeLatticeElement} a similar lattice element with nullable flag flipped.
   */
  public TypeLatticeElement asNonNullable() {
    return BOTTOM;
  }

  /**
   * Computes the least upper bound of the current and the other elements.
   *
   * @param other {@link TypeLatticeElement} to join.
   * @param definitions {@link DexDefinitionSupplier}.
   * @return {@link TypeLatticeElement}, a least upper bound of {@param this} and {@param other}.
   */
  public TypeLatticeElement join(TypeLatticeElement other, DexDefinitionSupplier definitions) {
    if (this == other) {
      return this;
    }
    if (isBottom()) {
      return other;
    }
    if (other.isBottom()) {
      return this;
    }
    if (isTop() || other.isTop()) {
      return TOP;
    }
    if (isPrimitive()) {
      return other.isPrimitive()
          ? asPrimitiveTypeLatticeElement().join(other.asPrimitiveTypeLatticeElement())
          : TOP;
    }
    if (other.isPrimitive()) {
      // By the above case, !(isPrimitive())
      return TOP;
    }
    // From now on, this and other are precise reference types, i.e., either ArrayType or ClassType.
    assert isReference() && other.isReference();
    assert isPreciseType() && other.isPreciseType();
    Nullability nullabilityJoin = nullability().join(other.nullability());
    if (isNullType()) {
      return other.asReferenceTypeLatticeElement().getOrCreateVariant(nullabilityJoin);
    }
    if (other.isNullType()) {
      return this.asReferenceTypeLatticeElement().getOrCreateVariant(nullabilityJoin);
    }
    if (getClass() != other.getClass()) {
      return objectClassType(definitions, nullabilityJoin);
    }
    // From now on, getClass() == other.getClass()
    if (isArrayType()) {
      assert other.isArrayType();
      return asArrayTypeLatticeElement().join(other.asArrayTypeLatticeElement(), definitions);
    }
    if (isClassType()) {
      assert other.isClassType();
      return asClassTypeLatticeElement().join(other.asClassTypeLatticeElement(), definitions);
    }
    throw new Unreachable("unless a new type lattice is introduced.");
  }

  public static TypeLatticeElement join(
      Iterable<TypeLatticeElement> typeLattices, DexDefinitionSupplier definitions) {
    TypeLatticeElement result = BOTTOM;
    for (TypeLatticeElement other : typeLattices) {
      result = result.join(other, definitions);
    }
    return result;
  }

  /**
   * Determines the strict partial order of the given {@link TypeLatticeElement}s.
   *
   * @param other expected to be *strictly* bigger than {@param this}
   * @param definitions {@link DexDefinitionSupplier} to compute the least upper bound of {@link
   *     TypeLatticeElement}
   * @return {@code true} if {@param this} is strictly less than {@param other}.
   */
  public boolean strictlyLessThan(TypeLatticeElement other, DexDefinitionSupplier definitions) {
    if (equals(other)) {
      return false;
    }
    TypeLatticeElement lub = join(other, definitions);
    return !equals(lub) && other.equals(lub);
  }

  /**
   * Determines the partial order of the given {@link TypeLatticeElement}s.
   *
   * @param other expected to be bigger than or equal to {@param this}
   * @param definitions {@link DexDefinitionSupplier} to compute the least upper bound of {@link
   *     TypeLatticeElement}
   * @return {@code true} if {@param this} is less than or equal to {@param other}.
   */
  public boolean lessThanOrEqual(TypeLatticeElement other, DexDefinitionSupplier definitions) {
    return equals(other) || strictlyLessThan(other, definitions);
  }

  /**
   * Determines if this type is based on a missing class, directly or indirectly.
   *
   * @return {@code} true if this type is based on a missing class.
   */
  public boolean isBasedOnMissingClass(DexDefinitionSupplier definitions) {
    return false;
  }

  /**
   * Represents a type that can be everything.
   *
   * @return {@code true} if the corresponding {@link Value} could be any kinds.
   */
  public boolean isTop() {
    return false;
  }

  /**
   * Represents an empty type.
   *
   * @return {@code true} if the type of corresponding {@link Value} is not determined yet.
   */
  public boolean isBottom() {
    return false;
  }

  public boolean isReference() {
    return false;
  }

  public ReferenceTypeLatticeElement asReferenceTypeLatticeElement() {
    return null;
  }

  public boolean isArrayType() {
    return false;
  }

  public ArrayTypeLatticeElement asArrayTypeLatticeElement() {
    return null;
  }

  public boolean isClassType() {
    return false;
  }

  public ClassTypeLatticeElement asClassTypeLatticeElement() {
    return null;
  }

  public boolean isPrimitive() {
    return false;
  }

  public PrimitiveTypeLatticeElement asPrimitiveTypeLatticeElement() {
    return null;
  }

  public boolean isSingle() {
    return false;
  }

  public boolean isWide() {
    return false;
  }

  boolean isBoolean() {
    return false;
  }

  boolean isByte() {
    return false;
  }

  boolean isShort() {
    return false;
  }

  boolean isChar() {
    return false;
  }

  public boolean isInt() {
    return false;
  }

  public boolean isFloat() {
    return false;
  }

  public boolean isLong() {
    return false;
  }

  public boolean isDouble() {
    return false;
  }

  public boolean isPreciseType() {
    return isArrayType()
        || isClassType()
        || isNullType()
        || isInt()
        || isFloat()
        || isLong()
        || isDouble()
        || isBottom();
  }

  public boolean isFineGrainedType() {
    return isBoolean()
        || isByte()
        || isShort()
        || isChar();
  }

  /**
   * Determines if this type only includes null values that are defined by a const-number
   * instruction in the same enclosing method.
   *
   * These null values can be assigned to any type.
   */
  public boolean isNullType() {
    return false;
  }

  /**
   * Determines if this type only includes null values.
   *
   * These null values cannot be assigned to any type. For example, it is a type error to "throw v"
   * where the value `v` satisfies isDefinitelyNull(), because the static type of `v` may not be a
   * subtype of Throwable.
   */
  public boolean isDefinitelyNull() {
    return nullability().isDefinitelyNull();
  }

  public int requiredRegisters() {
    assert !isBottom() && !isTop();
    return isWide() ? 2 : 1;
  }

  public static ClassTypeLatticeElement objectClassType(
      DexDefinitionSupplier definitions, Nullability nullability) {
    return fromDexType(definitions.dexItemFactory().objectType, nullability, definitions)
        .asClassTypeLatticeElement();
  }

  static ArrayTypeLatticeElement objectArrayType(
      DexDefinitionSupplier definitions, Nullability nullability) {
    DexItemFactory dexItemFactory = definitions.dexItemFactory();
    return fromDexType(
            dexItemFactory.createArrayType(1, dexItemFactory.objectType), nullability, definitions)
        .asArrayTypeLatticeElement();
  }

  public static ClassTypeLatticeElement classClassType(
      DexDefinitionSupplier definitions, Nullability nullability) {
    return fromDexType(definitions.dexItemFactory().classType, nullability, definitions)
        .asClassTypeLatticeElement();
  }

  public static ClassTypeLatticeElement stringClassType(
      DexDefinitionSupplier definitions, Nullability nullability) {
    return fromDexType(definitions.dexItemFactory().stringType, nullability, definitions)
        .asClassTypeLatticeElement();
  }

  public static TypeLatticeElement fromDexType(
      DexType type, Nullability nullability, DexDefinitionSupplier definitions) {
    return fromDexType(type, nullability, definitions, false);
  }

  public static TypeLatticeElement fromDexType(
      DexType type,
      Nullability nullability,
      DexDefinitionSupplier definitions,
      boolean asArrayElementType) {
    if (type == DexItemFactory.nullValueType) {
      assert !nullability.isDefinitelyNotNull();
      return NULL;
    }
    if (type.isPrimitiveType()) {
      return PrimitiveTypeLatticeElement.fromDexType(type, asArrayElementType);
    }
    return definitions
        .dexItemFactory()
        .createReferenceTypeLatticeElement(type, nullability, definitions);
  }

  public boolean isValueTypeCompatible(TypeLatticeElement other) {
    return (isReference() && other.isReference())
        || (isSingle() && other.isSingle())
        || (isWide() && other.isWide());
  }

  public TypeLatticeElement checkCast(DexDefinitionSupplier definitions, DexType castType) {
    TypeLatticeElement castTypeLattice = fromDexType(castType, nullability(), definitions);
    if (lessThanOrEqual(castTypeLattice, definitions)) {
      return this;
    }
    return castTypeLattice;
  }

  @Override
  public abstract String toString();

  @Override
  public abstract boolean equals(Object o);

  @Override
  public abstract int hashCode();
}
