// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.naming;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.CachedHashValueDexItem;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexReference;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.naming.NamingState.InternalState;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.shaking.ProguardConfiguration;
import com.android.tools.r8.shaking.RootSetBuilder.RootSet;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

abstract class MemberNameMinifier<MemberType, StateType extends CachedHashValueDexItem> {

  protected final AppView<AppInfoWithLiveness> appView;
  protected final RootSet rootSet;
  protected final List<String> dictionary;

  protected final Map<MemberType, DexString> renaming = new IdentityHashMap<>();
  protected final NamingState<StateType, ?> globalState;
  protected final boolean useUniqueMemberNames;
  protected final boolean overloadAggressively;
  protected final boolean useApplyMapping;

  protected final State minifierState = new State();

  // The use of a bidirectional map allows us to map a naming state to the type it represents,
  // which is useful for debugging.
  private final BiMap<DexType, NamingState<StateType, ?>> states = HashBiMap.create();

  MemberNameMinifier(
      AppView<AppInfoWithLiveness> appView, RootSet rootSet, MemberNamingStrategy strategy) {
    ProguardConfiguration proguardConfiguration = appView.options().getProguardConfiguration();
    this.appView = appView;
    this.rootSet = rootSet;
    this.dictionary = proguardConfiguration.getObfuscationDictionary();
    this.useUniqueMemberNames = proguardConfiguration.isUseUniqueClassMemberNames();
    this.overloadAggressively =
        proguardConfiguration.isOverloadAggressivelyWithoutUseUniqueClassMemberNames();
    this.globalState =
        NamingState.createRoot(
            appView.dexItemFactory(),
            dictionary,
            getKeyTransform(),
            strategy,
            useUniqueMemberNames);
    this.useApplyMapping = proguardConfiguration.hasApplyMappingFile();
  }

  abstract Function<StateType, ?> getKeyTransform();

  protected NamingState<StateType, ?> computeStateIfAbsent(
      DexType type, Function<DexType, NamingState<StateType, ?>> f) {
    return useUniqueMemberNames ? globalState : states.computeIfAbsent(type, f);
  }

  protected boolean alwaysReserveMemberNames(DexClass holder) {
    return !useApplyMapping && holder.isNotProgramClass();
  }

  // A class that provides access to the minification state. An instance of this class is passed
  // from the method name minifier to the interface method name minifier.
  class State {

    DexString getRenaming(MemberType key) {
      return renaming.get(key);
    }

    void putRenaming(MemberType key, DexString value) {
      renaming.put(key, value);
    }

    NamingState<StateType, ?> getState(DexType type) {
      return useUniqueMemberNames ? globalState : states.get(type);
    }

    DexType getStateKey(NamingState<StateType, ?> state) {
      return states.inverse().get(state);
    }

    NamingState<StateType, ?> globalState() {
      return globalState;
    }

    boolean isReservedInGlobalState(DexString name, StateType state) {
      return globalState.isReserved(name, state);
    }

    boolean useUniqueMemberNames() {
      return useUniqueMemberNames;
    }
  }

  interface MemberNamingStrategy {
    DexString next(DexReference source, InternalState internalState);

    boolean bypassDictionary();

    boolean breakOnNotAvailable(DexReference source, DexString name);
  }
}
