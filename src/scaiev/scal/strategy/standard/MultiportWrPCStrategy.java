package scaiev.scal.strategy.standard;

import java.util.function.Consumer;
import java.util.stream.IntStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.MultiportStallAttributes;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.pipeline.PipelineStage.StageTag;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.SCALUtil;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.util.Verilog;

/**
 * Strategy that builds shared WrPC out of the last port stage and WrPCLate (see {@link MultiportWrPCStrategy#makeDelayedWrPCNode(BNode)}).
 */
public class MultiportWrPCStrategy extends MultiNodeStrategy {
  protected static final Logger logger = LogManager.getLogger();

  Verilog language;
  BNode bNodes;
  Core core;

  SCAIEVNode delayedWrPC;
  SCAIEVNode delayedWrPCValid;

  /**
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   */
  public MultiportWrPCStrategy(Verilog language, BNode bNodes, Core core) {
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
    this.delayedWrPC = makeDelayedWrPCNode(bNodes);
    this.delayedWrPCValid = makeDelayedWrPCValidNode(bNodes);
    if (bNodes.GetSCAIEVNode(delayedWrPC.name).name.isEmpty()) {
      bNodes.AddCoreBNode(delayedWrPC);
      bNodes.AddCoreBNode(delayedWrPCValid);
    }
  }
 
  /**
   * Node for WrPC that should be delayed for correct 'shared WrPC' multi-port handling.
   * MultiportWrPCStrategy uses WrPCLate nodes with the multiport-base stage's name as 'ISAX' in its MUXing.
   * MultiportWrPCStrategy can be triggered by adding a requirement with Purpose.MARKER_INTERNALIMPL_PIN in the multiport base stage.
   * @param bNodes The BNode object for the node instantiation
   * @return the WrPCLate node
   */
  public static SCAIEVNode makeDelayedWrPCNode(BNode bNodes) {
    return new SCAIEVNode("WrPCLate", bNodes.WrPC.size, true);
  }
  /**
   * validReq node for {@link #makeDelayedWrPCNode(BNode)}.
   * @param bNodes The BNode object for the node instantiation
   * @return the WrPCLate node
   */
  public static SCAIEVNode makeDelayedWrPCValidNode(BNode bNodes) {
    return new SCAIEVNode(makeDelayedWrPCNode(bNodes), AdjacentNode.validReq, 1, true, false);
  }

