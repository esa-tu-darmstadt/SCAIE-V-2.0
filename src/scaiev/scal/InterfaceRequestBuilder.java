package scaiev.scal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;

/**
 * A NodeLogicBuilder that triggers creation of a module pin.
 */
public class InterfaceRequestBuilder extends NodeLogicBuilder {

  private static final NodeInstanceDesc.Purpose AnyIOMarkerPurpose = new Purpose(
      "AnyIOMarkerPurpose", false, Optional.empty(),
      List.of(Map.entry(Purpose.MARKER_FROMCORE_PIN, 1), Map.entry(Purpose.MARKER_FROMISAX_PIN, 1), Map.entry(Purpose.MARKER_TOCORE_PIN, 1),
              Map.entry(Purpose.MARKER_TOISAX_PIN, 1), Map.entry(Purpose.MARKER_INTERNALIMPL_PIN, 1)));

  NodeInstanceDesc.Purpose markerPurpose;
  NodeInstanceDesc.Key nodeKey;
  /**
   * @param markerPurpose one of the supported IO marker purposes, see {@link
   *     scaiev.scal.NodeInstanceDesc.Purpose}.MARKER_(FROM|TO)(CORE|ISAX)_PIN and MARKER_INTERNALIMPL_PIN.
   * @param nodeKey the node key (purpose is ignored)
   */
  public InterfaceRequestBuilder(NodeInstanceDesc.Purpose markerPurpose, NodeInstanceDesc.Key nodeKey) {
    super("InterfaceRequestBuilder_" + markerPurpose.getName() + "_" + nodeKey.toString(false));
    if (!AnyIOMarkerPurpose.matches(markerPurpose))
      throw new IllegalArgumentException("Requires a supported IO marker Purpose");
    this.markerPurpose = markerPurpose;
    this.nodeKey = nodeKey;
  }

  /** A 'requestedFor' set that will be lazy-added to the interface port marker node. */
  public RequestedForSet requestedFor = new RequestedForSet();

  /**
   * Apply the InterfaceRequestBuilder, but with the third parameter specifying if the interface pin is a required or an optional
   * dependency.
   */
  public NodeLogicBlock applyOptional(NodeRegistryRO registry, int aux, boolean optional) {
    // explicitly request output
    var lookupMarkerKey = NodeInstanceDesc.Key.keyWithPurposeAux(nodeKey, markerPurpose, 0);
    Optional<NodeInstanceDesc> markerInst_opt =
        optional ? registry.lookupOptionalUnique(nodeKey) : Optional.of(registry.lookupRequired(lookupMarkerKey));
    var ret = new NodeLogicBlock();
    if (markerInst_opt.isPresent()) {
      markerInst_opt.get().addRequestedFor(requestedFor, true);
    }
    return ret;
  }
  @Override
  public NodeLogicBlock apply(NodeRegistryRO registry, int aux) {
    return applyOptional(registry, aux, false);
  }
}
