package scaiev.scal.strategy.state;

import java.util.List;

import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVNode;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageTag;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.strategy.state.SCALStateStrategy.RegfileInfo;
import scaiev.util.Verilog;

public class EarlyRWForwardingImplementation extends EarlyRWImplementation {
  public EarlyRWForwardingImplementation(SCALStateStrategy stateStrategy, Verilog language, BNode bNodes, Core core) {
    super(stateStrategy, language, bNodes, core);
    throw new RuntimeException("Not implemented");
  }
  @Override
  String getEarlyDirtyName(RegfileInfo regfile) {
    return String.format("%s_dirty_early", regfile.regName);
  }
  boolean isForwardedEarlyWrite(RegfileInfo regfile, SCAIEVNode writeNode) {
    // Check if the writeNode is from an early write, and forwarding is supported for the other present early writes and reads.
    if (regfile.earlyWrites.size() == 1 &&
        regfile.earlyWrites.stream().anyMatch(earlyWriteKey -> earlyWriteKey.getNode().equals(writeNode))) {
      PipelineStage writeStage = regfile.earlyWrites.get(0).getStage();
      PipelineFront writeFront = new PipelineFront(writeStage);
      if (writeStage.getTags().contains(StageTag.InOrder) &&
          regfile.earlyReads.stream().allMatch(earlyReadKey -> writeFront.isAroundOrBefore(earlyReadKey.getStage(), false)))
        return true;
    }
    return false;
  }
  @Override
  boolean rerunPreissueOn(RegfileInfo regfile, SCAIEVNode writeNode) {
    return !isForwardedEarlyWrite(regfile, writeNode);
  }
  @Override
  String earlyDirtyFFResetLogic(NodeRegistryRO registry, RegfileInfo regfile, SCAIEVNode writeNode, String addr, String lineTabs) {
    // All hazards handled by WrRerunNext plus stalling from the general dirty reg
    if (isForwardedEarlyWrite(regfile, writeNode))
      return "";
    return lineTabs + String.format("%s%s <= 0;\n", getEarlyDirtyName(regfile), (regfile.depth > 1 ? String.format("[%s]", addr) : ""));
  }
  @Override
  String earlyDirtyFFSetLogic(NodeRegistryRO registry, RegfileInfo regfile, SCAIEVNode writeNode, String addr, String lineTabs) {
    // All hazards handled by WrRerunNext plus stalling from the general dirty reg
    if (isForwardedEarlyWrite(regfile, writeNode))
      return "";
    return lineTabs + String.format("%s%s <= 1;\n", getEarlyDirtyName(regfile), (regfile.depth > 1 ? String.format("[%s]", addr) : ""));
  }

