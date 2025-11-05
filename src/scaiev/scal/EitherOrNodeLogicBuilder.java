package scaiev.scal;

/**
 * NodeLogicBuilder that tries the first, then the second inner builder.
 *  Note: Calls the inner builder with an empty NodeLogicBlock, regardless of the block passed to this.
 *  Example: Choose whether to build a pipeline register for a node,
 *           or to build the node from scratch in the stage.
 */
public class EitherOrNodeLogicBuilder extends NodeLogicBuilder {

  NodeLogicBuilder[] inner;

  /**
   * @param name a short, descriptive name for the builder
   * @param inner the inner builders; the first one that returns
   *              some logic will be used in {@link #apply(NodeRegistryRO, int)}
   */
  public EitherOrNodeLogicBuilder(String name, NodeLogicBuilder... inner) {
    super(name);
    this.inner = inner;
  }

  @Override
  public NodeLogicBlock apply(NodeRegistryRO registry, int aux) {
    NodeLogicBlock logicBlock = new NodeLogicBlock();
    // If still empty, apply builders from the 'others' array.
    for (int i = 0; logicBlock.isEmpty() && i < inner.length; ++i) {
      logicBlock = inner[i].apply(registry, aux);
    }
    return logicBlock;
  }
}
