package com.gzoltar.core.instr.pass;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import com.gzoltar.core.AgentConfigs;
import com.gzoltar.core.instr.InstrumentationConstants;
import com.gzoltar.core.instr.Outcome;
import com.gzoltar.core.instr.actions.BlackList;
import com.gzoltar.core.instr.filter.DuplicateCollectorReferenceFilter;
import com.gzoltar.core.instr.filter.EmptyMethodFilter;
import com.gzoltar.core.instr.filter.EnumFilter;
import com.gzoltar.core.instr.filter.Filter;
import com.gzoltar.core.instr.filter.IFilter;
import com.gzoltar.core.instr.filter.SyntheticFilter;
import com.gzoltar.core.instr.granularity.GranularityFactory;
import com.gzoltar.core.instr.granularity.GranularityLevel;
import com.gzoltar.core.instr.granularity.IGranularity;
import com.gzoltar.core.instr.matchers.MethodAnnotationMatcher;
import com.gzoltar.core.instr.matchers.MethodModifierMatcher;
import com.gzoltar.core.instr.matchers.MethodNameMatcher;
import com.gzoltar.core.model.Node;
import com.gzoltar.core.model.NodeFactory;
import com.gzoltar.core.runtime.Collector;
import com.gzoltar.core.runtime.Probe;
import javassist.CtBehavior;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.Modifier;
import javassist.bytecode.Bytecode;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.ConstPool;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.Opcode;

public class InstrumentationPass implements IPass {

  private final FieldInstrumentationPass fieldPass = new FieldInstrumentationPass();

  private final AbstractInitMethodInstrumentationPass initMethodPass;

  private final StackSizePass stackSizePass = new StackSizePass();

  private final DuplicateCollectorReferenceFilter duplicateCollectorFilter =
      new DuplicateCollectorReferenceFilter();

  private final List<IFilter> filters = new ArrayList<IFilter>();

  private final GranularityLevel granularity;

  private final Set<Integer> uniqueLineNumbers = new LinkedHashSet<Integer>();

  public InstrumentationPass(final AgentConfigs agentConfigs) {
    this.granularity = agentConfigs.getGranularity();

    if (agentConfigs.getOfflineInstrumentation()) {
      this.initMethodPass = new OfflineInitMethodInstrumentationPass();
    } else {
      this.initMethodPass = new InitMethodInstrumentationPass();
    }

    // filter classes/methods according to users preferences

    if (!agentConfigs.getInclPublicMethods()) {
      this.filters.add(new Filter(new BlackList(new MethodModifierMatcher(Modifier.PUBLIC))));
    }

    if (!agentConfigs.getInclStaticConstructors()) {
      this.filters.add(new Filter(new BlackList(new MethodNameMatcher("<clinit>*"))));
    }

    if (!agentConfigs.getInclDeprecatedMethods()) {
      this.filters.add(new Filter(new BlackList(new MethodAnnotationMatcher(Deprecated.class.getCanonicalName()))));
    }

    // exclude synthetic methods
    this.filters.add(new SyntheticFilter());

    // exclude methods 'values' and 'valuesOf' of enum classes
    this.filters.add(new EnumFilter());

    // exclude methods without any source code
    this.filters.add(new EmptyMethodFilter());
  }

