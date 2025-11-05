package scaiev.scal.strategy.state;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.pipeline.PipelineStage;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.strategy.StrategyBuilders;
import scaiev.scal.strategy.state.SCALStateStrategy.RegfileInfo;
import scaiev.ui.SCAIEVConfig;
import scaiev.util.Verilog;

/**
 * Implementation logic for the early stage portions of an early SCALStateStrategy read/write.
 * (From the issue stage onwards, the writes behave like a regular port and are handled in the main part of the implementation.)
 */
public class EarlyRWImplementation {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  protected BNode bNodes;
  protected SCALStateStrategy stateStrategy;
  protected Verilog language;
  protected Core core;

  /**
   * @param stateStrategy The state strategy to build early RW on
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   */
  public EarlyRWImplementation(SCALStateStrategy stateStrategy, Verilog language, BNode bNodes, Core core) {
    this.stateStrategy = stateStrategy;
    this.bNodes = bNodes;
    this.language = language;
    this.core = core;
  }
  
  String getEarlyDirtyName(RegfileInfo regfile) { return null; }
  /**
   * Returns whether a write issue has to cause a flush and rerun of the following instructions.
   */
  boolean rerunPreissueOn(RegfileInfo regfile, SCAIEVNode writeNode) { return true; }
  /**
   * Returns the FF logic to reset the 'dirty for early read' flag,
   * or an empty string if the group of the write node is already being forwarded or handled another way.
   * @param writeNode the write node that completed, or null if all entries are to be reset
   * @param addr the address expression to clear, or null to reset all entries
   */
  String earlyDirtyFFResetLogic(NodeRegistryRO registry, RegfileInfo regfile, SCAIEVNode writeNode, String addr, String lineTabs) {
    // All hazards handled by WrRerunNext plus stalling from the general dirty reg
    return "";
  }
  /**
   * Returns the FF logic to set the 'dirty for early read' flag,
   * or an empty string if the group of the write node is already being forwarded or handled another way.
   */
  String earlyDirtyFFSetLogic(NodeRegistryRO registry, RegfileInfo regfile, SCAIEVNode writeNode, String addr, String lineTabs) {
    // All hazards handled by WrRerunNext plus stalling from the general dirty reg
    return "";
  }
  /**
   * Returns the (comb) logic for forwarding to an early read.
   * Can set rdata_outLogic and stallOutLogic with blocking assignments.
   */
  String earlyReadForwardLogic(NodeRegistryRO registry, RegfileInfo regfile, NodeInstanceDesc.Key earlyReadKey, String rdata_outLogic,
                               String raddr, String stallOutLogic, String lateDirtyRegName,
                               String lineTabs, NodeLogicBlock logicBlock) {
    return "";
  }
  NodeLogicBlock earlyRead(NodeRegistryRO registry, RegfileInfo regfile, List<Integer> auxReads, String regfilePin_rdata,
                           String regfilePin_re, String regfilePin_raddr, String dirtyRegName) {
    String earlyDirtyRegName = getEarlyDirtyName(regfile);
    if (earlyDirtyRegName == null)
      earlyDirtyRegName = dirtyRegName;
    String tab = language.tab;
    int elements = regfile.depth;
    NodeLogicBlock ret = new NodeLogicBlock();
    for (int iEarlyRead = 0; iEarlyRead < regfile.earlyReads.size(); ++iEarlyRead) {
      int readPortn = regfile.issue_reads.size() + iEarlyRead;
      if (auxReads.size() <= readPortn)
        auxReads.add(registry.newUniqueAux());
      int auxRead = auxReads.get(readPortn);

      NodeInstanceDesc.Key requestedReadKey = regfile.earlyReads.get(iEarlyRead);
      PipelineStage earlyReadStage = requestedReadKey.getStage();

      String wireName_earlyRead = requestedReadKey.toString(false) + "_s";
      ret.declarations += String.format("logic [%d-1:0] %s;\n", regfile.width, wireName_earlyRead);
      ret.outputs.add(new NodeInstanceDesc(requestedReadKey, wireName_earlyRead, ExpressionType.WireName));

      String wireName_dhInEarlyStage = String.format("dhRd%s_%s_%d", regfile.regName, earlyReadStage.getName(), iEarlyRead);
      ret.declarations += String.format("logic %s;\n", wireName_dhInEarlyStage);

      String rdAddrExpr = (elements > 1) ? registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
                                               bNodes.GetAdjSCAIEVNode(requestedReadKey.getNode(), AdjacentNode.addr).orElseThrow(),
                                               earlyReadStage, requestedReadKey.getISAX()))
                                         : "0";
      String rdAddrValidExpr = registry.lookupExpressionRequired(
          new NodeInstanceDesc.Key(bNodes.GetAdjSCAIEVNode(requestedReadKey.getNode(), AdjacentNode.addrReq).orElseThrow(),
                                   earlyReadStage, requestedReadKey.getISAX()));