  @Override
  String earlyReadForwardLogic(NodeRegistryRO registry, RegfileInfo regfile, NodeInstanceDesc.Key earlyReadKey, String rdata_outLogic,
                               String raddr, String stallOutLogic, String lineTabs) {
    if (regfile.earlyWrites.size() > 0 &&
        regfile.earlyWrites.stream().anyMatch(earlyWriteKey -> isForwardedEarlyWrite(regfile, earlyWriteKey.getNode()))) {
      // Forwarding
      String forwardNameBase = String.format("%s_earlyfwd", regfile.regName);
      String forwardBufferName = forwardNameBase + "_data";
      String forwardAddrName = forwardNameBase + "_addr";
      String forwardValidName = forwardNameBase + "_valid";
    }
    throw new RuntimeException("Not implemented");
    // return "";
  }
  @Override
  NodeLogicBlock earlyRead(NodeRegistryRO registry, RegfileInfo regfile, List<Integer> auxReads, String regfilePin_rdata,
                           String regfilePin_re, String regfilePin_raddr, String dirtyRegName) {
    String tab = language.tab;
    boolean hasForward = false;
    NodeLogicBlock ret = new NodeLogicBlock();
    if (regfile.earlyWrites.size() > 0 &&
        regfile.earlyWrites.stream().anyMatch(earlyWriteKey -> isForwardedEarlyWrite(regfile, earlyWriteKey.getNode()))) {
      hasForward = true;
      int forwardBufferDepth = Math.min(regfile.depth, 2);

      String forwardNextNameBase = String.format("%s_earlyfwd_next", regfile.regName);
      String forwardNextBufferName = forwardNextNameBase + "_data";
      String forwardNextAddrName = forwardNextNameBase + "_addr";
      String forwardNextValidName = forwardNextNameBase + "_valid";
      String forwardNextUpdateName = forwardNextNameBase + "_update";

      String forwardNameBase = String.format("%s_earlyfwd", regfile.regName);
      String forwardBufferName = forwardNameBase + "_data";
      String forwardAddrName = forwardNameBase + "_addr";
      String forwardValidName = forwardNameBase + "_valid";

      ret.declarations += String.format("logic [%d-1:0] %s [%d];\n", regfile.width, forwardBufferName, forwardBufferDepth);
      ret.declarations += String.format("logic [%d-1:0] %s [%d];\n", regfile.width, forwardNextBufferName, forwardBufferDepth);
      if (regfile.depth > 1) {
        ret.declarations += String.format("logic [$clog2(%d)-1:0] %s [%d];\n", regfile.depth, forwardAddrName, forwardBufferDepth);
        ret.declarations += String.format("logic [$clog2(%d)-1:0] %s [%d];\n", regfile.depth, forwardNextAddrName, forwardBufferDepth);
      }
      ret.declarations += String.format("logic %s [%d];\n", forwardValidName, forwardBufferDepth);
      ret.declarations += String.format("logic %s [%d];\n", forwardNextValidName, forwardBufferDepth);

      ret.declarations += String.format("logic %s [%d];\n", forwardNextUpdateName, forwardBufferDepth);

      ret.logic += String.format("always_ff @(posedge %s) begin : %s_ff_scope\n", language.clk, forwardNameBase)
                   + tab + String.format("for (int i = 0; i < %d; i=i+1) begin\n", forwardBufferDepth)
                   + tab + tab + String.format("if (%s[i]) begin\n", forwardNextUpdateName)
                   + tab + tab + tab + String.format("%s[i] <= %s[i];\n", forwardBufferName, forwardNextBufferName)
                   + tab + tab + tab + String.format("%s[i] <= %s[i];\n", forwardAddrName, forwardNextAddrName)
                   + tab + tab + tab + String.format("%s[i] <= %s[i];\n", forwardValidName, forwardNextValidName)
                   + tab + tab + "end\n"
                   + tab + "end\n"
                   + tab + String.format("if (%s) begin\n", language.reset)
                   + tab + tab + String.format("for (int iRst = 0; iRst < %d; iRst=iRst+1) begin\n", forwardBufferDepth)
                   + tab + tab + tab + String.format("%s[i] <= 0;\n", forwardValidName) + tab + tab + "end\n" + tab + "end\n"
                   + "end\n";

      // TODO: Proper flush tracking -
      //- follow the forward index through the pipeline; on flush in any stage, flush the forward index of that stage
      //- need special handling for non-continuous stages
      //- may need several cycles for this operation
      //-> should use a separate strategy for this purpose, so it can more easily work with different cores)
    }
    // TODO: Override read with forward
    ret.addOther(
        super.earlyRead(registry, regfile, auxReads, regfilePin_rdata, regfilePin_re, regfilePin_raddr, getEarlyDirtyName(regfile)));
    return ret;
  }
  @Override
  NodeLogicBlock issueWriteToEarlyReadHazard(NodeRegistryRO registry, RegfileInfo regfile, int auxRerun) {
    NodeLogicBlock ret = super.issueWriteToEarlyReadHazard(registry, regfile, auxRerun);
    ret.declarations += String.format("logic [%d-1:0] %s;\n", regfile.depth, getEarlyDirtyName(regfile));
    return ret;
  }
}
