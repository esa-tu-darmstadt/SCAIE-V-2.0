package scaiev.scal.strategy.decoupled;

import java.util.HashSet;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.InterfaceRequestBuilder;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Key;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.util.Verilog;

/** Dedicated strategy that implements the committed_rd_spawn nodes */
public class SpawnCommittedRdStrategy extends MultiNodeStrategy {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  Verilog language;
  BNode bNodes;
  Core core;
  /**
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   */
  public SpawnCommittedRdStrategy(Verilog language, BNode bNodes, Core core) {
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
  }

  @Override
  public void setLanguage(Verilog lang) {
    this.language = lang;
  }

  private HashSet<SCAIEVNode> implementedNodes = new HashSet<>();

  private boolean implementCommittedRdSpawn(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey) {
    if (!nodeKey.getPurpose().matches(Purpose.WIREDIN) || nodeKey.getStage().getKind() == StageKind.Sub ||
        nodeKey.getStage().getChildren().isEmpty() || nodeKey.getAux() != 0 || !nodeKey.getISAX().isEmpty())
      return false;
    if (nodeKey.getNode().name.startsWith(bNodes.committed_rd_spawn_valid.name + "_")) {
      // Note: This isn't beautiful.
      SCAIEVNode spawnNode = bNodes.GetSCAIEVNode(nodeKey.getNode().name.substring(bNodes.committed_rd_spawn_valid.name.length() + 1));

      // committed_rd_spawn is only used for WrRd (not Mem) -> should not have trouble finding a specific spawn node.
      assert (spawnNode != null);
      assert (spawnNode.isSpawn() && !spawnNode.isAdj());
      if (spawnNode == null || !spawnNode.isSpawn() || spawnNode.isAdj())
        return false;

      if (implementedNodes.add(nodeKey.getNode())) {
        // committed_rd_spawn_valid = ISAX_fire2_r for the given node
        out.accept(NodeLogicBuilder.fromFunction("SpawnCommittedRdStrategy_" + nodeKey.toString(), registry -> {
          var ret = new NodeLogicBlock();
          ret.outputs.add(
              new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.WIREDIN_FALLBACK, nodeKey.getNode(), nodeKey.getStage(), ""),
                                   registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
                                       SpawnFireStrategy.ISAX_fire2_r, nodeKey.getStage(), SpawnFireStrategy.getFireNodeSuffix(spawnNode))),
                                   ExpressionType.AnyExpression));
          return ret;
        }));
      }
      return true;
    } else if (nodeKey.getNode().name.startsWith(bNodes.committed_rd_spawn.name + "_")) {
      SCAIEVNode spawnNode = bNodes.GetSCAIEVNode(nodeKey.getNode().name.substring(bNodes.committed_rd_spawn.name.length() + 1));

      // committed_rd_spawn is only used for WrRd (not Mem) -> should not have trouble finding a specific spawn node.
      assert (spawnNode != null);
      assert (spawnNode.isSpawn() && !spawnNode.isAdj());
      if (spawnNode == null || !spawnNode.isSpawn() || spawnNode.isAdj())
        return false;

      if (implementedNodes.add(nodeKey.getNode())) {
        if (spawnNode.equals(bNodes.WrRD_spawn) && core.GetNodes().containsKey(bNodes.rd_dh_spawn_addr) &&
            (nodeKey.getStage().getKind() == StageKind.Decoupled ||
             core.StageIsInRange(core.GetNodes().get(bNodes.rd_dh_spawn_addr), nodeKey.getStage()))) {
          // Take from rd_dh_spawn_addr given by the core.
          if (bNodes.rd_dh_spawn_addr.size != 5) {
            logger.warn("rd_dh_spawn_addr is expected to be five bits wide");
          }
          out.accept(new InterfaceRequestBuilder(Purpose.MARKER_FROMCORE_PIN,
                                                 new NodeInstanceDesc.Key(bNodes.rd_dh_spawn_addr, nodeKey.getStage(), "")));
          out.accept(NodeLogicBuilder.fromFunction("SpawnCommittedRdStrategy_" + nodeKey.toString(), registry -> {
            var ret = new NodeLogicBlock();
            ret.outputs.add(new NodeInstanceDesc(
                new NodeInstanceDesc.Key(Purpose.WIREDIN_FALLBACK, nodeKey.getNode(), nodeKey.getStage(), ""),
                registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.rd_dh_spawn_addr, nodeKey.getStage(), "")),
                ExpressionType.AnyExpression));
            return ret;
          }));
        } else {
          Optional<SCAIEVNode> spawnAddrNode_opt = bNodes.GetAdjSCAIEVNode(spawnNode, AdjacentNode.addr);
          // Take from rd_dh_spawn_addr given by the core.
          if (!spawnAddrNode_opt.isPresent()) {
            logger.warn("SpawnCommittedRdStrategy: {} will default to 0 due to {} not having an adjacent addr node",
                        nodeKey.toString(false), spawnNode.name);
          } else if (spawnAddrNode_opt.get().size < 5) {
            logger.warn("SpawnCommittedRdStrategy: {} is narrower than expected (expected 5 bits, got {} bits)",
                        spawnAddrNode_opt.get().name, spawnAddrNode_opt.get().size);
          }
          out.accept(NodeLogicBuilder.fromFunction("SpawnCommittedRdStrategy_" + nodeKey.toString(), registry -> {
            // committed_rd_spawn_valid = general addr for the given node
            var ret = new NodeLogicBlock();
            String outputExpr = "0";
            if (spawnAddrNode_opt.isPresent()) {
              SCAIEVNode spawnAddrNode = spawnAddrNode_opt.get();
              outputExpr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(spawnAddrNode, nodeKey.getStage(), ""));
              if (spawnAddrNode.size > 5)
                outputExpr += "[5-1:0]";
            }
            ret.outputs.add(
                new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.WIREDIN_FALLBACK, nodeKey.getNode(), nodeKey.getStage(), ""),
                                     outputExpr, ExpressionType.AnyExpression));
            return ret;
          }));
        }
      }
      return true;
    }
    return false;
  }

  @Override
  public void implement(Consumer<NodeLogicBuilder> out, Iterable<Key> nodeKeys, boolean isLast) {
    var nodeKeyIter = nodeKeys.iterator();
    while (nodeKeyIter.hasNext()) {
      var nodeKey = nodeKeyIter.next();
      if (implementCommittedRdSpawn(out, nodeKey)) {
        nodeKeyIter.remove();
      }
    }
  }
}
