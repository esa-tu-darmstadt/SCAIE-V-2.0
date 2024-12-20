package scaiev.scal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Defines logic inside a module that generates a set of SCAIE-V nodes. Output of {@link NodeLogicBuilder#apply(NodeRegistryRO, int)}.
 */
public class NodeLogicBlock {
  /** Default {@link #interfPins} key for SCAL<->ISAX pins. */
  public static String InterfToISAXKey = "ISAX";
  /** Default {@link #interfPins} key for SCAL<->Core pins. */
  public static String InterfToCoreKey = "Core";

  public static class InterfacePin {
    /** Interface pin declaration. Must contain a trailing comma if not empty. */
    public String declaration; // Must contain a trailing comma if not empty
    /**
     * The node, stage and signal name matching the interface pin.
     * Expression type must be ModuleOutput or ModuleInput.
     * Used for wrapper netlist generation.
     */
    public NodeInstanceDesc nodeDesc;
    /**
     * List of receivers/senders for a wrapper netlist around SCAL.
     * - A list of ISAXes for SCAL<->ISAX pins tagged with InterfToISAXKey.
     *   For inputs to SCAL, there must be exactly one entry.
     * - Empty for SCAL<->Core pins tagged with InterfToCoreKey.
     */
    public List<String> receivers;

    public InterfacePin(String declaration, NodeInstanceDesc nodeDesc) {
      this.declaration = declaration;
      this.nodeDesc = nodeDesc;
      this.receivers = new ArrayList<String>();
    }
    public InterfacePin(String declaration, NodeInstanceDesc nodeDesc, Collection<String> receivers) {
      this.declaration = declaration;
      this.nodeDesc = nodeDesc;
      this.receivers = new ArrayList<String>(receivers);
    }

    @Override
    public int hashCode() {
      return Objects.hash(declaration);
    }
    @Override
    public boolean equals(Object obj) {
      if (this == obj)
        return true;
      if (obj == null)
        return false;
      if (getClass() != obj.getClass())
        return false;
      InterfacePin other = (InterfacePin)obj;
      return Objects.equals(declaration, other.declaration) && Objects.equals(nodeDesc, other.nodeDesc) &&
          Objects.equals(receivers, other.receivers);
    }
  }

  /**
   * Interface pin declarations to add to the module; must contain a trailing comma if not empty.
   * The key identifies the portion of the interface and will be grouped together across logic instances.
   **/
  public List<Map.Entry<String, InterfacePin>> interfPins = new ArrayList<>();

  /** Declarations to add inside the module. */
  public String declarations = "";
  /** Logic to add inside the module */
  public String logic = "";
  /** External module declarations. */
  public String otherModules = "";
  /**
   * The list of nodes this instance provides as outputs.
   * Entries with expression type ModuleOutput will be ignored.
   */
  public List<NodeInstanceDesc> outputs = new ArrayList<>();
  /** Set to make {@link NodeLogicBlock#isEmpty()} return false despite an otherwise empty NodeLogicBlock. */
  public boolean treatAsNotEmpty = false;

  public boolean isEmpty() {
    return !treatAsNotEmpty && interfPins.isEmpty() && declarations.isEmpty() && logic.isEmpty() && otherModules.isEmpty() &&
        outputs.isEmpty();
  }

  @Override
  public int hashCode() {
    return Objects.hash(declarations, logic, otherModules);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    NodeLogicBlock other = (NodeLogicBlock)obj;
    if (!(Objects.equals(declarations, other.declarations) && Objects.equals(logic, other.logic) &&
          Objects.equals(otherModules, other.otherModules)))
      return false;
    HashSet<InterfacePin> interfEntriesThis = interfPins.stream()
                                                  .map((Map.Entry<String, InterfacePin> pinEntry) -> pinEntry.getValue())
                                                  .collect(Collectors.toCollection(HashSet::new));
    HashSet<InterfacePin> interfEntriesOther = other.interfPins.stream()
                                                   .map((Map.Entry<String, InterfacePin> pinEntry) -> pinEntry.getValue())
                                                   .collect(Collectors.toCollection(HashSet::new));
    if (!interfEntriesThis.equals(interfEntriesOther))
      return false;

    HashSet<NodeInstanceDesc> outputsThis = new HashSet<>(outputs);
    HashSet<NodeInstanceDesc> outputsOther = new HashSet<>(other.outputs);
    return outputsThis.equals(outputsOther);
  }

  public NodeLogicBlock() {}

  /**
   * Adds the contents of another NodeLogicBlock to this one. Does _not_ check for duplicate pins or outputs.
   * @param other the input NodeLogicBlock
   */
  public void addOther(NodeLogicBlock other) {
    this.interfPins.addAll(other.interfPins);

    if (!this.declarations.isEmpty() && !this.declarations.endsWith("\n"))
      this.declarations += "\n";
    this.declarations += other.declarations;

    if (!this.logic.isEmpty() && !this.logic.endsWith("\n"))
      this.logic += "\n";
    this.logic += other.logic;

    if (!this.otherModules.isEmpty() && !this.otherModules.endsWith("\n"))
      this.otherModules += "\n";
    this.otherModules += other.otherModules;

    this.outputs.addAll(other.outputs);

    this.treatAsNotEmpty = this.treatAsNotEmpty || other.treatAsNotEmpty;
  }
}
