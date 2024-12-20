package scaiev.scal.strategy.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAL;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.pipeline.PipelineStage.StageTag;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Key;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.strategy.SingleNodeStrategy;
import scaiev.ui.SCAIEVConfig;
import scaiev.util.Verilog;

/**
 * Implements stalling logic and WrStall nodes for the isaxctx instruction.
 */
public class SCALStateContextStrategy extends SingleNodeStrategy {

  // logging
  protected static final Logger logger = LogManager.getLogger();

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
   * @param hasWrRD_datahazard If set, ignores WrRD_spawn nodes for fence
   */
  public SCALStateContextStrategy(Verilog language, BNode bNodes, Core core,
                                  HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                                  HashMap<String, SCAIEVInstr> allISAXes, SCAIEVConfig cfg) {
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
    this.op_stage_instr = op_stage_instr;
    this.allISAXes = allISAXes;
    this.cfg = cfg;
  }

  public static final SCAIEVNode isaxctx_node = new SCAIEVNode("isaxctx");
  @Override
  public Optional<NodeLogicBuilder> implement(Key nodeKey) {
    // Additional implementation for isaxctx
    if (nodeKey.getPurpose().matches(Purpose.MARKER_INTERNALIMPL_PIN) && nodeKey.getNode().equals(isaxctx_node) &&
        allISAXes.containsKey(SCAL.PredefInstr.ctx.instr.GetName())) {

      // get commit stage
      var commit_stages = core.GetRootStage()
                              .getAllChildren()
                              // only look at core stages
                              .filter(stage -> stage.getKind() == StageKind.Core)
                              .filter(stage -> stage.getTags().contains(StageTag.Commit))
                              .collect(Collectors.toList());

      if (commit_stages.size() != 1) {
        logger.error("Multiple commit stages found! Context generation will most likely fail!");
      }

      var stage_switch_ctx = commit_stages.get(0);

      return Optional.of(NodeLogicBuilder.fromFunction("isaxctx", (registry, aux) -> {
        var ret = new NodeLogicBlock();

        // get RdIValid and RdInstr signals for the commit stage
        // reasoning: we know if the instruction was flushed at that point

        var rdivalid = registry.lookupExpressionRequired(
            new NodeInstanceDesc.Key(bNodes.RdIValid, stage_switch_ctx, SCAL.PredefInstr.ctx.instr.GetName()));
        var rdinstr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdInstr, stage_switch_ctx, ""));
        // flush signal to avoid ctx switch during misprediction
        var rdflush = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdFlush, stage_switch_ctx, ""));

        // create current context register
        ret.declarations += String.format("reg [$clog2(%s):0] custom_regfile_ctx;\n", cfg.number_of_contexts);

        // generate reset logic (set to ctx 0) and switch logic
        ret.logic += String.format("always@(posedge %s) begin\n", language.clk);
        ret.logic += String.format("    if (%s)\n", language.reset);
        ret.logic += "        custom_regfile_ctx <= 0;\n";
        ret.logic += String.format("    else if (%s && !%s)\n", rdivalid, rdflush);
        ret.logic += String.format("        custom_regfile_ctx <= {%s[31:25], %s[11:7]};\n", rdinstr, rdinstr);
        ret.logic += "end\n";

        // create signal node for ctx register
        var output = new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.MARKER_INTERNALIMPL_PIN),
                                          "custom_regfile_ctx", ExpressionType.WireName);
        ret.outputs.add(output);

        // stall earlier stages if ctx switch is imminent

        // get all stages prior to commit
        var stages_pre_switch_ctx = core.GetRootStage()
                                        .getAllChildren()
                                        // only look at core stages
                                        .filter(stage -> stage.getKind() == StageKind.Core)
                                        .filter(stage -> stage.getStagePos() < stage_switch_ctx.getStagePos())
                                        .collect(Collectors.toList());

        // generate stall for every pre-commit stage
        for (var stage : stages_pre_switch_ctx) {
          // filter out all stages later than this one
          var later_stages_ctx_switch_expression =
              stages_pre_switch_ctx.stream()
                  .filter(s -> s.getStagePos() > stage.getStagePos())
                  .map(s
                       ->
                       // acquire the rdivalid ctx switch expressions
                       registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, s, SCAL.PredefInstr.ctx.instr.GetName()))

                           )
                  .collect(Collectors.toList());
          // add commit stage as a stall source
          later_stages_ctx_switch_expression.add(rdivalid);

          // generate stall signal expression
          String wireName = String.format("stall_from_ctxswitch_%d_s", stage.getStagePos());
          ret.declarations += String.format("wire %s;\n", wireName);
          ret.logic += String.format("assign %s = (%s);\n", wireName, String.join(" || ", later_stages_ctx_switch_expression));

          ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrStall, stage, "", aux), wireName,
                                               ExpressionType.WireName));
        }

        return ret;
      }));
    }
    return Optional.empty();
  }
}
