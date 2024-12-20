package scaiev.coreconstr;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Optional;
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

  public Core(String name, PipelineStage rootStage) {
    this.rootStage = rootStage;
    this.name = name;
  }

  public Core() {
    this.rootStage = new PipelineStage(StageKind.Root, EnumSet.noneOf(StageTag.class), "root", Optional.empty(), false);
    this.name = "";
  }

  @Override
  public String toString() {
    return String.format("INFO. Core. Core named:" + name + " with nodes = " + nodes.toString());
  }

  public void PutName(String name) { this.name = name; }

  public void PutNodes(HashMap<SCAIEVNode, CoreNode> nodes) {
    for (Entry<SCAIEVNode, CoreNode> node : nodes.entrySet()) {
      if (node.getKey().name.equals("RdRS1")) {
        start_spawn_node = node.getValue();
        break;
      }
    }
    this.nodes = nodes;
  }

  public void PutNode(SCAIEVNode fnode, CoreNode corenode) {
    this.nodes.put(fnode, corenode);
    if (fnode.name.equals("RdRS1"))
      start_spawn_node = corenode;
  }

  public PipelineStage GetRootStage() { return this.rootStage; }

  public PipelineFront GetSpawnStages() {
    // return maxStage+1;
    return new PipelineFront(this.rootStage.getAllChildren().filter(stage -> stage.getKind() == StageKind.Decoupled));
  }

  public PipelineFront GetStartSpawnStages() {
    if (start_spawn_node == null)
      return new PipelineFront();
    return new PipelineFront(TranslateStageScheduleNumber(start_spawn_node.GetEarliest())
                                 .asList()
                                 .stream()
                                 .filter(stage -> stage.getKind() != StageKind.CoreInternal));
  }
  public String GetName() { return name; }

  public HashMap<SCAIEVNode, CoreNode> GetNodes() { return nodes; }

  /**
   * Retrieves the stage schedule number that the given stage covers by default; Optional.empty if the stage is not used for scheduling by
   * default.
   */
  public Optional<Integer> GetStageNumber(PipelineStage stage) {
    if (stage.getKind() != StageKind.Core && stage.getKind() != StageKind.Decoupled)
      return Optional.empty();
    return Optional.of(stage.getStagePos());
  }

  /** Translates a stage schedule number from a {@link CoreNode} into a {@link PipelineFront}. */
  public PipelineFront TranslateStageScheduleNumber(int stageNum) {
    return new PipelineFront(rootStage.getChildrenByStagePos(stageNum).filter(stage -> stage.getKind() != StageKind.CoreInternal));
  }

  /** Translates a {@link ScheduleFront} from a {@link CoreNode} into a {@link PipelineFront}. */
  public PipelineFront TranslateStageScheduleNumber(ScheduleFront stageNum) {
    var asFront_opt = stageNum.tryGetAsFront();
    if (asFront_opt.isPresent())
      return asFront_opt.get();
    return TranslateStageScheduleNumber(stageNum.asInt());
  }

  /** Determines whether a {@link PipelineStage} is in the earliest-latest range of a {@link CoreNode}. */
  public boolean StageIsInRange(CoreNode coreNode, PipelineStage stage) {
    return TranslateStageScheduleNumber(coreNode.GetEarliest()).isAroundOrBefore(stage, false) &&
        TranslateStageScheduleNumber(coreNode.GetLatest()).isAroundOrAfter(stage, false);
  }
}
