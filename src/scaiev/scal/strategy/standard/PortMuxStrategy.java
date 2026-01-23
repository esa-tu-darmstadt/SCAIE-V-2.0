package scaiev.scal.strategy.standard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVInstr.InstrTag;
import scaiev.frontend.SCAIEVNode;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Key;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.SCALUtil;
import scaiev.scal.strategy.SingleNodeStrategy;
import scaiev.ui.SCAIEVConfig;
import scaiev.util.Log2;
import scaiev.util.Verilog;

/**
 * Handles ISAX port multiplexing (see {@link PipelineStage.StageKind#ISAXMux}).
 */
public class PortMuxStrategy extends SingleNodeStrategy {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  private static AtomicInteger nextUniqueID = new AtomicInteger(0);

  int uniqueID;
  Verilog language;
  BNode bNodes;
  Core core;
  HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr;
  HashMap<String, SCAIEVInstr> allISAXes;
  SCAIEVConfig cfg;
  /**
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param allISAXes The ISAX descriptions
   * @param cfg The SCAIE-V global config
   */
  public PortMuxStrategy(Verilog language, BNode bNodes, Core core,
                         HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                         HashMap<String, SCAIEVInstr> allISAXes,
                         SCAIEVConfig cfg) {
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
    this.op_stage_instr = op_stage_instr;
    this.allISAXes = allISAXes;
    this.uniqueID = nextUniqueID.getAndIncrement();
    this.cfg = cfg;
  }

  private static final SCAIEVNode portSelectNode = new SCAIEVNode("PortSelect", 1, false); 