      String readLogic = "";
      readLogic += "always_comb begin\n";
      if (!regfile.writeback_writes.isEmpty()) {
        // Stall the early read stage if the requested register is marked dirty.
        assert (regfile.regularReads.stream().allMatch(regularKey -> regularKey.getStage() != earlyReadStage));
        readLogic += tab + String.format("%s = %s && %s[%s];\n", wireName_dhInEarlyStage, rdAddrValidExpr, earlyDirtyRegName, rdAddrExpr);
      } else
        readLogic += tab + String.format("%s = 0;\n", wireName_dhInEarlyStage);
      readLogic +=
          tab + String.format("%s = %s;\n", wireName_earlyRead, stateStrategy.makeRegModuleSignalName(regfile.regName, regfilePin_rdata, readPortn));
      readLogic += tab + String.format("%s = 1;\n", stateStrategy.makeRegModuleSignalName(regfile.regName, regfilePin_re, readPortn));
      if (elements > 1)
        readLogic += tab + String.format("%s = 'x;\n", stateStrategy.makeRegModuleSignalName(regfile.regName, regfilePin_raddr, readPortn));
      readLogic +=
          earlyReadForwardLogic(registry, regfile, requestedReadKey, wireName_earlyRead, rdAddrExpr, wireName_dhInEarlyStage, dirtyRegName, tab, ret);
      readLogic += "end\n";
      ret.logic += readLogic;

      ret.outputs.add(
          new NodeInstanceDesc(new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.REGULAR, bNodes.WrStall, earlyReadStage, "", auxRead),
                               wireName_dhInEarlyStage, ExpressionType.WireName));
      registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrStall, earlyReadStage, ""));
    }
    return ret;
  }
  NodeLogicBlock issueWriteToEarlyReadHazard(NodeRegistryRO registry, RegfileInfo regfile, int auxRerun) {
    NodeLogicBlock ret = new NodeLogicBlock();
    if (regfile.writeback_writes.size() > 0 && regfile.earlyReads.size() > 0) {
      // Issue WrRerunNext in an issue stage if it is announcing an (upcoming) write.
      // This flushes out any possible RaW hazards with early reads.
      // Note: If there are several issue stages (-> multi-issue core),
      //  the WrRerunNext implementation of the core may have to flush concurrent issues depending on the logical instruction ordering.
      for (PipelineStage issueStage : regfile.issueFront.asList()) {
        String anyWriteInitiatedExpr =
            regfile.writeback_writes.stream()
                .filter(commitWriteKey -> rerunPreissueOn(regfile, commitWriteKey.getNode()))
                .map(commitWriteKey -> {
                  SCAIEVNode commitWriteNode = commitWriteKey.getNode();
                  SCAIEVNode nonspawnWriteNode = bNodes.GetEquivalentNonspawnNode(commitWriteNode).orElse(commitWriteNode);
                  return registry
                      .lookupRequired(
                          new NodeInstanceDesc.Key(bNodes.GetAdjSCAIEVNode(nonspawnWriteNode, AdjacentNode.addrReq).orElseThrow(),
                                                   issueStage, commitWriteKey.getISAX()))
                      .getExpressionWithParens();
                })
                .reduce((a, b) -> a + " || " + b)
                .orElseThrow();

        String rerunNextWire = String.format("WrRerunNext_RegDH_%s_%s", regfile.regName, issueStage.getName());
        ret.declarations += String.format("logic %s;\n", rerunNextWire);
        ret.logic += String.format("assign %s = %s;\n", rerunNextWire, anyWriteInitiatedExpr);
        ret.outputs.add(
            new NodeInstanceDesc(new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.REGULAR, bNodes.WrRerunNext, issueStage, "", auxRerun),
                                 rerunNextWire, ExpressionType.WireName));
        registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrRerunNext, issueStage, ""));
      }
    }
    return ret;
  }
}