  @Override
  public Outcome transform(final CtClass ctClass) throws Exception {
    boolean instrumented = false;

    // as the constructor of this class is only called once, the set of unique line numbers is only
    // initialised once. and as the instrumentation of previous classes could have populated this
    // set of unique line numbers, we must restart it
    this.uniqueLineNumbers.clear();

    byte[] originalBytes = ctClass.toBytecode(); // toBytecode() method frozens the class
    // in order to be able to modify it, it has to be defrosted
    ctClass.defrost();

    for (CtBehavior ctBehavior : ctClass.getDeclaredBehaviors()) {
      boolean behaviorInstrumented =
          this.transform(ctClass, ctBehavior).equals(Outcome.REJECT) ? false : true;
      instrumented = instrumented || behaviorInstrumented;

      if (behaviorInstrumented) {
        // update stack size
        this.stackSizePass.transform(ctClass, ctBehavior);
      }
    }

    if (instrumented) {
      // make GZoltar's field
      this.fieldPass.transform(ctClass);

      // make method to init GZoltar's field
      this.initMethodPass.setHash(originalBytes);
      this.initMethodPass.transform(ctClass);

      // make sure GZoltar's field is initialised. note: the following code requires the init method
      // to be in the instrumented class, otherwise a compilation error is thrown

      boolean hasAnyStaticInitializerBeenInstrumented = false;
      for (CtBehavior ctBehavior : ctClass.getDeclaredBehaviors()) {
        if (ctBehavior.getName().equals(InstrumentationConstants.INIT_METHOD_NAME)) {
          // for obvious reasons, init method cannot call itself
          continue;
        }

        // before executing the code of every single method, check whether FIELD_NAME has been
        // initialised. if not, init method should initialise the field
        this.initMethodPass.transform(ctClass, ctBehavior);

        if (hasAnyStaticInitializerBeenInstrumented == false
            && ctBehavior.getMethodInfo2().isStaticInitializer()) {
          hasAnyStaticInitializerBeenInstrumented = true;
        }
      }

      if (!hasAnyStaticInitializerBeenInstrumented) {
        CtConstructor clinit = ctClass.makeClassInitializer();
        this.initMethodPass.transform(ctClass, clinit);
      }
    }

    return Outcome.ACCEPT;
  }

  @Override
  public Outcome transform(final CtClass ctClass, final CtBehavior ctBehavior) throws Exception {
    Outcome instrumented = Outcome.REJECT;

    // check whether this method should be instrumented
    for (IFilter filter : this.filters) {
      switch (filter.filter(ctBehavior)) {
        case REJECT:
          return instrumented;
        case ACCEPT:
        default:
          continue;
      }
    }

    MethodInfo methodInfo = ctBehavior.getMethodInfo();
    CodeAttribute ca = methodInfo.getCodeAttribute();

    if (ca == null) {
      // do not instrument methods with no code
      return instrumented;
    }

    CodeIterator ci = ca.iterator();
    IGranularity granularity = GranularityFactory.getGranularity(ctClass, methodInfo, this.granularity);

    for (int instrSize = 0, index, curLine; ci.hasNext(); this.uniqueLineNumbers.add(curLine)) {
      index = ci.next();

      curLine = methodInfo.getLineNumber(index);

      if (curLine == -1 || this.uniqueLineNumbers.contains(curLine)) {
        continue;
      } else if (methodInfo.isConstructor() && curLine == 1) {
        continue;
      }

      if (granularity.instrumentAtIndex(index, instrSize)) {
        Node node = NodeFactory.createNode(ctClass, ctBehavior, curLine);
        assert node != null;
        Probe probe = this.getProbe(ctClass, node);
        assert probe != null;

        if (this.duplicateCollectorFilter.filter(ctClass) == Outcome.ACCEPT) {
          Bytecode bc = this.getInstrumentationCode(ctClass, probe, methodInfo.getConstPool());
          ci.insert(index, bc.get());
          instrSize += bc.length();

          instrumented = Outcome.ACCEPT;
        } else {
          instrumented = Outcome.REJECT;
        }
      }

      if (granularity.stopInstrumenting()) {
        break;
      }
    }

    return instrumented;
  }

  private Bytecode getInstrumentationCode(CtClass ctClass, Probe probe, ConstPool constPool) {
    Bytecode b = new Bytecode(constPool);
    b.addGetstatic(ctClass, InstrumentationConstants.FIELD_NAME,
        InstrumentationConstants.FIELD_DESC_BYTECODE);
    b.addIconst(probe.getArrayIndex());
    b.addOpcode(Opcode.ICONST_1);
    b.addOpcode(Opcode.BASTORE);

    return b;
  }

  public Probe getProbe(CtClass ctClass, Node node) {
    Collector c = Collector.instance();
    return c.regiterProbe(ctClass.getName(), node);
  }

}
