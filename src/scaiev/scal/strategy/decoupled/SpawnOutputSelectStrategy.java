package scaiev.scal.strategy.decoupled;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.frontend.SCAIEVNode.NodeTypeTag;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.util.Verilog;

/**
 * Strategy that selects to-core / to-ISAX output values for spawn nodes, taking into account fire results and priority.
 * Note: Makes assumptions on the SpawnFireStrategy implementation for selection conditions.
 */
public class SpawnOutputSelectStrategy extends MultiNodeStrategy {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  Verilog language;
  BNode bNodes;
  Core core;
  HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr;
  Map<SCAIEVNode, Collection<String>> isaxesSortedByPriority;
  /**
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param isaxesSortedByPriority For each spawn node, an ISAX name collection sorted by priority
   */
  public SpawnOutputSelectStrategy(Verilog language, BNode bNodes, Core core,
                                   HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                                   Map<SCAIEVNode, Collection<String>> isaxesSortedByPriority) {
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
    this.op_stage_instr = op_stage_instr;
    this.isaxesSortedByPriority = isaxesSortedByPriority;
  }

  @Override
  public void setLanguage(Verilog lang) {
    this.language = lang;
  }

  private void LogicToCoreSpawn(NodeLogicBlock logicBlock, NodeRegistryRO registry, RequestedForSet requestedFor, SCAIEVNode node,
                                PipelineStage stage) {
    StringBuilder body = new StringBuilder();
    SCAIEVNode mainNode = (node.isAdj() ? bNodes.GetSCAIEVNode(node.nameParentNode) : node);
    SCAIEVNode nodeToCore = node.makeFamilyNode();
    String toCoreSignalName = language.CreateLocalNodeName(nodeToCore, stage, "");

    StringBuilder priority = new StringBuilder();
    SpawnFireStrategy
        .getPriorityValidReqKeysStream(Purpose.REGISTERED, bNodes, op_stage_instr, stage,
                                       SpawnFireStrategy.getISAXPriority(bNodes, isaxesSortedByPriority, mainNode))
        .forEachOrdered(curValidReqKeys -> {
          String curValidReq =
              curValidReqKeys.stream().map(key -> registry.lookupExpressionRequired(key)).reduce((a, b) -> a + " || " + b).orElse("1'b0");
          requestedFor.addRelevantISAX(curValidReqKeys.get(0).getISAX());
          String align = "";
          if (!priority.isEmpty()) {
            body.append("if (!(" + priority.toString() + "))\n");
            align = language.tab;
          }
          SCAIEVNode selectedMainNode = bNodes.GetSCAIEVNode(curValidReqKeys.get(0).getNode().nameParentNode);
          Optional<SCAIEVNode> selectedNode =
              node.isAdj() ? bNodes.GetAdjSCAIEVNode(selectedMainNode, node.getAdj()) : Optional.of(selectedMainNode);
          if (selectedNode.isPresent()) {
            assert (selectedNode.get().getAdj() == node.getAdj());
            if (selectedNode.get().getAdj() == AdjacentNode.isWrite) {
              body.append(String.format("%s%s = %s;\n", align, toCoreSignalName, (selectedMainNode.isInput ? "1" : "0")));
            } else if (node.canRepresentAsFamily() || selectedNode.get().equals(node)) {
              assert (selectedNode.get().getAdj() != AdjacentNode.validReq);
              String curNodeKey = registry.lookupExpressionRequired(
                  new NodeInstanceDesc.Key(Purpose.REGISTERED, selectedNode.get(), stage, curValidReqKeys.get(0).getISAX()));
              body.append(String.format("%s%s = %s;\n", align, toCoreSignalName, curNodeKey));
            } else {
              body.append(String.format("%s%s = 0;\n", align, toCoreSignalName));
            }
          }
          priority.append((priority.isEmpty() ? "" : " || ") + curValidReq);
        });
    if (!body.isEmpty()) {
      logicBlock.logic += language.CreateInAlways(false, body.toString());
      logicBlock.declarations += (nodeToCore.size > 0) ? String.format("reg [%d-1:0] %s;\n", nodeToCore.size, toCoreSignalName)
                                                       : String.format("reg %s;\n", toCoreSignalName);
      logicBlock.outputs.add(
          new NodeInstanceDesc(new NodeInstanceDesc.Key(nodeToCore, stage, ""), toCoreSignalName, ExpressionType.WireName, requestedFor));
    }
  }

