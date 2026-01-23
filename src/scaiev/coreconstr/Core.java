package scaiev.coreconstr;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;

import scaiev.frontend.SCAIEVNode;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.pipeline.PipelineStage.StageTag;
import scaiev.pipeline.ScheduleFront;

public class Core {
  private PipelineStage rootStage;
  private HashMap<SCAIEVNode, CoreNode> nodes = new HashMap<SCAIEVNode, CoreNode>();
  private CoreNode start_spawn_node = null;
  private String name;
  public int maxStage;

  public enum CoreTag {
    /** The core is RV64 and has 64-bit registers, addresses, and memory words. */
    RV64("RV64");

    public final String serialName;

    private CoreTag(String serialName) { this.serialName = serialName; }
  }
  EnumSet<CoreTag> tags;

  public Core(String name, PipelineStage rootStage, EnumSet<CoreTag> tags) {
    this.rootStage = rootStage;
    this.name = name;
    this.tags = tags.clone();
  }

  public Core() {
    this.rootStage = new PipelineStage(StageKind.Root, List.of(), "root", Optional.empty(), false);
    this.name = "";
    this.tags = EnumSet.noneOf(CoreTag.class);
  }

  public void addTag(CoreTag tag) { tags.add(tag); }
  public Set<CoreTag> getTags() { return Collections.unmodifiableSet(tags); }

  @Override
  public String toString() {
    return String.format("INFO. Core. Core named:" + name + " with nodes = " + nodes.toString());
  }

  public void setName(String name) { this.name = name; }

  public void setNodes(HashMap<SCAIEVNode, CoreNode> nodes) {
    for (Entry<SCAIEVNode, CoreNode> node : nodes.entrySet()) {
      if (node.getKey().name.equals("RdRS1")) {
        start_spawn_node = node.getValue();
        break;
      }
    }
    this.nodes = nodes;
  }

  /**
   * Adds a node to the Core, as if declared in the datasheet.
   * @param node the node to declare (is an FNode if from the datasheet, can be from BNode or elsewhere if custom)
   * @param corenode the node
   */
  public void putNode(SCAIEVNode node, CoreNode corenode) {
    this.nodes.put(node, corenode);
    if (node.name.equals("RdRS1"))
      start_spawn_node = corenode;
  }

  public PipelineStage getRootStage() { return this.rootStage; }

  public PipelineFront getSpawnStages() {
    // return maxStage+1;
    return new PipelineFront(this.rootStage.getAllChildren().filter(stage -> stage.getKind() == StageKind.Decoupled));
  }

  public PipelineFront getStartSpawnStages() {
    //If present, use RegRename-tagged stages as 'start spawn'; otherwise, use the RdRS1/start_spawn_node
    var ret = new PipelineFront(this.rootStage.getAllChildren().filter(stage -> stage.getTags().contains(StageTag.RegRename)));
    if (!ret.asList().isEmpty())
      return ret;
    if (start_spawn_node == null)
      return new PipelineFront();
    return new PipelineFront(translateStageScheduleNumber(start_spawn_node.getEarliest())
                                 .asList()
                                 .stream()
                                 .filter(stage -> stage.getKind() != StageKind.CoreInternal));
  }
  public String getName() { return name; }

  public HashMap<SCAIEVNode, CoreNode> getNodes() { return nodes; }

  /**
   * Retrieves the stage schedule number that the given stage covers by default; Optional.empty if the stage is not used for scheduling by
   * default.
   */
  public Optional<Integer> getStageNumber(PipelineStage stage) {
    if (stage.getKind() != StageKind.Core && stage.getKind() != StageKind.CoreMultiport && stage.getKind() != StageKind.Decoupled)
      return Optional.empty();
    return Optional.of(stage.getStagePos());
  }

  /** Translates a stage schedule number from a {@link CoreNode} into a {@link PipelineFront}. */
  public PipelineFront translateStageScheduleNumber(int stageNum) {
    return new PipelineFront(rootStage.getChildrenByStagePos(stageNum).filter(stage -> stage.getKind() != StageKind.CoreInternal));
  }

  /** Translates a {@link ScheduleFront} from a {@link CoreNode} into a {@link PipelineFront}. */
  public PipelineFront translateStageScheduleNumber(ScheduleFront stageNum) {
    var asFront_opt = stageNum.tryGetAsFront();
    if (asFront_opt.isPresent())
      return asFront_opt.get();
    return translateStageScheduleNumber(stageNum.asInt());
  }

  /** Determines whether a {@link PipelineStage} is in the earliest-latest range of a {@link CoreNode}. */
  public boolean stageIsInRange(CoreNode coreNode, PipelineStage stage) {
    return translateStageScheduleNumber(coreNode.getEarliest()).isAroundOrBefore(stage, false) &&
        translateStageScheduleNumber(coreNode.getLatest()).isAroundOrAfter(stage, false);
  }
}
