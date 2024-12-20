package scaiev.scal.strategy.standard;

import java.util.Map;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.BNode;
import scaiev.frontend.SCAIEVNode;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Key;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.strategy.SingleNodeStrategy;
import scaiev.util.Verilog;

/** Strategy that combines all nodes tagged with a non-empty ISAX / non-zero aux value to global WrStall and WrFlush nodes. */
public class SCALInputOutputStrategy extends SingleNodeStrategy {
  // logging
  protected static final Logger logger = LogManager.getLogger();

  Verilog language;

  /**
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   */
  public SCALInputOutputStrategy(Verilog language, BNode bNodes) { this.language = language; }

  @Override
  public Optional<NodeLogicBuilder> implement(Key nodeKey) {
    boolean isOutputPort = nodeKey.getPurpose().equals(Purpose.MARKER_TOCORE_PIN) || nodeKey.getPurpose().equals(Purpose.MARKER_TOISAX_PIN);
    boolean isInputPort =
        nodeKey.getPurpose().equals(Purpose.MARKER_FROMCORE_PIN) || nodeKey.getPurpose().equals(Purpose.MARKER_FROMISAX_PIN);

    if (isOutputPort || isInputPort) { // should be a MODULEOUTPUT node
      SCAIEVNode outnode_correct_polarity =
          (isInputPort == nodeKey.getNode().isInput) ? nodeKey.getNode() : nodeKey.getNode().NodeNegInput();
      Key outNodeKey = new Key(NodeInstanceDesc.Purpose.WIREDIN, outnode_correct_polarity, nodeKey.getStage(), nodeKey.getISAX(), 0);

      var markerRequestedFor = new RequestedForSet();
      var pinRequestedFor = new RequestedForSet(nodeKey.getISAX());
      pinRequestedFor.addAll(markerRequestedFor, true);

      return Optional.of(NodeLogicBuilder.fromFunction("SCALInputOutputStrategy (" + nodeKey.toString() + ")", registry -> {
        NodeLogicBlock retBlock = new NodeLogicBlock();

        // only create IO if it does not exist yet
        if (registry.lookupOptional(nodeKey).isEmpty()) {

          // check whether the signal name should contain an ISAX
          boolean isISAX =
              nodeKey.getPurpose().equals(Purpose.MARKER_TOISAX_PIN) || nodeKey.getPurpose().equals(Purpose.MARKER_FROMISAX_PIN);
          String guardedISAX = nodeKey.getISAX(); // isISAX ? (nodeKey.getNode().oneInterfToISAX ? "" : nodeKey.getISAX()) : "";

          // Prevent possible collision between to-ISAX and to-core pins (e.g. for RdIValid).
          String pinNamePrefix = (nodeKey.getISAX().isEmpty() || isISAX) ? "" : "core_";

          // create interface type and pin name
          String newinterf = language.CreateTextInterface(pinNamePrefix + nodeKey.getNode().name, nodeKey.getStage(), guardedISAX,
                                                          isInputPort, nodeKey.getNode().size, "");
          String pinName = pinNamePrefix + language.CreateFamNodeName(outnode_correct_polarity, nodeKey.getStage(), guardedISAX, false);
          // create a pin datatype to describe the iface pin
          NodeLogicBlock.InterfacePin pin = new NodeLogicBlock.InterfacePin(

              newinterf, new NodeInstanceDesc(outNodeKey, pinName, isOutputPort ? ExpressionType.ModuleOutput : ExpressionType.ModuleInput,
                                              pinRequestedFor));

          // add interface to SCAL
          retBlock.interfPins.add(Map.entry(isISAX ? NodeLogicBlock.InterfToISAXKey : NodeLogicBlock.InterfToCoreKey, pin));
          retBlock.outputs.add(pin.nodeDesc); // Note: will not be used for logic generation if isOutputPort

          var markerNodeInst = new NodeInstanceDesc(new NodeInstanceDesc.Key(nodeKey.getPurpose(), outnode_correct_polarity,
                                                                             nodeKey.getStage(), nodeKey.getISAX(), nodeKey.getAux()),
                                                    "1", ExpressionType.AnyExpression, markerRequestedFor);

          // Add a marker pseudo-output that NodeLogicBuilders can lookup to determine if an output exists.
          retBlock.outputs.add(markerNodeInst);

          if (isOutputPort) {
            var assignNodeInst = registry.lookupRequired(
                new NodeInstanceDesc.Key(Purpose.match_WIREOUT_OR_default, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX()),
                pin.nodeDesc.getRequestedFor());
            pin.nodeDesc.addRequestedFor(assignNodeInst.getRequestedFor(), true);
            retBlock.logic = "assign " + pin.nodeDesc.getExpression() + " = " + assignNodeInst.getExpression() + ";";
          }
        }
        return retBlock;
      }));
    }
    return Optional.empty();
  }
}