  private boolean implementSingle(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey) {
    //this strategy implements WrFlush on the CoreMultiport stage, FlushingTokenNode on the port stages.
    PipelineStage stage = nodeKey.getStage();
    PipelineStage baseStage = stage.getMultiportBase();
    var multiportStallAttr = nodeKey.getStage().getMultiportBase().getTagAttr(StageTag.MultiportStall, MultiportStallAttributes.class);

    if (nodeKey.getNode().equals(delayedWrPC) && nodeKey.getStage().getKind() == StageKind.CoreMultiport &&
        nodeKey.getPurpose().matches(Purpose.MARKER_INTERNALIMPL_PIN) && nodeKey.getISAX().isEmpty() && nodeKey.getAux() == 0 &&
        (core.getNodes().containsKey(bNodes.WrPC) &&
          core.translateStageScheduleNumber(core.getNodes().get(bNodes.WrPC).getEarliest())
              .isAroundOrBefore(nodeKey.getStage(), false))) {
      if (baseStage.getKind() != StageKind.CoreMultiport)
        return false;
      assert(multiportStallAttr != null);
      if (multiportStallAttr.perPortFlush() || !multiportStallAttr.hasSharedFlush())
        return false; //No need to do anything
      RequestedForSet requestedFor = new RequestedForSet();
      //React to MARKER_INTERNALIMPL_PIN with WrPCLate in a multiport base stage
      // -> Stall all later ports after the port with active WrPC
      // -> Register the WrPC request
      // -> Then apply WrPC and a flush to the multiport base stage
      out.accept(
          NodeLogicBuilder.fromFunction("MultiportWrPCStrategy (" + nodeKey.toString() + ")", (NodeRegistryRO registry, Integer aux) -> {
            NodeLogicBlock ret = new NodeLogicBlock();

            String wrPCReg = nodeKey.toString(false) + "_r";
            String wrPCValidReg = nodeKey.toString(false) + "_valid_r";
            String[] combStallConds = IntStream.range(0, (int)nodeKey.getStage().getChildren().stream().filter(st->st.getKind()==StageKind.Core).count())
                                                           .mapToObj(i->"").toArray(n->new String[n]);
            ret.declarations += String.format("logic [%d-1:0] %s;\n", bNodes.WrPC.size, wrPCReg);
            ret.declarations += String.format("logic %s;\n", wrPCValidReg);
            StringBuilder pcLogic = new StringBuilder();
            pcLogic.append("""
                always_ff @(posedge %1$s) begin
                    %2$s <= '0; //prevent X in sim
                    %3$s <= 1'b0;
                """.formatted(language.clk, wrPCReg, wrPCValidReg));
            boolean noConditions = true;
            for (int iPort = 0; iPort < nodeKey.getStage().getChildren().size(); ++iPort) {
              PipelineStage portStage = nodeKey.getStage().getChildren().get(iPort);
              if (portStage.getKind() != StageKind.Core)
                continue;
              var wrPCInstOpt = registry.lookupOptional(new NodeInstanceDesc.Key(delayedWrPC, portStage, ""));
              var wrPCValidInstOpt = registry.lookupOptional(new NodeInstanceDesc.Key(delayedWrPCValid, portStage, ""));
              if (wrPCInstOpt.isEmpty() || wrPCValidInstOpt.isEmpty())
                continue;
              //ASSUMPTION: Instructions are in-order (port 0 comes before port 1, etc.)
              String noStallFlushCond = SCALUtil.buildCond_StageNotStalling(bNodes, registry, portStage, true);
              //Whenever the given stage is not flushing and has WrPCLate_valid, 
              pcLogic.append("""
                      %6$sif (%1$s && %5$s) begin
                          %2$s <= %4$s;
                          %3$s <= 1'b1;
                      end
                  """.formatted(noStallFlushCond, //1
                                wrPCReg, wrPCValidReg, //2,3
                                wrPCInstOpt.get().getExpression(), wrPCValidInstOpt.get().getExpression(), //4,5
                                noConditions ? "" : "else ")); //6
              //Combinationally stall all later ports
              for (int iOther = iPort + 1; iOther < nodeKey.getStage().getChildren().size(); ++iOther) {
                if (nodeKey.getStage().getChildren().get(iOther).getKind() != StageKind.Core)
                  continue;
                combStallConds[iOther] += (combStallConds[iOther].isEmpty() ? "" : " || ") + wrPCValidInstOpt.get().getExpressionWithParens();
              }
              requestedFor.addAll(wrPCInstOpt.get().getRequestedFor(), true);
              noConditions = false;
            }
            pcLogic.append("end\n");
            if (noConditions) {
              // Discard generated logic and declarations.
              return new NodeLogicBlock();
            }
            ret.logic += pcLogic.toString();
            //Apply stalls
            for (int iPort = 0; iPort < nodeKey.getStage().getChildren().size(); ++iPort) {
              if (iPort >= combStallConds.length || combStallConds[iPort].isEmpty())
                continue;
              PipelineStage portStage = nodeKey.getStage().getChildren().get(iPort);
              var stallKey = new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrStall, portStage, "", aux);
              String stallWireName = language.CreateBasicNodeName(bNodes.WrStall, portStage, "", false) + "_multiportWrPC_s";
              ret.declarations += "logic %s;\n".formatted(stallWireName);
              ret.logic += "assign %s = %s;\n".formatted(stallWireName, combStallConds[iPort]);
              ret.outputs.add(new NodeInstanceDesc(stallKey, stallWireName, ExpressionType.WireName));
              registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrStall, portStage, ""));
            }
            //Output marker node
            ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.MARKER_INTERNALIMPL_PIN, delayedWrPC, nodeKey.getStage(), ""),
                                                 "", ExpressionType.AnyExpression));
            //Add WrPC to base stage
            ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrPC, nodeKey.getStage(), "", aux),
                                                 wrPCReg, ExpressionType.WireName, requestedFor));
            ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrPC_valid, nodeKey.getStage(), "", aux),
                                                 wrPCValidReg, ExpressionType.WireName, requestedFor));
            registry.lookupRequired(
                new NodeInstanceDesc.Key(Purpose.MARKER_TOCORE_PIN, bNodes.WrPC, nodeKey.getStage(), "")); // Ensure WrPC pin generation
            registry.lookupRequired(new NodeInstanceDesc.Key(Purpose.MARKER_TOCORE_PIN, bNodes.WrPC_valid, nodeKey.getStage(),
                                                             "")); // Ensure WrPC_valid pin generation
            //Perform WrFlush on base stage
            ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrFlush, nodeKey.getStage(), "", aux),
                                                 wrPCValidReg, ExpressionType.AnyExpression_Noparen));
            registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrFlush, nodeKey.getStage(), ""));
            return ret;
          }));
      return true;
    }
    return false;
  }


  @Override
  public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    var nodeKeyIter = nodeKeys.iterator();
    while (nodeKeyIter.hasNext()) {
      NodeInstanceDesc.Key nodeKey = nodeKeyIter.next();
      if (this.implementSingle(out, nodeKey))
        nodeKeyIter.remove();
    }
  }
}
