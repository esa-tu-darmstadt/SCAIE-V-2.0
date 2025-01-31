package scaiev.scal.strategy.standard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.frontend.SCAIEVNode.NodeTypeTag;
import scaiev.pipeline.PipelineStage;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Key;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.strategy.SingleNodeStrategy;
import scaiev.util.Verilog;

/** Strategy that MUXes a node based on the active ISAXes. */
public class ValidMuxStrategy extends SingleNodeStrategy {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  Verilog language;
  BNode bNodes;
  Core core;
  HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr;
  HashMap<String, SCAIEVInstr> allISAXes;
  /**
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param allISAXes The ISAX descriptions
   */
  public ValidMuxStrategy(Verilog language, BNode bNodes, Core core,
                          HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                          HashMap<String, SCAIEVInstr> allISAXes) {
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
    this.op_stage_instr = op_stage_instr;
    this.allISAXes = allISAXes;
  }
  private HashMap<NodeInstanceDesc.Key, RequestedForSet> spawnValidRespRequestedFor_byKey = new HashMap<>();
  NodeLogicBlock CreateValidEncodingIValid(NodeRegistryRO registry, NodeInstanceDesc.Key nodeKey, HashSet<String> lookAtISAX,
                                           SCAIEVNode operation, SCAIEVNode checkAdj, String defaultValue, NodeLogicBlock ret,
                                           RequestedForSet requestedFor) {
    PipelineStage stage = nodeKey.getStage();
    String tab = language.tab;

    SCAIEVNode assignNode = operation;
    if (!checkAdj.getAdj().equals(AdjacentNode.none))
      assignNode = checkAdj;
    String assignNodeName = language.CreateLocalNodeName(assignNode.NodeNegInput(), stage, "");
    SCAIEVNode correctcheckAdj;
    if (checkAdj.getAdj().validMarkerFor != null &&
        checkAdj.getAdj().validMarkerFor !=
            AdjacentNode.none) // for exp in case of wrmem_addr_valid, we need to check if ISAX contains addr Adj
      correctcheckAdj = bNodes.GetAdjSCAIEVNode(bNodes.GetNonAdjNode(checkAdj), checkAdj.getAdj().validMarkerFor).orElseThrow();
    else
      correctcheckAdj = checkAdj;

    class PriorityEntry {
      String isax = "";
      int aux = 0;
      boolean spawn = false;
      public PriorityEntry forISAX(String isax) {
        this.isax = isax;
        return this;
      }
      public PriorityEntry forAux(int aux) {
        this.aux = aux;
        return this;
      }
      public PriorityEntry forSpawn() {
        this.spawn = true;
        return this;
      }
    }

    // Order ISAXes so that the ones without opcode have priority
    List<PriorityEntry> lookAtISAXOrdered = language.OrderISAXOpCode(lookAtISAX, allISAXes)
                                                .entrySet()
                                                .stream()
                                                .map(orderEntry -> new PriorityEntry().forISAX(orderEntry.getValue()))
                                                .collect(Collectors.toCollection(ArrayList::new));
    // Check for semi-coupled spawn
    Optional<SCAIEVNode> spawnNode_opt =
        operation.tags.contains(NodeTypeTag.isPortNode) ? Optional.empty() : bNodes.GetMySpawnNode(operation);
    if (spawnNode_opt.isPresent() && op_stage_instr.containsKey(spawnNode_opt.get()) &&
        op_stage_instr.get(spawnNode_opt.get()).containsKey(stage)) {
      spawnNode_opt = spawnNode_opt.flatMap(
          spawnNode -> checkAdj.isAdj() ? bNodes.GetAdjSCAIEVNode(spawnNode, checkAdj.getAdj()) : Optional.of(spawnNode));
      if (spawnNode_opt.isPresent()) {
        // Semi-coupled spawn needs to be checked first for correct instruction ordering,
        //  since tightly-coupled (e.g. single-cycle) instructions in <stage> (currently) prevent new spawn instructions from entering
        //  <stage> until they leave <stage>.
        // lookAtISAXOrdered.put(lookAtISAXOrdered.isEmpty() ? 0 : (lookAtISAXOrdered.lastKey() + 1), "--spawn--");
        lookAtISAXOrdered.add(new PriorityEntry().forSpawn());
      }
    }
    for (NodeInstanceDesc lookedUp : registry.lookupAll(nodeKey, false)) {
      if (lookedUp.getKey().getISAX().isEmpty() && lookedUp.getKey().getAux() != 0) {
        lookAtISAXOrdered.add(new PriorityEntry().forAux(lookedUp.getKey().getAux())); // Add internally requested nodes.
      }
    }
    if (lookAtISAXOrdered.isEmpty())
      return new NodeLogicBlock();

    boolean isValidAdj = (checkAdj.getAdj() == AdjacentNode.validReq || checkAdj.getAdj() == AdjacentNode.addrReq);
    boolean defaultGeneratedBySCAL = (checkAdj.getAdj() == AdjacentNode.cancelReq);
    boolean needsDefault = false;

    class ConditionalAssignEntry {
      public ConditionalAssignEntry(boolean isExclusive, String cond, String expr) {
        this.isExclusive = isExclusive;
        this.cond = cond;
        this.expr = expr;
      }
      public boolean isExclusive;
      public String cond;
      public String expr;
    };
    List<ConditionalAssignEntry> conditionalAssigns = new ArrayList<>();

    //		for (Integer priority  :  lookAtISAXOrdered.descendingKeySet()) {
    //			String ISAX = lookAtISAXOrdered.get(priority);
    for (int priority = lookAtISAXOrdered.size() - 1; priority >= 0; --priority) {
      PriorityEntry orderEntry = lookAtISAXOrdered.get(priority);
      // boolean isSpawn = ISAX.equals("--spawn--");
      if (!orderEntry.isax.isEmpty())
        requestedFor.addRelevantISAX(orderEntry.isax);
      String RdIValid;

      if (orderEntry.spawn) {
        SCAIEVNode spawnOperation = spawnNode_opt.get();
        SCAIEVNode baseOperation = (spawnOperation.isAdj() ? bNodes.GetSCAIEVNode(spawnOperation.nameParentNode) : spawnOperation);
        SCAIEVNode userValid =
            (spawnOperation.validBy == AdjacentNode.none) ? null : bNodes.GetAdjSCAIEVNode(baseOperation, spawnOperation.validBy).get();
        if (isValidAdj || checkAdj.getAdj() == AdjacentNode.cancelReq)
          userValid = spawnOperation;
        if (userValid != null) {
          var userValidNodeInst = registry.lookupRequired(new NodeInstanceDesc.Key(userValid, stage, ""));
          requestedFor.addAll(userValidNodeInst.getRequestedFor(), true);
          RdIValid = userValidNodeInst.getExpression();
        } else {
          var spawnKey = new NodeInstanceDesc.Key(spawnOperation, stage, "");
          logger.error("ValidMuxStrategy: Cannot build a mux condition for " + spawnKey.toString(false) + " (semi-coupled spawn)");
          RdIValid = "MISSING_" + spawnOperation.name + "_valid_" + stage.getName();
        }
        String assignSignal;
        if (isValidAdj || checkAdj.getAdj() == AdjacentNode.addrReq)
          assignSignal = "1";
        else {
          var assignNodeInst = registry.lookupRequired(new NodeInstanceDesc.Key(spawnOperation, stage, ""));
          requestedFor.addAll(assignNodeInst.getRequestedFor(), true);
          assignSignal = assignNodeInst.getExpression();
        }
        conditionalAssigns.add(new ConditionalAssignEntry(true, RdIValid, assignSignal));
        // body += tab.repeat(2) +RdIValid+" : "+assignNodeName +" = " + assignSignal+ ";\n";
        // assignLogic = "always_comb "+assignNodeName +" = " + assignSignal+ ";\n";
        if (checkAdj.getAdj() == AdjacentNode.validReq &&
            bNodes.GetAdjSCAIEVNode(spawnNode_opt.get(), AdjacentNode.validResp).isPresent()) {
          // If ValidMuxStrategy translates validReq from spawn into non-spawn, also translate validResp back into spawn.
          SCAIEVNode spawnRespNode = bNodes.GetAdjSCAIEVNode(spawnNode_opt.get(), AdjacentNode.validResp).orElseThrow();
          var nonspawnRespNode_opt = bNodes.GetAdjSCAIEVNode(operation, AdjacentNode.validResp);
          String validReqFromSpawnExpr = RdIValid + " && " + assignSignal;
          String respExpr = "";
          if (nonspawnRespNode_opt.isPresent()) {
            // Use validResp if possible.
            NodeInstanceDesc respDesc = registry.lookupRequired(new NodeInstanceDesc.Key(nonspawnRespNode_opt.get(), stage, ""));
            if (!respDesc.getExpression().startsWith("MISSING_"))
              respExpr = validReqFromSpawnExpr + " && " + respDesc.getExpression();
          }
          if (respExpr.isEmpty()) {
            String stallCond = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdStall, stage, ""));
            var wrStallInst_opt = registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.WrStall, stage, ""));
            if (wrStallInst_opt.isPresent())
              stallCond += " || " + wrStallInst_opt.get().getExpression();
            respExpr = validReqFromSpawnExpr + " && !(" + stallCond + ")";
          }
          var spawnValidRespKey = new NodeInstanceDesc.Key(spawnRespNode, stage, "");
          var spawnValidRespRequestedFor =
              spawnValidRespRequestedFor_byKey.computeIfAbsent(spawnValidRespKey, tmp_ -> new RequestedForSet());
          String spawnValidRespWire = spawnValidRespKey.toString(false) + "_s";
          ret.declarations += String.format("wire %s;\n", spawnValidRespWire);
          ret.logic += String.format("assign %s = %s;\n", spawnValidRespWire, respExpr);
          ret.outputs.add(new NodeInstanceDesc(spawnValidRespKey, spawnValidRespWire, ExpressionType.WireName, spawnValidRespRequestedFor));
        }
        continue;
      }
      assert (orderEntry.aux != 0 || !orderEntry.isax.isEmpty());

      // Create RdIValid = user valid for instr without encoding, else decode instr and create IValid
      if (orderEntry.aux != 0 || !orderEntry.isax.isEmpty() && allISAXes.get(orderEntry.isax).HasNoOp() ||
          core.TranslateStageScheduleNumber(core.GetNodes().get(bNodes.RdInstr).GetEarliest()).isAfter(stage, false)) {
        assert (!operation.isAdj());
        SCAIEVNode baseOperation = operation; //(operation.isAdj() ? bNodes.GetSCAIEVNode(operation.nameParentNode) : operation);
        SCAIEVNode userValid =
            (operation.validBy == AdjacentNode.none) ? null : bNodes.GetAdjSCAIEVNode(baseOperation, operation.validBy).get();
        if (checkAdj.getAdj() == AdjacentNode.cancelReq)
          userValid = checkAdj;
        if (operation.getAdj() == AdjacentNode.validReq)
          RdIValid = registry.lookupExpressionRequired(
              new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN, operation, stage, orderEntry.isax, orderEntry.aux));
        else if (!operation.isInput && !operation.DH) // if output (read node) and has no DH ==> no valid bit required, constant read
          RdIValid = "1'b0";
        else if (userValid != null)
          RdIValid = registry.lookupExpressionRequired(
              new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN, userValid, stage, orderEntry.isax, orderEntry.aux));
        else {
          logger.error("ValidMuxStrategy: Cannot build a mux condition for {} (ISAX {} aux {})", nodeKey.toString(false), orderEntry.isax,
                       orderEntry.aux);
          RdIValid = "MISSING_" + nodeKey.getNode().name + "_valid_" + stage.getName() + "_" + orderEntry.isax + "_" + orderEntry.aux;
        }
      } else {
        RdIValid = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, stage, orderEntry.isax));
      }

      boolean assignProvidedByISAX =
          orderEntry.aux == 0 && !orderEntry.isax.isEmpty() &&
          (allISAXes.get(orderEntry.isax).HasSchedWith(operation, snode -> snode.HasAdjSig(correctcheckAdj.getAdj())) ||
           allISAXes.get(orderEntry.isax).HasSchedWith(correctcheckAdj, snode -> true)) &&
          !assignNode.noInterfToISAX;

      // Create body
      boolean requiredByNode =
          orderEntry.aux != 0 || assignProvidedByISAX || defaultGeneratedBySCAL; // not required if it does not have interf to ISAX
      if ((requiredByNode || !checkAdj.isAdj())) { // should not go on this path if it has no opcode (= encoding don.t care)
        String assignSignal;
        // if(checkAdj.getAdj() == AdjacentNode.addrReq || checkAdj.getAdj() == AdjacentNode.cancelReq || isValidAdj) // for
        // wrmem_addr_valid. It is simply 1 in case of an instr using addr bits
        if (!assignProvidedByISAX && !defaultGeneratedBySCAL && orderEntry.aux == 0)
          assignSignal = "1"; //'addrReq = 1'
        else {
          // e.g. 'WrRD_<stage>_s = WrRD_<isax>_i;'
          assignSignal = registry.lookupExpressionRequired(
              new Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN, checkAdj, stage, orderEntry.isax, orderEntry.aux));
        }
        conditionalAssigns.add(new ConditionalAssignEntry(false, RdIValid, assignSignal));
      } else if (isValidAdj &&
                 checkAdj.getAdj().validMarkerFor == AdjacentNode.none) { // for exp in case of addrValid, it should not go on this path
        conditionalAssigns.add(new ConditionalAssignEntry(false, RdIValid, "1"));
      } else if (isValidAdj) { // here should go addrValid if no instructions require it
        assert (checkAdj.getAdj().validMarkerFor != null);
      } else if (assignNode.mustToCore) { // Example: addr, size
        needsDefault = true;
      }
    }
    if (isValidAdj || operation.tags.contains(NodeTypeTag.accumulatesUntilCommit)) {
      var defaultAssignNodeInst =
          registry.lookupRequired(new NodeInstanceDesc.Key(Purpose.match_WIREDIN_OR_PIPEDIN, assignNode, stage, ""));
      String assignExpr = "0";
      if (!defaultAssignNodeInst.getExpression().startsWith("MISSING_")) {
        requestedFor.addAll(defaultAssignNodeInst.getRequestedFor(), true); // Add the input's requestedFor set.
        assignExpr = defaultAssignNodeInst.getExpression();
      }
      conditionalAssigns.add(new ConditionalAssignEntry(false, "", assignExpr));
      // body += tab.repeat(2)+"default : "+assignNodeName+" = 0;\n";
    } else if (needsDefault) {
      // For addr, size, (data)
      var assignNodeInst = registry.lookupRequired(new NodeInstanceDesc.Key(Purpose.match_WIREDIN_OR_PIPEDIN, assignNode, stage, ""));
      requestedFor.addAll(assignNodeInst.getRequestedFor(), true); // Add the input's requestedFor set.
      conditionalAssigns.add(new ConditionalAssignEntry(false, "", assignNodeInst.getExpression()));
      // body += tab.repeat(2)+"default : "+assignNodeName+" = " + assignNodeInst.getExpression() + ";\n";
    } else if (conditionalAssigns.stream().filter(condAssign -> !condAssign.isExclusive).count() == 0) {
      if (conditionalAssigns.size() != 0) // All others are exclusive
        conditionalAssigns.get(conditionalAssigns.size() - 1).cond += " || 1";
      else
        conditionalAssigns.add(new ConditionalAssignEntry(false, "", "0"));
    }

    // Build the body from the collected assign conditions.
    String body = "always_comb begin \n";
    var exclusiveAssigns = conditionalAssigns.stream().filter(condAssign -> condAssign.isExclusive).toList();
    if (!exclusiveAssigns.isEmpty()) {
      String ifElseif = "if";
      for (ConditionalAssignEntry exclusiveAssign : exclusiveAssigns) {
        assert (!exclusiveAssign.cond.isEmpty());
        body += tab + String.format("%s (%s) %s = %s;\n", ifElseif, exclusiveAssign.cond, assignNodeName, exclusiveAssign.expr);
        ifElseif = "else if";
      }
    }
    var nonExclusiveAssigns = conditionalAssigns.stream().filter(condAssign -> !condAssign.isExclusive).toList();
    if (!nonExclusiveAssigns.isEmpty()) {
      String caseTabPrefix = tab;
      if (!exclusiveAssigns.isEmpty()) {
        body += tab + "else begin\n";
        caseTabPrefix = tab + tab;
      }
      body += caseTabPrefix + "case(1'b1)\n";
      boolean hadDefault = false;
      for (int iAssign = 0; iAssign < nonExclusiveAssigns.size(); ++iAssign) {
        var assign = nonExclusiveAssigns.get(iAssign);
        assert (!assign.cond.isEmpty() || !hadDefault);
        String caseCond = (assign.cond.isEmpty() || (iAssign == nonExclusiveAssigns.size() - 1 && !hadDefault)) ? "default" : assign.cond;
        body += caseTabPrefix + tab + String.format("%s: %s = %s;\n", caseCond, assignNodeName, assign.expr);
        if (caseCond.equals("default"))
          hadDefault = true;
      }
      body += caseTabPrefix + "endcase\n";
      if (!exclusiveAssigns.isEmpty())
        body += tab + "end\n";
    }
    body += "end\n";
    if (conditionalAssigns.isEmpty()) {
      // if((nrElem==0) && !isValidAdj && !needsDefault) {
      // logger.error("Encountered unsupported node " + checkAdj.name + " in ValidMuxStrategy");
      logger.warn("ValidMuxStrategy: Found no supported elements for " + nodeKey.toString(false));
      return new NodeLogicBlock();
    }

    ret.declarations += (language.CreateDeclSig(assignNode.NodeNegInput(), stage, "", true, assignNodeName));
    ret.outputs.add(new NodeInstanceDesc(new Key(assignNode, stage, ""), assignNodeName, ExpressionType.WireName, requestedFor));
    ret.logic += body;
    //		if(nrElem==1 && !isValidAdj)
    //			ret.logic += assignLogic;
    //		else if((nrElem==0) && checkAdj.getAdj() ==AdjacentNode.addrReq)
    //			ret.logic += empty_assignLogic;
    //		else
    //			ret.logic += body;
    return ret;
  }
  HashSet<NodeInstanceDesc.Key> implementedKeys = new HashSet<>();
  @Override
  public Optional<NodeLogicBuilder> implement(Key nodeKey) {
    // if (nodeKey.getNode().tags.contains(NodeTypeTag.supportsPortNodes))
    //	return Optional.empty();
    SCAIEVNode baseNode = nodeKey.getNode().isAdj() ? bNodes.GetSCAIEVNode(nodeKey.getNode().nameParentNode) : nodeKey.getNode();
    if (!bNodes.HasSCAIEVFNode(baseNode.name))
      return Optional.empty();
    // Special case: For custom registers, the (Rd|Wr)<reg>_addr node is registered explicitly in a separate stage.
    SCAIEVNode lookupNode_ = baseNode;
    if (bNodes.IsUserBNode(nodeKey.getNode())) {
      if (nodeKey.getNode().getAdj() == AdjacentNode.addr)
        lookupNode_ = nodeKey.getNode();
      else if (nodeKey.getNode().getAdj().validMarkerFor == AdjacentNode.addr)
        lookupNode_ = bNodes.GetAdjSCAIEVNode(bNodes.GetNonAdjNode(nodeKey.getNode()), AdjacentNode.addr).orElseThrow();
    }
    SCAIEVNode lookupNode = lookupNode_;
    if (nodeKey.getPurpose().matches(NodeInstanceDesc.Purpose.REGULAR) &&
        nodeKey.getNode().isInput                                       // Is an input node to the core (-> an output from SCAL to the core)
        && !nodeKey.getNode().isSpawn()                                 // Not handling decoupled/spawn
        && !nodeKey.getNode().tags.contains(NodeTypeTag.perStageStatus) // WrFlush, WrStall, etc. are handled by WrStallFlushStrategy
        //&& this.op_stage_instr.getOrDefault(lookupNode, new HashMap<>()).containsKey(nodeKey.getStage()) //Some ISAX uses the base node
        && nodeKey.getISAX().isEmpty() // This strategy MUXes between all ISAXes, outputting a node with an empty ISAX field
    ) {
      if (!implementedKeys.add(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.REGULAR)))
        return Optional.empty(); // If this strategy already created a builder without any outputs, ignore it.
      var requestedFor = new RequestedForSet();
      return Optional.of(
          NodeLogicBuilder.fromFunction("ValidMuxStrategy (" + nodeKey.toString() + ")", (NodeRegistryRO registry, Integer aux) -> {
            HashSet<String> relevantISAXes =
                this.op_stage_instr.getOrDefault(lookupNode, new HashMap<>()).getOrDefault(nodeKey.getStage(), new HashSet<>());

            NodeLogicBlock updateBlock = new NodeLogicBlock();

            CreateValidEncodingIValid(registry, nodeKey, relevantISAXes, baseNode, nodeKey.getNode(), nodeKey.getNode().isAdj() ? "1" : "0",
                                      updateBlock, requestedFor);

            // for WrPC, we need to create an associated flush signal to the processor
            // as writing the PC redirects program flow
            // new node is only created if it does not already exist
            if (nodeKey.getNode().equals(bNodes.WrPC_valid)) {
              for (PipelineStage prevStage : nodeKey.getStage().getPrev()) {
                // get the valid expression just generated
                String validExpr = language.CreateLocalNodeName(nodeKey.getNode(), nodeKey.getStage(), "");
                // add a WrFlush signal (equal to the wrPC_valid signal) as an output with aux != 0
                // WrStallFlushStrategy will collect this flush signal
                updateBlock.outputs.add(
                    new NodeInstanceDesc(new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.REGULAR, bNodes.WrFlush, prevStage, "", aux),
                                         validExpr, ExpressionType.AnyExpression, requestedFor));
                // force a dependency for an overall flush signal with aux=0
                // this does not yet create an output pin!
                registry.lookupExpressionRequired(
                    new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.REGULAR, bNodes.WrFlush, prevStage, "", 0));
              }
            }

            return updateBlock;
          }));
    }
    return Optional.empty();
  }
}
