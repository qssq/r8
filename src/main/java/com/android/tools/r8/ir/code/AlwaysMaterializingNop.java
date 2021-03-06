// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.cf.LoadStoreHelper;
import com.android.tools.r8.cf.code.CfNop;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.conversion.CfBuilder;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.ConstraintWithTarget;
import com.android.tools.r8.ir.optimize.InliningConstraints;

public class AlwaysMaterializingNop extends Instruction {

  public AlwaysMaterializingNop() {
    super(null);
  }

  @Override
  public boolean canBeDeadCode(AppView<? extends AppInfo> appView, IRCode code) {
    return false;
  }

  @Override
  public void buildDex(DexBuilder builder) {
    builder.addNop(this);
  }

  @Override
  public void buildCf(CfBuilder builder) {
    builder.add(new CfNop());
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other instanceof AlwaysMaterializingNop;
  }

  @Override
  public int maxInValueRegister() {
    throw new Unreachable();
  }

  @Override
  public int maxOutValueRegister() {
    throw new Unreachable();
  }

  @Override
  public ConstraintWithTarget inliningConstraint(
      InliningConstraints inliningConstraints, DexType invocationContext) {
    return ConstraintWithTarget.ALWAYS;
  }

  @Override
  public void insertLoadAndStores(InstructionListIterator it, LoadStoreHelper helper) {
    // Nothing to do.
  }

  @Override
  public boolean hasInvariantOutType() {
    return true;
  }
}