  private void LogicToISAXSpawnAdj(NodeLogicBlock logicBlock, NodeRegistryRO registry, SCAIEVNode node, PipelineStage stage, String ISAX) {
    SCAIEVNode mainNode = (node.isAdj() ? bNodes.GetSCAIEVNode(node.nameParentNode) : node);
    var outputKey = new NodeInstanceDesc.Key(node, stage, ISAX);
    String toISAXSignalName = outputKey.toString(false) + "_s";

    // Produce the selection condition for higher-priority nodes/ISAXes.
    String priority =
        SpawnFireStrategy
            .getPriorityValidReqKeysStream(Purpose.REGISTERED, bNodes, op_stage_instr, stage,
                                           SpawnFireStrategy.getISAXPriority(bNodes, isaxesSortedByPriority, mainNode)
                                               .takeWhile(entry -> !(entry.getKey().equals(mainNode) && entry.getValue().equals(ISAX))))
            .flatMap(
                keys -> keys.stream()) // Since we're ORing all together anyway, there is no need to keep the condition key lists intact.
            .map(key -> registry.lookupExpressionRequired(key))
            .reduce("1'b0", (a, b) -> a + " || " + b);

    // Assign the result validity condition for the ISAX.
    // Example: -> Result `RdMem_spawn_ISAX` will be marked as valid for ISAX by `RdMem_spawn_validResp_ISAX == 1`.
    logicBlock.logic += String.format("assign %s = %s && !(%s) && %s && %s;\n", toISAXSignalName,
                                      registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
                                          SpawnFireStrategy.ISAX_fire2_r, stage, SpawnFireStrategy.getFireNodeSuffix(node))),
                                      priority,
                                      registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
                                          Purpose.REGISTERED, bNodes.GetAdjSCAIEVNode(mainNode, AdjacentNode.validReq).get(), stage, ISAX)),
                                      registry.lookupExpressionRequired(new NodeInstanceDesc.Key(node.makeFamilyNode(), stage, "")));

    logicBlock.declarations += String.format("wire %s;\n", toISAXSignalName);
    logicBlock.outputs.add(new NodeInstanceDesc(outputKey, toISAXSignalName, ExpressionType.WireName));
  }

  private HashSet<String> builtToCoreNodeNameSet = new HashSet<String>();
  private HashSet<String> builtToISAXNodeNameSet = new HashSet<>();

  @Override
  public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    Iterator<NodeInstanceDesc.Key> keyIter = nodeKeys.iterator();
    while (keyIter.hasNext()) {
      NodeInstanceDesc.Key nodeKey = keyIter.next();
      if (!nodeKey.getPurpose().matches(Purpose.REGULAR) || !nodeKey.getNode().isSpawn() ||
          !(nodeKey.getStage().getKind() == StageKind.Decoupled || nodeKey.getStage().getKind() == StageKind.Core) || nodeKey.getAux() != 0)
        continue;
      if (nodeKey.getISAX().isEmpty() && nodeKey.getNode().isInput) {
        SCAIEVNode familyNode = nodeKey.getNode().makeFamilyNode();
        String builderIdentifier = String.format("%s_%s", familyNode.name, nodeKey.getStage().getName());
        //-> to core
        if (builtToCoreNodeNameSet.add(builderIdentifier)) {
          var requestedFor = new RequestedForSet();
          out.accept(NodeLogicBuilder.fromFunction("SpawnOutputSelectStrategy_toCore_" + builderIdentifier, registry -> {
            NodeLogicBlock ret = new NodeLogicBlock();
            LogicToCoreSpawn(ret, registry, requestedFor, familyNode, nodeKey.getStage());
            return ret;
          }));
        }
        keyIter.remove();
      } else if (nodeKey.getISAX().isEmpty() && !nodeKey.getNode().isInput && nodeKey.getStage().getKind() != StageKind.Decoupled &&
                 !nodeKey.getNode().equals(bNodes.ISAX_spawnAllowed) // special case exclusion
                 &&
                 bNodes.GetEquivalentNonspawnNode(nodeKey.getNode())
                     .map(nonspawnNode
                          -> !nonspawnNode.tags.contains(NodeTypeTag.defaultNotprovidedByCore) || core.GetNodes().containsKey(nonspawnNode))
                     .orElse(false)) {
        // Create the spawn node directly from the non-spawn equivalent,
        //  e.g. RdMem -> RdMem_spawn, *_validResp -> *_spawn_validResp (if non-spawn validResp is explicitly present in the core)
        SCAIEVNode nodeNonspawnAdj = bNodes.GetEquivalentNonspawnNode(nodeKey.getNode()).orElseThrow();

        String builderIdentifier = String.format("%s_%s", nodeKey.getNode().name, nodeKey.getStage().getName());
        if (builtToCoreNodeNameSet.add(builderIdentifier)) {
          var requestedFor = new RequestedForSet();
          out.accept(NodeLogicBuilder.fromFunction("SpawnOutputSelectStrategy_nonspawnToSpawn_" + builderIdentifier, registry -> {
            NodeLogicBlock ret = new NodeLogicBlock();

            NodeInstanceDesc nodespawnNodeInst =
                registry.lookupRequired(new NodeInstanceDesc.Key(nodeNonspawnAdj, nodeKey.getStage(), ""), requestedFor);
            requestedFor.addAll(nodespawnNodeInst.getRequestedFor(), true);

            String wireName = nodeKey.toString(false) + "_s";
            int nodeSize = nodespawnNodeInst.getKey().getNode().size;
            if (nodeSize > 1)
              ret.declarations += String.format("wire [%d-1:0] %s;\n", nodeSize, wireName);
            else
              ret.declarations += String.format("wire %s;\n", wireName);
            ret.logic += String.format("assign %s = %s;\n", wireName, nodespawnNodeInst.getExpression());
            ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.REGULAR), wireName,
                                                 ExpressionType.WireName, requestedFor));

            return ret;
          }));
        }
        keyIter.remove();
      } else if (!nodeKey.getISAX().isEmpty() && !nodeKey.getNode().isInput) {
        //-> to ISAX
        // Do not build the same pin twice (using full name, as the ISAX does not care about node families)
        String builderIdentifier = String.format("%s_%s_%s", nodeKey.getNode().name, nodeKey.getISAX(), nodeKey.getStage().getName());
        if (builtToISAXNodeNameSet.add(builderIdentifier)) {
          out.accept(NodeLogicBuilder.fromFunction("SpawnOutputSelectStrategy_toISAX_" + builderIdentifier, registry -> {
            NodeLogicBlock ret = new NodeLogicBlock();
            if (nodeKey.getNode().DefaultMandatoryAdjSig()) {
              if (nodeKey.getNode().size != 1) {
                logger.error("SpawnOutputSelectStrategy: Encountered node " + nodeKey.getNode().name + " with size " +
                             nodeKey.getNode().size + " (expected size 1).");
                return ret;
              }
              if (nodeKey.getNode().getAdj() != AdjacentNode.validResp) {
                // Placed this check, since it appears likely that if a new 'validResp'-like pin was added, this logic would not fit.
                logger.error("SpawnOutputSelectStrategy: Encountered 'mandatory adj' node " + nodeKey.getNode().name +
                             " (not supported yet).");
                return ret;
              }
              LogicToISAXSpawnAdj(ret, registry, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX());
            } else {
              // Assign any SCAL->ISAX pin directly from the general Core->SCAL pin,
              //  aside from validResp-style nodes (handled above).
              //-> The core generally has a single SCAIE-V result port (for now).
              //    The ISAX knows when that data is valid via validResp (-> DefaultMandatoryAdjSig()).
              ret.outputs.add(new NodeInstanceDesc(
                  new NodeInstanceDesc.Key(nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX()),
                  registry.lookupExpressionRequired(new NodeInstanceDesc.Key(nodeKey.getNode(), nodeKey.getStage(), "")),
                  ExpressionType.AnyExpression));
            }
            return ret;
          }));
        }
        keyIter.remove();
      }
    }
  }
}
