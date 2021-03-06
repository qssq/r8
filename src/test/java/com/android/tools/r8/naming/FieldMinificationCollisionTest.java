// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.naming;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.android.tools.r8.NeverClassInline;
import com.android.tools.r8.NeverInline;
import com.android.tools.r8.NeverMerge;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.android.tools.r8.utils.codeinspector.FieldSubject;
import org.junit.Test;

/** Regression test for b/127932803. */
public class FieldMinificationCollisionTest extends TestBase {

  @Test
  public void test() throws Exception {
    String expectedOutput = StringUtils.lines("ABC");
    CodeInspector inspector =
        testForR8(Backend.DEX)
            .addInnerClasses(FieldMinificationCollisionTest.class)
            .addKeepMainRule(TestClass.class)
            .addKeepRules(
                "-keep class " + B.class.getTypeName() + " { public java.lang.String f2; }")
            .enableClassInliningAnnotations()
            .enableInliningAnnotations()
            .enableMergeAnnotations()
            .run(TestClass.class)
            .assertSuccessWithOutput(expectedOutput)
            .inspector();

    FieldSubject f1Subject = inspector.clazz(A.class).uniqueFieldWithName("f1");
    assertThat(f1Subject, isPresent());

    FieldSubject f3Subject = inspector.clazz(C.class).uniqueFieldWithName("f3");
    assertThat(f3Subject, isPresent());

    // TODO(b/127932803): f1 and f3 should not be given the same name.
    assertEquals(f1Subject.getFinalName(), f3Subject.getFinalName());
  }

  static class TestClass {

    public static void main(String[] args) {
      new C("A", "B", "C").print();
    }
  }

  @NeverMerge
  static class A {

    public String f1;

    public A(String f1) {
      this.f1 = f1;
    }
  }

  @NeverMerge
  static class B extends A {

    public String f2;

    public B(String f1, String f2) {
      super(f1);
      this.f2 = f2;
    }
  }

  @NeverClassInline
  static class C extends B {

    public String f3;

    public C(String f1, String f2, String f3) {
      super(f1, f2);
      this.f3 = f3;
    }

    @NeverInline
    public void print() {
      System.out.println(f1 + f2 + f3);
    }
  }
}
