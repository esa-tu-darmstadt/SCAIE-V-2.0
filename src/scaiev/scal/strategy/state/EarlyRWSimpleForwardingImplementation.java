package scaiev.scal.strategy.state;

import java.util.List;

import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageTag;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeRegistry;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.SCALUtil;
import scaiev.scal.strategy.pipeline.NodeRegPipelineStrategy;
import scaiev.scal.strategy.state.SCALStateStrategy.RegfileInfo;
import scaiev.util.Verilog;

public class EarlyRWSimpleForwardingImplementation extends EarlyRWImplementation {
  public EarlyRWSimpleForwardingImplementation(SCALStateStrategy stateStrategy, Verilog language, BNode bNodes, Core core) {
    super(stateStrategy, language, bNodes, core);
  }
  @Override
  String getEarlyDirtyName(RegfileInfo regfile) {
    if (regfile.earlyWrites.size() > 0 &&
        regfile.earlyWrites.stream().anyMatch(earlyWriteKey -> isForwardedEarlyWrite(regfile, earlyWriteKey.getNode()))) {
      return String.format("%s_dirty_early", regfile.regName);
    }
    return null;
  }
  boolean isForwardedEarlyWrite(RegfileInfo regfile, SCAIEVNode writeNode) {
    if (writeNode == null)
      return false;
    //Check if the writeNode is from an early write, and forwarding is supported for the other present early reads.
    //(use reduce->null to assert there is only one key)
    NodeInstanceDesc.Key writeKey = regfile.earlyWrites.stream().filter(earlyWriteKey -> earlyWriteKey.getNode().equals(writeNode))
                                                                .reduce((a,b)->null).orElse(null);
    if (writeKey != null) {
      PipelineStage writeStage = writeKey.getStage();
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
    String earlyDirtyName = getEarlyDirtyName(regfile);
    if (earlyDirtyName == null)
      return "";
    if (writeNode == null) {
      //Reset all
      assert(addr == null);
      String resetRet = "";
      for (int i = 0; i < regfile.depth; ++i)
        resetRet += lineTabs + String.format("%s%s <= 0;\n", earlyDirtyName, (regfile.depth > 1 ? String.format("[%s]", addr) : ""));
      return resetRet;
    }
    assert(addr != null || regfile.depth == 0);
    // All hazards handled by WrRerunNext plus stalling from the general dirty reg
    if (isForwardedEarlyWrite(regfile, writeNode))
      return "";
    return lineTabs + String.format("%s%s <= 0;\n", earlyDirtyName, (regfile.depth > 1 ? String.format("[%s]", addr) : ""));
  }
  @Override
  String earlyDirtyFFSetLogic(NodeRegistryRO registry, RegfileInfo regfile, SCAIEVNode writeNode, String addr, String lineTabs) {
    String earlyDirtyName = getEarlyDirtyName(regfile);
    if (earlyDirtyName == null)
      return "";
    // All hazards handled by WrRerunNext plus stalling from the general dirty reg
    if (isForwardedEarlyWrite(regfile, writeNode))
      return "";
    return lineTabs + String.format("%s%s <= 1;\n", earlyDirtyName, (regfile.depth > 1 ? String.format("[%s]", addr) : ""));
  }

  @Override
  String earlyReadForwardLogic(NodeRegistryRO registry, RegfileInfo regfile, NodeInstanceDesc.Key earlyReadKey, String rdata_outLogic,
                               String raddr, String stallOutLogic, String lateDirtyRegName,
                               String lineTabs, NodeLogicBlock logicBlock) {
    //- Each early read gets a forward data register with a valid bit (and address, if needed)
    //- Each write starting in issue/later sets WrRerunNext, which the read stage will see as a flush and clear the 'forward valid' bit
    //- If an early write has an unclear ordering relationship to an early read, it is marked as non-forwardable (-> isForwardedEarlyWrite)
    //- If an early write is after any early read, it is also marked as non-forwardable (-> isForwardedEarlyWrite)
    //- Otherwise, each early write running through an early read stage updates the early read's forward register, given it is not being flushed
    List<SCAIEVNode> forwardedEarlyWriteNodes = regfile.earlyWrites.stream()
                                                       .filter(earlyWriteKey -> isForwardedEarlyWrite(regfile, earlyWriteKey.getNode())
                                                               && new PipelineFront(earlyWriteKey.getStage())
                                                                  .isAroundOrBefore(earlyReadKey.getStage(), false))
                                                       .map(earlyWriteKey -> earlyWriteKey.getNode())
                                                       .toList();
    //Each early write should be unique
    assert(forwardedEarlyWriteNodes.size() == forwardedEarlyWriteNodes.stream().distinct().count());
    if (forwardedEarlyWriteNodes.size() > 0) {
      String tab = language.tab;
      // Forward buffer management: Create a buffer of available forwards
      if (regfile.depth > 1) {
        logger.error("EarlyRWSimpleForwardingImplementation currently requires a register file depth of 1");
        //Note: This is solvable. Example:
        // - stall the read stage if a valid forward entry would need to be evicted
        // - clear the corresponding 'valid forward' entry when a forwardable write is committed or cancelled
        // - still clear the forward buffers on flush of the read stage
      }
      //Observe all stages between read and issue.
      List<PipelineStage> observeStages = earlyReadKey.getStage().streamNext_bfs(successorStage -> !regfile.issueFront.contains(successorStage))
                                              .skip(1).distinct().toList();
      //Check if there is any (early) write pending for the current node.
      String anyEarlyWriteCond = "";
      // (extra condition that only is set if a pending write is being flushed)
      String anyEarlyWriteMayGetFlushedCond = "";
      for (SCAIEVNode earlyWriteToForward : forwardedEarlyWriteNodes) {
        SCAIEVNode earlyWriteValidReq = bNodes.GetAdjSCAIEVNode(earlyWriteToForward, AdjacentNode.validReq).get();
        for (PipelineStage observeStage : observeStages) {
          var allValidsInStageKey = new NodeInstanceDesc.Key(NodeRegPipelineStrategy.Purpose_Getall_ToPipeTo, earlyWriteValidReq, observeStage, "");
          var allValidsNodeInst = registry.lookupRequired(allValidsInStageKey);
          if (allValidsNodeInst.getExpression().startsWith(NodeRegistry.MISSING_PREFIX))
            continue;
          String anyInStageValidCond = allValidsNodeInst.getExpressionWithParens();
          if (allValidsNodeInst.getKey().getNode().elements > 1)
            anyInStageValidCond = "(|%s)".formatted(anyInStageValidCond);
          anyEarlyWriteCond += (anyEarlyWriteCond.isEmpty() ? "" : " || ") + anyInStageValidCond;
          String destStageFlushingCond = SCALUtil.buildCond_StageFlushing(bNodes, registry, observeStage);
          anyEarlyWriteMayGetFlushedCond += (anyEarlyWriteMayGetFlushedCond.isEmpty() ? "" : " || ")
                                            + "%s && (%s)".formatted(anyInStageValidCond, destStageFlushingCond);
        }
      }
      if (anyEarlyWriteCond.isEmpty())
        anyEarlyWriteCond = "1'b0";
      //Also check if there is any post-issue write pending while the forwarder is empty.
      //-> The combination of the 'early write' and 'post-issue write' checks, applied after an early-stage flush,
      //   should cover all 'inconsistent forwarding buffer (after flush)' cases.
      String anyLateWriteCond = ((regfile.depth > 1) ? "|" : "") + lateDirtyRegName;

      //Simple implementation with just a single forward buffer entry.
      String forwardNameBase = String.format("%s_%s_earlyfwd", regfile.regName, earlyReadKey.getStage().getName());
      String forwardValidName = forwardNameBase + "_valid_r";
      String forwardAddrName = forwardNameBase + "_addr_r";
      String forwardBufferName = forwardNameBase + "_data_r";
      String forwardWasFlushedName = forwardNameBase + "_wasFlushed_r";

      logicBlock.declarations += String.format("logic %s;\n", forwardValidName);
      if (regfile.depth > 1)
        logicBlock.declarations += String.format("logic [$clog2(%d)-1:0] %s;\n", regfile.depth, forwardAddrName);
      logicBlock.declarations += String.format("logic [%d-1:0] %s;\n", regfile.width, forwardBufferName);
      logicBlock.declarations += String.format("logic %s;\n", forwardWasFlushedName);
      String bufferLogic = String.format("""
          always_ff @(posedge %1$s) begin
              if (%2$s) begin
                  %3$s <= 1'b0;%4$s
                  %5$s <= '0;
                  %6$s <= 1'b0;
              end
              else begin
          """, language.clk, language.reset, forwardValidName, //1,2,3
               (regfile.depth <= 1 ? "" : String.format("\n        %s <= '0;\n", forwardAddrName)), //4
               forwardBufferName, forwardWasFlushedName); //5,6,7

      String readstageNotStallingCond = SCALUtil.buildCond_StageNotStalling(bNodes, registry, earlyReadKey.getStage(), false);
      bufferLogic += """
                  if (%s) begin
          """.formatted(readstageNotStallingCond);
      
      for (SCAIEVNode earlyWriteToForward : forwardedEarlyWriteNodes) {
        var writeKey = new NodeInstanceDesc.Key(earlyWriteToForward, earlyReadKey.getStage(), "");
        var writeValidKey = new NodeInstanceDesc.Key(bNodes.GetAdjSCAIEVNode(earlyWriteToForward, AdjacentNode.validReq).get(), earlyReadKey.getStage(), "");
        var writeAddrKey = (regfile.depth <= 1 ? null
                            : new NodeInstanceDesc.Key(bNodes.GetAdjSCAIEVNode(earlyWriteToForward, AdjacentNode.addr).get(), earlyReadKey.getStage(), ""));
        bufferLogic += """
                        if (%1$s) begin
                            %3$s <= 1'b1;%4$s
                            %5$s <= %2$s;
                        end
            """.formatted(registry.lookupExpressionRequired(writeValidKey), registry.lookupExpressionRequired(writeKey), //1,2
                          forwardValidName,  //3
                          (regfile.depth <= 1 ? ""
                           : String.format("\n                %s <= %s;\n", forwardAddrName, registry.lookupExpressionRequired(writeAddrKey))), //4
                          forwardBufferName); //5
      }

      if (anyEarlyWriteMayGetFlushedCond.isEmpty())
        anyEarlyWriteMayGetFlushedCond = anyEarlyWriteCond;
      String flushReadCond = SCALUtil.buildCond_StageFlushing(bNodes, registry, earlyReadKey.getStage());
      //Note: May optimize this condition a bit further:
      // - invalidate if an early write is being flushed, see anyEarlyWriteMayGetFlushedCond
      //   (also covered by flushReadCond)
      // - invalidate if a late write from another port (i.e., not the early write port) starts
      //   (also covered by flushReadCond)
      // - invalidate if an early write is being late-discarded
      //   (also covered by flushReadCond, since each late-discard comes with a branch/exception flush)
      // - However, don't invalidate if the core flushes on its own (e.g. CVA6 fetch replay) without killing a pending write
      //   (flushReadCond also triggers in this case)
      bufferLogic += """
                  end
                  if (%1$s) begin
                      %2$s <= 1'b0;
                      %3$s <= (%5$s || %6$s);
                  end
                  else begin
                      %3$s <= %3$s && (%4$s || %6$s);
                  end
              end
          end
          """.formatted(flushReadCond, forwardValidName, forwardWasFlushedName, //1,2,3
                        anyEarlyWriteCond, anyEarlyWriteMayGetFlushedCond, anyLateWriteCond); //4,5,6
      logicBlock.logic += bufferLogic;
      String addrMatchCond = (regfile.depth <= 1 ? "" : " && %s == %s".formatted(raddr, forwardAddrName));
      return lineTabs + "if (%s%s) %s = %s;\n".formatted(forwardValidName, addrMatchCond, rdata_outLogic, forwardBufferName)
           + lineTabs + "if (%s) %s = 1'b1;\n".formatted(forwardWasFlushedName, stallOutLogic);
    }
    return "";
  }
  @Override
  NodeLogicBlock issueWriteToEarlyReadHazard(NodeRegistryRO registry, RegfileInfo regfile, int auxRerun) {
    NodeLogicBlock ret = super.issueWriteToEarlyReadHazard(registry, regfile, auxRerun);
    String earlyDirtyName = getEarlyDirtyName(regfile);
    if (earlyDirtyName != null)
      ret.declarations += String.format("logic [%d-1:0] %s;\n", regfile.depth, getEarlyDirtyName(regfile));
    return ret;
  }
}
