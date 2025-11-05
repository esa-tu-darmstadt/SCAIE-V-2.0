package scaiev.frontend;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import scaiev.frontend.SCAIEVNode.AdjacentNode;

public class Scheduled {
  private int startCycle = 0;
  private HashSet<AdjacentNode> adjSignals = new HashSet<AdjacentNode>();
  private HashMap<AdjacentNode, Integer> constAdj = new HashMap<AdjacentNode, Integer>();
  EnumSet<ScheduledNodeTag> tags = EnumSet.noneOf(ScheduledNodeTag.class);

  public enum ScheduledNodeTag {
    /**
     * Disable read forwarding for a custom register read.
     * Note: May still perform forwarding if the read port is shared with a scheduled node that has forwarding enabled.
     */
    Custreg_DisableReadForwarding("Custreg_DisableReadForwarding");

    public final String serialName;

    private ScheduledNodeTag(String serialName) { this.serialName = serialName; }
    public static Optional<ScheduledNodeTag> fromSerialName(String serialName) {
      return Stream.of(ScheduledNodeTag.values()).filter(tagVal -> tagVal.serialName.equals(serialName)).findAny();
    }
  }

  public Scheduled(int startCycle, HashSet<AdjacentNode> adjSignals, HashMap<AdjacentNode, Integer> constAdj) { // for CSR
    this.startCycle = startCycle;
    this.adjSignals = adjSignals;
    this.constAdj = constAdj;
  }

  public Scheduled() {
    // TODO Auto-generated constructor stub
  }

  public int GetStartCycle() { return startCycle; }

  public void UpdateStartCycle(int newStartCycle) { startCycle = newStartCycle; }

  /**
   * Function to check whether node has adjacent signals, like address, valid request, valid response
   * @param adjNode
   * @return
   */
  public boolean HasAdjSig(AdjacentNode adjNode) { return adjNode == AdjacentNode.none || adjSignals.contains(adjNode); }

  /**
   * Function to add adjacent signal to scheduled node
   * @param adjNode
   * @return
   */
  public boolean AddAdjSig(AdjacentNode adjNode) {
    if (adjNode != AdjacentNode.none)
      return adjSignals.add(adjNode);
    return false;
  }

  /**
   * Returns const values for adjacent signals. For example to be used by CSRs. -1 means no constant value
   * @param adjNode
   * @return
   */
  public int GetConstAdjSig(AdjacentNode adjNode) {
    if (constAdj.containsKey(adjNode))
      return constAdj.get(adjNode);
    return -1;
  }

  public void AddTag(ScheduledNodeTag tag) { tags.add(tag); }
  public Set<ScheduledNodeTag> GetTags() { return Collections.unmodifiableSet(tags); }

  @Override
  public String toString() {
    return String.format("Scheduled node, start_cycle: %d has_address: %d has_validReq: %d has_validResp: %d%s",
                         this.startCycle,
                         this.adjSignals.contains(AdjacentNode.addr)?1:0,
                         this.adjSignals.contains(AdjacentNode.validReq)?1:0,
                         this.adjSignals.contains(AdjacentNode.validResp)?1:0,
                         tags.isEmpty()?"":(" tags: " + tags.stream().map(t->t.serialName).reduce((a,b)->a+", "+b).orElse("")));
  }
}
