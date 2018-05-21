package com.gzoltar.core.instr.pass;

import com.gzoltar.core.instr.Outcome;
import javassist.CtBehavior;
import javassist.CtClass;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.MethodInfo;

public class StackSizePass implements IPass {

  @Override
  public Outcome transform(final CtClass ctClass) throws Exception {
    for (CtBehavior ctBehavior : ctClass.getDeclaredBehaviors()) {
      if (this.transform(ctClass, ctBehavior) == Outcome.REJECT) {
        return Outcome.REJECT;
      }
    }
    return Outcome.ACCEPT;
  }

  @Override
  public Outcome transform(final CtClass ctClass, final CtBehavior ctBehavior) throws Exception {
    MethodInfo info = ctBehavior.getMethodInfo();
    CodeAttribute ca = info.getCodeAttribute();

    if (ca != null) {
      int ss = ca.computeMaxStack();
      ca.setMaxStack(ss);
      //info.rebuildStackMapIf6(ctClass.getClassPool(), ctClass.getClassFile());
    }

    return Outcome.ACCEPT;
  }

}
