// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.optimize;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.ir.code.BasicBlock;
import com.android.tools.r8.ir.code.DominatorTree;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.If;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionIterator;
import com.android.tools.r8.ir.code.InstructionListIterator;
import com.android.tools.r8.ir.code.NonNull;
import com.android.tools.r8.ir.code.Phi;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.code.ValueType;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public class NonNullTracker {

  public NonNullTracker() {
  }

  @VisibleForTesting
  static boolean throwsOnNullInput(Instruction instruction) {
    return (instruction.isInvokeMethodWithReceiver() && !instruction.isInvokeDirect())
        || instruction.isInstanceGet()
        || instruction.isInstancePut()
        || instruction.isArrayGet()
        || instruction.isArrayPut()
        || instruction.isArrayLength()
        || instruction.isMonitor();
  }

  private Value getNonNullInput(Instruction instruction) {
    if (instruction.isInvokeMethodWithReceiver()) {
      return instruction.asInvokeMethodWithReceiver().getReceiver();
    } else if (instruction.isInstanceGet()) {
      return instruction.asInstanceGet().object();
    } else if (instruction.isInstancePut()) {
      return instruction.asInstancePut().object();
    } else if (instruction.isArrayGet()) {
      return instruction.asArrayGet().array();
    } else if (instruction.isArrayPut()) {
      return instruction.asArrayPut().array();
    } else if (instruction.isArrayLength()) {
      return instruction.asArrayLength().array();
    } else if (instruction.isMonitor()) {
      return instruction.asMonitor().object();
    }
    throw new Unreachable("Should conform to throwsOnNullInput.");
  }

  public void addNonNull(IRCode code) {
    addNonNullInPart(code, code.blocks.listIterator(), b -> true);
  }

  public void addNonNullInPart(
      IRCode code, ListIterator<BasicBlock> blockIterator, Predicate<BasicBlock> blockTester) {
    while (blockIterator.hasNext()) {
      BasicBlock block = blockIterator.next();
      if (!blockTester.test(block)) {
        continue;
      }
      // Add non-null after instructions that implicitly indicate receiver/array is not null.
      InstructionListIterator iterator = block.listIterator();
      while (iterator.hasNext()) {
        Instruction current = iterator.next();
        if (!throwsOnNullInput(current)) {
          continue;
        }
        Value knownToBeNonNullValue = getNonNullInput(current);
        // Avoid adding redundant non-null instruction.
        if (knownToBeNonNullValue.isNeverNull()) {
          // Otherwise, we will have something like:
          // non_null_rcv <- non-null(rcv)
          // ...
          // another_rcv <- non-null(non_null_rcv)
          continue;
        }
        // First, if the current block has catch handler, split into two blocks, e.g.,
        //
        // ...x
        // invoke(rcv, ...)
        // ...y
        //
        //   ~>
        //
        // ...x
        // invoke(rcv, ...)
        // goto A
        //
        // A: ...y // blockWithNonNullInstruction
        //
        BasicBlock blockWithNonNullInstruction =
            block.hasCatchHandlers() ? iterator.split(code, blockIterator) : block;
        // Next, add non-null fake IR, e.g.,
        // ...x
        // invoke(rcv, ...)
        // goto A
        // ...
        // A: non_null_rcv <- non-null(rcv)
        // ...y
        Value nonNullValue =
            code.createValue(ValueType.OBJECT, knownToBeNonNullValue.getLocalInfo());
        NonNull nonNull = new NonNull(nonNullValue, knownToBeNonNullValue, current);
        nonNull.setPosition(current.getPosition());
        if (blockWithNonNullInstruction !=  block) {
          // If we split, add non-null IR on top of the new split block.
          blockWithNonNullInstruction.listIterator().add(nonNull);
        } else {
          // Otherwise, just add it to the current block at the position of the iterator.
          iterator.add(nonNull);
        }
        // Then, replace all users of the original value that are dominated by either the current
        // block or the new split-off block. Since NPE can be explicitly caught, nullness should be
        // propagated through dominance.
        Set<Instruction> users = knownToBeNonNullValue.uniqueUsers();
        Set<Instruction> dominatedUsers = Sets.newIdentityHashSet();
        Map<Phi, IntList> dominatedPhiUsersWithPositions = new IdentityHashMap<>();
        DominatorTree dominatorTree = new DominatorTree(code);
        Set<BasicBlock> dominatedBlocks = Sets.newIdentityHashSet();
        for (BasicBlock dominatee : dominatorTree.dominatedBlocks(blockWithNonNullInstruction)) {
          dominatedBlocks.add(dominatee);
          InstructionListIterator dominateeIterator = dominatee.listIterator();
          if (dominatee == blockWithNonNullInstruction) {
            // In the block with the inserted non null instruction, skip instructions up to and
            // including the newly inserted instruction.
            dominateeIterator.nextUntil(instruction -> instruction == nonNull);
          }
          while (dominateeIterator.hasNext()) {
            Instruction potentialUser = dominateeIterator.next();
            assert potentialUser != nonNull;
            if (users.contains(potentialUser)) {
              dominatedUsers.add(potentialUser);
            }
          }
        }
        for (Phi user : knownToBeNonNullValue.uniquePhiUsers()) {
          IntList dominatedPredecessorIndexes =
              findDominatedPredecessorIndexesInPhi(user, knownToBeNonNullValue, dominatedBlocks);
          if (!dominatedPredecessorIndexes.isEmpty()) {
            dominatedPhiUsersWithPositions.put(user, dominatedPredecessorIndexes);
          }
        }
        knownToBeNonNullValue.replaceSelectiveUsers(
            nonNullValue, dominatedUsers, dominatedPhiUsersWithPositions);
      }

      // Add non-null on top of the successor block if the current block ends with a null check.
      if (block.exit().isIf() && block.exit().asIf().isZeroTest()) {
        // if v EQ blockX
        // ... (fallthrough)
        // blockX: ...
        //
        //   ~>
        //
        // if v EQ blockX
        // non_null_value <- non-null(v)
        // ...
        // blockX: ...
        //
        // or
        //
        // if v NE blockY
        // ...
        // blockY: ...
        //
        //   ~>
        //
        // blockY: non_null_value <- non-null(v)
        // ...
        If theIf = block.exit().asIf();
        Value knownToBeNonNullValue = theIf.inValues().get(0);
        // Avoid adding redundant non-null instruction.
        if (!knownToBeNonNullValue.isNeverNull()) {
          BasicBlock target = theIf.targetFromCondition(1L);
          // Ignore uncommon empty blocks.
          if (!target.isEmpty()) {
            DominatorTree dominatorTree = new DominatorTree(code);
            // Make sure there are no paths to the target block without passing the current block.
            if (dominatorTree.dominatedBy(target, block)) {
              // Collect users of the original value that are dominated by the target block.
              Set<Instruction> dominatedUsers = Sets.newIdentityHashSet();
              Map<Phi, IntList> dominatedPhiUsersWithPositions = new IdentityHashMap<>();
              Set<BasicBlock> dominatedBlocks =
                  Sets.newHashSet(dominatorTree.dominatedBlocks(target));
              for (Instruction user : knownToBeNonNullValue.uniqueUsers()) {
                if (dominatedBlocks.contains(user.getBlock())) {
                  dominatedUsers.add(user);
                }
              }
              for (Phi user : knownToBeNonNullValue.uniquePhiUsers()) {
                IntList dominatedPredecessorIndexes = findDominatedPredecessorIndexesInPhi(
                    user, knownToBeNonNullValue, dominatedBlocks);
                if (!dominatedPredecessorIndexes.isEmpty()) {
                  dominatedPhiUsersWithPositions.put(user, dominatedPredecessorIndexes);
                }
              }
              // Avoid adding a non-null for the value without meaningful users.
              if (!dominatedUsers.isEmpty() || !dominatedPhiUsersWithPositions.isEmpty()) {
                Value nonNullValue = code.createValue(
                    knownToBeNonNullValue.outType(), knownToBeNonNullValue.getLocalInfo());
                NonNull nonNull = new NonNull(nonNullValue, knownToBeNonNullValue, theIf);
                InstructionListIterator targetIterator = target.listIterator();
                nonNull.setPosition(targetIterator.next().getPosition());
                targetIterator.previous();
                targetIterator.add(nonNull);
                knownToBeNonNullValue.replaceSelectiveUsers(
                    nonNullValue, dominatedUsers, dominatedPhiUsersWithPositions);
              }
            }
          }
        }
      }
    }
  }

  private IntList findDominatedPredecessorIndexesInPhi(
      Phi user, Value knownToBeNonNullValue, Set<BasicBlock> dominatedBlocks) {
    assert user.getOperands().contains(knownToBeNonNullValue);
    List<Value> operands = user.getOperands();
    List<BasicBlock> predecessors = user.getBlock().getPredecessors();
    assert operands.size() == predecessors.size();

    IntList predecessorIndexes = new IntArrayList();
    int index = 0;
    Iterator<Value> operandIterator = operands.iterator();
    Iterator<BasicBlock> predecessorIterator = predecessors.iterator();
    while (operandIterator.hasNext() && predecessorIterator.hasNext()) {
      Value operand = operandIterator.next();
      BasicBlock predecessor = predecessorIterator.next();
      // When this phi is chosen to be known-to-be-non-null value,
      // check if the corresponding predecessor is dominated by the block where non-null is added.
      if (operand == knownToBeNonNullValue && dominatedBlocks.contains(predecessor)) {
        predecessorIndexes.add(index);
      }

      index++;
    }
    return predecessorIndexes;
  }

  public void cleanupNonNull(IRCode code) {
    InstructionIterator it = code.instructionIterator();
    boolean needToCheckTrivialPhis = false;
    while (it.hasNext()) {
      Instruction instruction = it.next();
      // non_null_rcv <- non-null(rcv)  // deleted
      // ...
      // non_null_rcv#foo
      //
      //  ~>
      //
      // rcv#foo
      if (instruction.isNonNull()) {
        NonNull nonNull = instruction.asNonNull();
        Value src = nonNull.src();
        Value dest = nonNull.dest();
        needToCheckTrivialPhis = needToCheckTrivialPhis || dest.uniquePhiUsers().size() != 0;
        dest.replaceUsers(src);
        it.remove();
      }
    }
    // non-null might introduce a phi, e.g.,
    // non_null_rcv <- non-null(rcv)
    // ...
    // v <- phi(rcv, non_null_rcv)
    //
    // Cleaning up that non-null may result in a trivial phi:
    // v <- phi(rcv, rcv)
    if (needToCheckTrivialPhis) {
      code.removeAllTrivialPhis();
    }
  }

}