  HashSet<NodeInstanceDesc.Key> implementedKeys = new HashSet<>();
  @Override
  public Optional<NodeLogicBuilder> implement(Key nodeKey) {
    PipelineStage multiportBase = nodeKey.getStage().getMultiportBase();
    if (multiportBase == nodeKey.getStage())
      return Optional.empty();

    if (nodeKey.getISAX().isEmpty() || !allISAXes.containsKey(nodeKey.getISAX())
        || allISAXes.get(nodeKey.getISAX()).hasTag(InstrTag.MultiPortFrontend))
      return Optional.empty();

    if (nodeKey.getNode().equals(portSelectNode) && nodeKey.getAux() == this.uniqueID) {
      if (!nodeKey.getPurpose().matches(NodeInstanceDesc.Purpose.REGULAR))
        return Optional.empty();
      if (nodeKey.getStage().getKind() != StageKind.Core)
        return Optional.empty();
      int portIdx = multiportBase.getChildren().indexOf(nodeKey.getStage());
      if (portIdx == -1) {
        logger.error("PortMuxStrategy: Could not find port stage {} in {}", nodeKey.getStage().getName(), multiportBase.getName());
        return Optional.empty();
      }
      //Port selection: In the given port stage, creates a condition node on whether the ISAX is present and selected.
      // Stalls if the ISAX is present but not selected.
      return Optional.of(NodeLogicBuilder.fromFunction("PortMuxStrategy_"+nodeKey.toString(false), (registry, aux) -> {
        var ret = new NodeLogicBlock();
        String ivalid = registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, nodeKey.getStage(), nodeKey.getISAX())).getExpressionWithParens();
        String ipresent = registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdInStageValid, nodeKey.getStage(), "")).getExpressionWithParens();
        List<String> deselectConds = new ArrayList<>();
        for (int iOther = 0; iOther < portIdx; ++iOther) {
          PipelineStage otherStage = multiportBase.getChildren().get(iOther);
          if (otherStage.getKind() != StageKind.Core)
            continue;
          deselectConds.add(registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
                                                                Purpose.REGULAR,
                                                                portSelectNode, otherStage, nodeKey.getISAX(),
                                                                uniqueID)));
        }
        String wireName = nodeKey.toString(false);
        ret.declarations += String.format("logic %s;\n", wireName);
        if (cfg.portmux_limit == -1 || portIdx <= cfg.portmux_limit) {
          ret.logic += String.format("assign %s = %s && %s%s;\n",
                                     wireName, ipresent, ivalid,
                                     deselectConds.stream().map(cond->" && !"+cond).reduce("",(a,b)->a+b));
        }
        else {
          ret.logic += String.format("assign %s = 1'b0;\n", wireName);
        }
        //Stall if the ISAX is present but not selected.
        String stallCond = "%s && !%s".formatted(ivalid, wireName);
        //Stall if the next stage has the max number of such ISAXes stalling.
        // -> Makes sure we don't get more active ISAXes than ports, which 
        List<PipelineStage> successors = SCALUtil.flatmapIntoPorts(multiportBase.getNext().stream()).filter(st->st.getKind() == StageKind.Core)
                                                 .toList();
        int maxISAXPorts = 1; //Number of ports the ISAX has (currently always 1)
        if (maxISAXPorts < successors.size()) {
          Stream<String> successorValidAndStallingConds = successors.stream().map(
                successor -> "(%s) && %s".formatted(
                    SCALUtil.buildCond_StageStalling(bNodes, registry, successor, false),
                    registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, successor, nodeKey.getISAX())).getExpressionWithParens()
              ));
          if (maxISAXPorts == 1) {
            // ISAX only has one port -> Any stalling ISAX in the next stage is enough to exceed the limit (OR the conditions)
            stallCond += " || " + successorValidAndStallingConds.reduce((a,b) -> a+" || "+b).get();
          }
          else {
            // ISAX has several ports but not enough -> Check the sum of stalling ISAXes in the next stage
            assert(maxISAXPorts > 1);
            String wireNameCount = wireName + "_enteringNext_count";
            int wireSizeCount = Log2.clog2(successors.size()+1);
            ret.declarations += String.format("logic [%d-1:0] %s;\n", wireSizeCount, wireNameCount);
            ret.logic += "assign %s = %s;\n".formatted(
                             successorValidAndStallingConds.map(cond -> "((%1$s) ? %2$d'd1 : %2$d'd0)".formatted(cond, wireSizeCount))
                                                           .reduce((a,b) -> a+" + "+b).get());
            stallCond += " || %s >= %d'd%d".formatted(wireNameCount, wireSizeCount, maxISAXPorts); 
          }
        }
        String wireNameStall = wireName + "_stall";
        ret.declarations += String.format("logic %s;\n", wireNameStall);
        ret.logic += String.format("assign %s = %s;\n", wireNameStall, stallCond);
        registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.WrStall, nodeKey.getStage(), ""));
        ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrStall, nodeKey.getStage(), "", aux),
                                             wireNameStall, ExpressionType.WireName)); 

        ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.REGULAR), wireName, ExpressionType.WireName));
        return ret;
      }));
    }

    if (nodeKey.getAux() != 0)
      return Optional.empty();

    SCAIEVNode baseNode = bNodes.GetNonAdjNode(nodeKey.getNode());
    //Only operate on nodes listed as FNode (i.e., base nodes that can be used on the ISAX interface).
    if (baseNode.name.isEmpty() || !bNodes.HasSCAIEVFNode(baseNode.name) || nodeKey.getNode().isSpawn())
      return Optional.empty();
    if (nodeKey.getStage().getKind() == StageKind.Core) {
      if (!nodeKey.getNode().isInput || nodeKey.getNode().equals(bNodes.RdIValid))
        return Optional.empty();
      if (!nodeKey.getPurpose().matches(NodeInstanceDesc.Purpose.WIREDIN))
        return Optional.empty();
      List<PipelineStage> muxStages = multiportBase.getChildren().stream().filter(st->st.getKind() == StageKind.ISAXMux).toList();
      if (muxStages.size() == 0) {
        if (nodeKey.getStage().getMultiportBase().getChildren().stream().filter(st->st.getKind() == StageKind.Core).count() > 1) {
          //No MUX stage present (?)
          logger.error("PortMuxStrategy: Unexpectedly missing a MUX pseudo-stage for {}", multiportBase.getName());
        }
        return Optional.empty();
      }
      if (muxStages.size() > 1) {
        //There should only be one MUX stage per multiport stage
        logger.error("PortMuxStrategy: Unexpectedly found several MUX pseudo-stages for {}", multiportBase.getName());
        return Optional.empty();
      }
      PipelineStage muxStage = muxStages.get(0);
      RequestedForSet requestedFor = new RequestedForSet(nodeKey.getISAX());
      //ISAX -> SCAL pin
      return Optional.of(NodeLogicBuilder.fromFunction("PortMuxStrategy_toCore_"+nodeKey.toString(false), (registry, aux) -> {
        var ret = new NodeLogicBlock();
        String wireName = nodeKey.toString(false) + "_s";
        NodeInstanceDesc nodeInst = registry.lookupRequired(new NodeInstanceDesc.Key(nodeKey.getNode(), muxStage, nodeKey.getISAX()));
        int nodeSize = nodeInst.getKey().getNode().size;
        ret.declarations += "logic %s%s;\n".formatted(nodeSize>1?("[%d-1:0]".formatted(nodeSize)):"", wireName);
        
        if (nodeKey.getNode().isValidNode()) {
          // Pass through control signals (validReq, etc.) only to the selected port
          String selectedExpr = registry.lookupRequired(new NodeInstanceDesc.Key(Purpose.REGULAR, portSelectNode,
                                                                                 nodeKey.getStage(), nodeKey.getISAX(), this.uniqueID))
                                        .getExpressionWithParens();
          ret.logic += "assign %s = %s && %s;\n".formatted(wireName, selectedExpr, nodeInst.getExpressionWithParens());
        }
        else {
          // Pass through data signals directly
          ret.logic += "assign %s = %s;\n".formatted(wireName, nodeInst.getExpressionWithParens());
        }
        ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.WIREDIN),
                                             wireName, ExpressionType.WireName, requestedFor));
        return ret;
      }));
      
    }
    if (nodeKey.getStage().getKind() == StageKind.ISAXMux) {
      if (nodeKey.getNode().isInput)
        return Optional.empty();
      if (!nodeKey.getPurpose().matches(NodeInstanceDesc.Purpose.REGULAR))
        return Optional.empty();
      if (multiportBase.getChildren().stream().filter(st->st.getKind() == StageKind.Core).count() < 1) {
        logger.error("PortMuxStrategy: Unexpectedly missing a Core stage for {}", multiportBase.getName());
        return Optional.empty();
      }
      RequestedForSet requestedFor = new RequestedForSet(nodeKey.getISAX());
      //SCAL -> ISAX pin
      return Optional.of(NodeLogicBuilder.fromFunction("PortMuxStrategy_toISAX_"+nodeKey.toString(false), (registry, aux) -> {
        var ret = new NodeLogicBlock();
        String wireName = nodeKey.toString(false) + "_s";
        List<NodeInstanceDesc> nodeInsts = multiportBase.getChildren().stream()
                                               .filter(st->st.getKind() == StageKind.Core)
                                               .map(coreStage -> registry.lookupRequired(new NodeInstanceDesc.Key(
                                                                     nodeKey.getNode(), coreStage, nodeKey.getISAX())))
                                               .toList();
        int nodeSize = nodeInsts.stream().map(inst->inst.getKey().getNode().size).max(Integer::compare).orElseThrow();
        ret.declarations += "logic %s%s;\n".formatted(nodeSize>1?("[%d-1:0]".formatted(nodeSize)):"", wireName);
        
        if (nodeKey.getNode().isValidNode()) {
          // Apply an OR in case of port control signals (validResp, etc.)
          String portOrExpr = nodeInsts.stream().map(inst->inst.getExpressionWithParens()).reduce((a,b)->a+" || "+b).orElse("1'b0");
          ret.logic += "assign %s = %s;\n".formatted(wireName, portOrExpr);
        }
        else {
          // MUX data signals based on the active port selection
          List<String> selExprs = multiportBase.getChildren().stream()
                                      .filter(st->st.getKind() == StageKind.Core)
                                      .map(coreStage -> registry.lookupRequired(new NodeInstanceDesc.Key(
                                                            Purpose.REGULAR, portSelectNode,
                                                            coreStage, nodeKey.getISAX(), this.uniqueID)).getExpression())
                                      .toList();
          assert(nodeInsts.size() == selExprs.size());
          ret.logic += """
              always_comb begin
                  %s = %s0;
              """.formatted(wireName, nodeSize>1 ? "'" : "1'b");
          ret.logic += IntStream.range(0, nodeInsts.size())
                           .mapToObj(i->"    if (%s) %s = %s;\n".formatted(selExprs.get(i), wireName, nodeInsts.get(i).getExpression()))
                           .reduce("", (a,b)->a+b);
          ret.logic += "end\n";
        }
        ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.REGULAR),
                                             wireName, ExpressionType.WireName, requestedFor));
        return ret;
      }));
    }
    return Optional.empty();
  }
}
