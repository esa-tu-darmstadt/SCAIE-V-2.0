package scaiev.frontend;

import java.util.HashMap;
import java.util.HashSet;
import scaiev.frontend.SCAIEVNode.AdjacentNode;

public class Scheduled {
  private int startCycle;
  private HashSet<AdjacentNode> adjSignals = new HashSet<AdjacentNode>();
  private HashMap<AdjacentNode, Integer> constAdj = new HashMap<AdjacentNode, Integer>();

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

  @Override
  public String toString() {
    return String.format("Scheduled node. Node start cycle: " + this.startCycle + " address: " +
                         this.adjSignals.contains(AdjacentNode.addr) + " validReq: " + this.adjSignals.contains(AdjacentNode.validReq) +
                         " validResp: " + this.adjSignals.contains(AdjacentNode.validResp));
  }
}
