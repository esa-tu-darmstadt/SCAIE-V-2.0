package scaiev.scal.strategy.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistry;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.SCALUtil;
import scaiev.scal.TriggerableNodeLogicBuilder;
import scaiev.scal.strategy.pipeline.IDMapperStrategy;
import scaiev.scal.strategy.pipeline.IDMapperStrategy.IDSource;
import scaiev.util.Log2;
import scaiev.util.Verilog;

public class WriteQueues {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  Core core;
  Verilog language;
  BNode bNodes;
  String regName;

  /**
   * 
   * @param core
   * @param language
   * @param bNodes
   * @param regName
   */
  public WriteQueues(Core core, Verilog language, BNode bNodes, String regName) {
    this.core = core;
    this.language = language;
    this.bNodes = bNodes;
    this.regName = regName;
  }

  private static final SCAIEVNode QueueElementStructNode = new SCAIEVNode("WriteQueueMetaStruct");

  private static record MetaStructProperties(
    int numQueues,
    int maxQueueDepth,
    int regWidth,
    int regIDLen,
    int instrIDLen,
    int forwardIndexLen,
    int forwardIndexCount,
    boolean hasWriteResp) {

    public String makeStructName() {
      return String.format("writemeta_entry_t_%d_%d_%d_%d_%d_%d_%d_%d", numQueues, maxQueueDepth, regWidth, regIDLen, instrIDLen,
                           forwardIndexLen, forwardIndexCount, hasWriteResp ? 1 : 0);
    }
  }

  private static List<MetaStructProperties> metaStructProperties = new ArrayList<>();

  /** TODO: Write ordering (ref_* struct fields) is not implemented yet. */
  private static final boolean supportsWriteOrdering = false;

  /**
   * Creates a Key for the struct declaration with given parameters.
   *   Note: Each call with new parameters creates an internal tracking object, so avoid calling this too often with non-final parameters.
   * @param numQueues the number of write queues (for entry references across queues)
   * @param maxQueueDepth the maximum queue depth across all write queues (for entry references across queues)
   * @param regWidth the width of each register
   * @param regIDLen the length of a logical register address/ID
   * @param instrIDLen the instruction ID length; must be positive
   * @param forwardIndexLen the length of the optional forward index (all-1-bit for invalid)
   * @param forwardIndexCount the number of forward indices; set to 0 to disable
   * @param hasWriteResp iff true, an indicator is added to the struct to indicate if the register file write has started
   * @return
   */
  public NodeInstanceDesc.Key makeMetaStructKey(int numQueues, int maxQueueDepth, int regWidth, int regIDLen, int instrIDLen,
                                                int forwardIndexLen, int forwardIndexCount, boolean hasWriteResp) {
    if (numQueues <= 0)
      throw new IllegalArgumentException("numQueues must be above 0");
    if (maxQueueDepth <= 0)
      throw new IllegalArgumentException("maxQueueDepth must be above 0");
    if (regWidth <= 0)
      throw new IllegalArgumentException("regWidth must be above 0");
    if (regIDLen < 0)
      throw new IllegalArgumentException("regIDLen must not be negative");
    if (instrIDLen <= 0)
      throw new IllegalArgumentException("instrIDLen must be above 0");
    if (forwardIndexLen < 0)
      throw new IllegalArgumentException("forwardIndexLen must not be negative");
    if (forwardIndexCount < 0)
      throw new IllegalArgumentException("forwardIndexCount must not be negative");
    if (forwardIndexCount > 0 && forwardIndexLen == 0)
      throw new IllegalArgumentException("forwardIndexLen must not be zero if forwardIndexCount is above zero");
    var properties = new MetaStructProperties(numQueues, maxQueueDepth, regWidth, regIDLen, instrIDLen, forwardIndexLen, forwardIndexCount,
                                              hasWriteResp);
    int auxVal = metaStructProperties.indexOf(properties);
    if (auxVal == -1) {
      auxVal = metaStructProperties.size();
      metaStructProperties.add(properties);
    }
    return new NodeInstanceDesc.Key(Purpose.REGULAR, QueueElementStructNode, core.GetRootStage(), "", auxVal);
  }
  /**
   * Creates the struct key with a given aux value previously returned from a makeMetaStructKey call.
   * (is a dedicated function to avoid too many assumptions about the key in other code locations)
   */
  private static NodeInstanceDesc.Key makeMetaStructKeyFromAux(Core core, int auxVal) {
    return new NodeInstanceDesc.Key(Purpose.REGULAR, QueueElementStructNode, core.GetRootStage(), "", auxVal);
  }

  private boolean implementSingle_Struct(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey, boolean isLast) {
    if (nodeKey.getPurpose().matches(Purpose.REGULAR) && nodeKey.getNode().equals(QueueElementStructNode) &&
        nodeKey.getStage().getKind() == StageKind.Root && nodeKey.getISAX().isEmpty()) {
      MetaStructProperties properties = metaStructProperties.get(nodeKey.getAux());
      out.accept(NodeLogicBuilder.fromFunction("WriteQueue_Struct", registry -> {
        var ret = new NodeLogicBlock();
        String name = properties.makeStructName();
        ret.declarations += """
            typedef struct packed {
                logic valid; //entry is in-use, other struct fields are valid
                logic flushing; //entry will not be committed (instr flush or write cancellation)
            """;
        if (supportsWriteOrdering) {
          ret.declarations += 
              "    logic is_overwritten; //entry is overwritten (will not be committed, resettable), "
                  + "exactly one overwriting entry has a reference to this\n";
        }

        if (properties.hasWriteResp)
          ret.declarations += "    logic pending; //write has started but may not be visible on the regfile's read port yet\n";

        if (properties.regIDLen > 0)
          ret.declarations += "logic [%1$d-1:0] regID;".formatted(properties.regIDLen);
        ret.declarations += """
                logic [%2$d-1:0] instrID; //(ID spaces can vary between queues - coupled vs decoupled instructions)
            """.formatted(properties.regIDLen, properties.instrIDLen);

        if (supportsWriteOrdering) {
          if (properties.forwardIndexCount > 0)
            ret.declarations += "    logic [%d-1:0] forwardTo [%d];\n".formatted(properties.forwardIndexLen, properties.forwardIndexCount);
  
          ret.declarations += """
                  //{ref_queueid,ref_inqueueidx}: ref to the previous entry (i.e., the entry being overwritten if
                  // !flushing, or the entry that forwarding should refer to)
                  logic [%1$d-1:0] ref_queueid; //'1 reserved as invalid
              """.formatted(Log2.clog2(properties.numQueues + 1));
  
          if (properties.maxQueueDepth > 1)
            ret.declarations += "    logic [%d-1:0] ref_inqueueidx;\n".formatted(Log2.clog2(properties.maxQueueDepth));
  
          ret.declarations += """
                  //When set to 1 after flushing is set to 1,
                  // ref_scanned indicates that the ref_* list portion is no longer pending
                  // and {data,data_present} has already been copied upstream to this queue entry;
                  // if (flushing&&ref_scanned&&!data_present), no read forwarding is necessary (register file is up-to-date)
                  logic ref_scanned;
                  logic [%1$d-1:0] ref_supersededby_queueid; //'1 reserved as invalid
              """.formatted(Log2.clog2(properties.numQueues + 1));
  
          if (properties.maxQueueDepth > 1)
            ret.declarations += "    logic [%d-1:0] ref_supersededby_inqueueidx;\n".formatted(Log2.clog2(properties.maxQueueDepth));
        }

        ret.declarations += """
                logic data_present; //data for this entry is present
                logic [%1$d-1:0] data; //data for this entry
            """.formatted(properties.regWidth);

        ret.declarations += "} " + name + ";";

        ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.REGULAR), name, ExpressionType.WireName));
        return ret;
      }));
      return true;
    }
    return false;
  }

  private static final String queueNodeStructAuxPrefix = "WriteQueueStructAux_";
  private static final String mainQueueNodePrefix = "WriteQueue_";
  private static final String pushQueueNodePrefix = "PushWriteQueue_";
  private static final String rdQueueNodePrefix = "RdWriteQueue_";
  private static final String wrQueueNodePrefix = "WrWriteQueue_";
  public static class WriteQueueDesc {
    protected String queueName; // Baked into rdBaseNode

    /** the physical write channel ID of the register file this queue is associated with */
    public int physicalWriteChannel;
    // public int numConcurrentEnqs;

    protected int depth; // Baked into rdAddrBaseNode

    /** the width of a register */
    public int regWidth;
    /** the length of a physical register ID */
    public int regIDLen;
    /** the length of the optional forward index */
    public int forwardIDLen;
    /** the number of forward indices to track per queue entry; set to 0 to disable */
    public int forwardIDCount;

    /** the ID mapping pushing into the queue */
    public List<IDMapperStrategy.IDSource> addrMapping;

    /** the ID mapping for all commits */
    public List<IDMapperStrategy.IDSource> commitMapping;
    
    /** the 'is discard' condition key for each commitMapping entry */
    public List<NodeInstanceDesc.Key> commitIsDiscardKeys;

    ///** the stages that push into the queue */
    //public PipelineFront addrFront;
    ///** the stages that remove elements from the queue */
    //public PipelineFront commitFront;

    ///** a mapping from each addrFront stage to a key for the current instruction ID */
    //public Function<PipelineStage, NodeInstanceDesc.Key> getAddrInstrIDKey;

    ///** a mapping from each commitFront stage to a key for the current instruction ID */
    //public Function<PipelineStage, NodeInstanceDesc.Key> getCommitInstrIDKey;

    ///** the flush indicator for this queue */
    //public NodeInstanceDesc.Key isFlushingKey;
    ///** the oldest instruction ID affected by the flush */
    //public NodeInstanceDesc.Key flushFromKey;
    ///** the newest instruction ID affected by the flush */
    //public NodeInstanceDesc.Key flushToKey;

    /**
     * The queue builder, to be triggered after changing any of the public fields.
     * Note: This does not trigger other builders that may depend on the public fields of this queue.
     */
    public TriggerableNodeLogicBuilder queueBuilder = null;

    /**
     * The list of other triggerable builders depending on the public fields of this queue.
     */
    public List<TriggerableNodeLogicBuilder> miscBuilders = new ArrayList<>();

    /**
     *
     * @param queueName the unique name of the queue; should not contain special characters other than _
     * @param physicalWriteChannel the physical write channel ID of the register file this queue is associated with
     * @param depth the depth (number of entries) of the write queue
     * @param forwardIDLen the length of the optional forward index
     * @param forwardIDLen the number of forward indices to track per queue entry; set to 0 to disable
     * @param regWidth the width of each register
     * @param regIDLen the length of a physical register ID
     */
    public WriteQueueDesc(String queueName, int physicalWriteChannel, int depth, int forwardIDLen, int forwardIDCount,
                          // int numConcurrentEnqs,
                          int regWidth, int regIDLen,
                          List<IDMapperStrategy.IDSource> addrMapping,
                          List<IDMapperStrategy.IDSource> commitMapping,
                          List<NodeInstanceDesc.Key> commitIsDiscardKeys
                          //* @param addrFront the stages that push into the queue
                          //* @param getAddrInstrIDKey a mapping from each addrFront stage to a key for the current instruction ID
                          //* @param commitFront the stages that remove elements from the queue
                          //* @param getCommitInstrIDKey a mapping from each commitFront stage to a key for the current instruction ID
                          //* @param isFlushingKey a key indicating if a flush is being performed
                          //* @param flushFromKey the oldest instruction ID affected by the flush
                          //* @param flushToKey the newest instruction ID affected by the flush
                          //PipelineFront addrFront, Function<PipelineStage, NodeInstanceDesc.Key> getAddrInstrIDKey,
                          //PipelineFront commitFront, Function<PipelineStage, NodeInstanceDesc.Key> getCommitInstrIDKey
                          ) {
      this.queueName = queueName;
      this.mainNode = new SCAIEVNode(mainQueueNodePrefix + queueName);
      this.physicalWriteChannel = physicalWriteChannel;
      this.depth = depth;
      this.forwardIDLen = forwardIDLen;
      this.forwardIDCount = forwardIDCount;
      // this.numConcurrentEnqs = numConcurrentEnqs;
      // if (numConcurrentEnqs > depth) {
      //	throw new IllegalArgumentException("numConcurrentEnqs cannot exceed depth");
      // }
      this.regWidth = regWidth;
      this.regIDLen = regIDLen;
      //this.addrFront = addrFront;
      //this.commitFront = commitFront;
      //this.getAddrInstrIDKey = getAddrInstrIDKey;
      //this.getCommitInstrIDKey = getCommitInstrIDKey;
      this.addrMapping = List.copyOf(addrMapping);
      this.commitMapping = List.copyOf(commitMapping);
      this.commitIsDiscardKeys = List.copyOf(commitIsDiscardKeys);

      this.pushDataNode = new SCAIEVNode(pushQueueNodePrefix + queueName, regIDLen, true);
      this.pushReqNode = new SCAIEVNode(this.pushDataNode, AdjacentNode.validReq, 1, true, false);
      this.pushReadyNode = new SCAIEVNode(this.pushDataNode, AdjacentNode.validResp, 1, false, false);
      this.pushAddrRespNode = new SCAIEVNode(this.pushDataNode, AdjacentNode.addr, Log2.clog2(depth), false, false);
      this.rdNode = new SCAIEVNode(rdQueueNodePrefix + queueName, 0, false);
      this.rdAddrNode = new SCAIEVNode(this.rdNode, AdjacentNode.addr, Log2.clog2(depth), true, false);
      this.wrNode = new SCAIEVNode(wrQueueNodePrefix + queueName, 0, true);
      this.wrReqNode = new SCAIEVNode(this.wrNode, AdjacentNode.validReq, 1, true, false);
      this.wrRespNode = new SCAIEVNode(this.wrNode, AdjacentNode.validResp, 1, false, false);
      this.wrAddrNode = new SCAIEVNode(this.wrNode, AdjacentNode.addr, Log2.clog2(depth), true, false);
      this.entryStructAuxNode = new SCAIEVNode(queueNodeStructAuxPrefix + queueName, 0, true);
    }

    protected SCAIEVNode entryStructAuxNode;
    /**
     * Looks up the Key for the entry struct. The caller can look up the returned key to get the struct name.
     * Note: The key can change over build iterations; the caller should subscribe via miscBuilders.
     * @param core the core
     * @param registry the registry of the calling builder
     */
    public NodeInstanceDesc.Key getEntryStructKey(Core core, NodeRegistryRO registry) {
      var auxKey = new NodeInstanceDesc.Key(Purpose.REGULAR, entryStructAuxNode, core.GetRootStage(), "");
      String auxExpr = registry.lookupExpressionRequired(auxKey);
      try {
        int auxVal = Integer.parseInt(auxExpr);
        return WriteQueues.makeMetaStructKeyFromAux(core, auxVal);
      } catch (NumberFormatException e) {
        assert (auxExpr.startsWith(NodeRegistry.MISSING_PREFIX));
        // just return the aux key, so the calling builder will also get an update once it resolves
        return auxKey;
      }
    }

    /** Main node handling the queue array. */
    protected SCAIEVNode mainNode;

    /** Main node handling the queue array. */
    protected SCAIEVNode queueDataNode;

    protected SCAIEVNode pushDataNode;
    /**
     * The register ID to push into the queue. Can be used in addrFront only.
     * Forward index could be set to a default value for 'no forwarding' that can later be overwritten.
     * Queue input.
     */
    public SCAIEVNode getPushDataNode() { return pushDataNode; }
    protected SCAIEVNode pushReqNode;
    /**
     * Queue push request.
     * Queue input.
     */
    public SCAIEVNode getPushReqNode() { return pushReqNode; }
    protected SCAIEVNode pushReadyNode;
    /**
     * Queue push ready.
     * The returned SCAIEVNode is marked as validResp, but readiness can be queried even if {@link WriteQueueDesc#getPushReqNode()} is set
     * to 0. 0 if not ready and push should be retried. Note: pushReady in addr stage N+1 has a combinational dependency on pushReq in addr
     * stage N. Queue output.
     */
    public SCAIEVNode getPushReadyNode() { return pushReadyNode; }
    protected SCAIEVNode pushAddrRespNode;
    /**
     * The queue address where the requested push is written to.
     * Only valid if both {@link WriteQueueDesc#getPushReqNode()} and {@link WriteQueueDesc#getPushReadyNode()} are 1.
     * Queue output.
     */
    public SCAIEVNode getPushAddrRespNode() { return pushAddrRespNode; }

    protected SCAIEVNode rdNode;
    /**
     * The queue entry read data. Should be requested with aux value matching the rdAddrNode.
     * Queue output.
     */
    public SCAIEVNode getRdNode() { return rdNode; }
    protected SCAIEVNode rdAddrNode;
    /**
     * The address of the queue entry to read from. Read data is output to the rdNode with the same aux.
     * Queue input.
     */
    public SCAIEVNode getRdAddrNode() { return rdAddrNode; }

    protected SCAIEVNode wrNode;
    /**
     * The data to write to a field at the specified queue address. Field name is given in place of the ISAX name:
     *   "valid", "flushing", "is_overwritten", "pending", "regID", "instrID", "forwardTo&lt;0..forwardIDCount-1&gt;", "ref_queueid",
     * "ref_inqueueidx", "ref_scanned", "ref_supersededby_queueid", "ref_supersededby_inqueueidx", "data_present", "data" Aux specifies the
     * queue write port. The same aux can be reused for different field names. Queue input.
     */
    public SCAIEVNode getWrNode(int size) {
      SCAIEVNode ret = SCAIEVNode.CloneNode(wrNode, Optional.empty(), true);
      ret.size = size;
      return ret;
    }
    /**
     * Queue write request.
     * Queue input.
     */
    protected SCAIEVNode wrReqNode;
    public SCAIEVNode getWrReqNode() { return wrReqNode; }
    /**
     * The address of the queue entry to write to. Field name (in ISAX field) and aux value must match the wrNode.
     * Queue input.
     */
    protected SCAIEVNode wrAddrNode;
    public SCAIEVNode getWrAddrNode() { return wrAddrNode; }
    protected SCAIEVNode wrRespNode;
    /**
     * Write response (fixed to 1). Any node writing via getWrNode and getWrAddrNode should lookupRequired the corresponding getWrRespNode.
     * Queue output.
     */
    public SCAIEVNode getWrRespNode() { return wrRespNode; }

    protected List<NodeInstanceDesc.Key> requestedWrResps = new ArrayList<>();
  }
  private Map<String, WriteQueueDesc> writeQueues = new HashMap<>();

  public WriteQueueDesc getWriteQueue(String queueName) { return writeQueues.get(queueName); }
  public boolean addWriteQueue(WriteQueueDesc queueDesc) {
    if (writeQueues.containsKey(queueDesc.queueName))
      return false; // throw new IllegalArgumentException("duplicate queueName");
    if (queueDesc.queueName.contains("_port"))
      throw new IllegalArgumentException("Illegal queue name");
    writeQueues.put(queueDesc.queueName, queueDesc);
    return true;
  }
  private Optional<String> getQueueNameFromWrNode(SCAIEVNode wrNode) {
    String relevantName = wrNode.isAdj() ? wrNode.nameParentNode : wrNode.name;
    if (relevantName.startsWith(wrQueueNodePrefix)) {
      int portIdx = relevantName.indexOf("_port");
      return Optional.of(relevantName.substring(wrQueueNodePrefix.length(), (portIdx == -1) ? relevantName.length() : portIdx));
    }
    return Optional.empty();
  }
  private Optional<String> getQueueNameFromRdNode(SCAIEVNode rdNode) {
    String relevantName = rdNode.isAdj() ? rdNode.nameParentNode : rdNode.name;
    if (relevantName.startsWith(rdQueueNodePrefix)) {
      int portIdx = relevantName.indexOf("_port");
      return Optional.of(relevantName.substring(rdQueueNodePrefix.length(), (portIdx == -1) ? relevantName.length() : portIdx));
    }
    return Optional.empty();
  }
  private Optional<String> getQueueNameFromMainNode(SCAIEVNode node) {
    if (node.name.startsWith(mainQueueNodePrefix)) {
      assert (!node.isAdj() && !node.name.contains("_port"));
      return Optional.of(node.name.substring(mainQueueNodePrefix.length()));
    }
    return Optional.empty();
  }
  
  private IntFunction<String> enqueuePosByAllocation(WriteQueueDesc queueDesc,
                                        int numConcurrentEnqs, List<String> enqPushRequests,
                                        List<String> addrStageStallConds,
                                        String queueStorageName, String queueDatatype,
                                        String tab, NodeLogicBlock ret) {
    String enqSlotRstLogic = "";
    String enqSlotNorstDecl = "";
    String enqSlotNorstLogic = "";

    IntFunction<String> getEnqSlotWireName = iEnq -> String.format("%s_nextenqslot_%d", queueStorageName, iEnq);

    //- enqscan: Scan the write queue for free slots as needed.
    // Scan width rounded down to a power of two (-> can fix lower bits of scanPosExpr to 0)
    int scanWidth = 2 * numConcurrentEnqs;
    if (scanWidth > queueDesc.depth)
      scanWidth = queueDesc.depth;
    scanWidth = 1 << Log2.log2(scanWidth);
    String scanPosExpr = String.format("%d'd0", Log2.clog2(queueDesc.depth));
    if (scanWidth < queueDesc.depth) {
      //-> We scan only a portion of the queue per cycle.
      ret.declarations +=
          String.format("logic [$clog2(%d)-$clog2(%d)-1:0] %s_enqscanpos;\n", queueDesc.depth, scanWidth, queueStorageName);
      scanPosExpr = String.format("{%s_enqscanpos,%d'd0}", queueStorageName, Log2.clog2(scanWidth));
      int maxScanposVal =
          (queueDesc.depth - 1) / scanWidth; // division by scanWidth (power of two), since enqscanpos only stores the upper bits
      int scanposRegWidth = Log2.clog2(queueDesc.depth) - Log2.clog2(scanWidth);

      enqSlotRstLogic += tab + tab + String.format("%s_enqscanpos <= 0;\n", queueStorageName);
      enqSlotNorstLogic +=
          tab + tab +
          String.format("if (%s)\n",
                        Stream
                            .concat(IntStream.range(0, numConcurrentEnqs)
                                        .mapToObj(getEnqSlotWireName)
                                        .map(slotWire -> "!" + slotWire + "_valid"), // If any enqueue slot is currently invalid..
                                    enqPushRequests.stream()                         //.. or an enqueue is being requested
                                    )
                            .reduce((a, b) -> a + " || " + b)
                            .orElseThrow());
      enqSlotNorstLogic +=
          tab + tab + tab +
          String.format("%s_enqscanpos <= (%s_enqscanpos >= %d) ? %d'd0 : (%s_enqscanpos + %d'd1);\n", //.. advance the scan position
                        queueStorageName, queueStorageName, maxScanposVal, scanposRegWidth, queueStorageName, scanposRegWidth);
    }

    enqSlotNorstDecl += tab + tab + String.format("%s enqscanResultEmpty;\n", queueDatatype, scanWidth);
    enqSlotNorstDecl += tab + tab + "enqscanResultEmpty.valid = 0;\n";
    enqSlotNorstDecl += tab + tab + String.format("%s enqscanResults[%d];\n", queueDatatype, scanWidth);
    for (int iScan = 0; iScan < scanWidth; ++iScan) {
      // Access the queue at the current scan position, offset by iScan.
      // Bounds check: If the scan is out of range of the queue, return an empty scan result..
      enqSlotNorstLogic += tab + tab +
                           String.format("enqscanResults[%d] = (%s <= %d'd%d) ? %s[%s+%d'd%d] : enqscanResultEmpty;\n", iScan,
                                         scanPosExpr, Log2.clog2(queueDesc.depth), queueDesc.depth - scanWidth + iScan,
                                         queueStorageName, scanPosExpr, Log2.clog2(queueDesc.depth), iScan);
    }

    //ret.declarations += String.format("logic [$clog2(%d)-1:0] %s_level;\n", queueDesc.depth, queueStorageName);
    // enqscanResultUsed: bitmap over the enqueue scan
    enqSlotNorstDecl += tab + tab + String.format("Integer iEnqscan;\n");
    enqSlotNorstDecl += tab + tab + String.format("logic [%d-1:0] enqscanResultUsed;\n", scanWidth);
    enqSlotNorstLogic += tab + tab + String.format("enqscanResultUsed = %d'd0;\n", scanWidth);
    for (int iEnq = 0; iEnq < numConcurrentEnqs; ++iEnq) {
      String enqSlotWireName = getEnqSlotWireName.apply(iEnq);
      ret.declarations += String.format("logic [$clog2(%d)-1:0] %s;\n", queueDesc.depth, enqSlotWireName);
      ret.declarations += String.format("logic %s_valid;\n", enqSlotWireName);
      // nextenqslot_<iEnq>_valid -> enqueue ready signal for port iEnq
      ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(
                                                   queueDesc.pushReadyNode,
                                                   queueDesc.addrMapping.get(iEnq).getTriggerStage(),
                                                   ""),
                                           String.format("%s_valid", enqSlotWireName),
                                           ExpressionType.WireName));
      ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(
                                                   queueDesc.pushAddrRespNode,
                                                   queueDesc.addrMapping.get(iEnq).getTriggerStage(),
                                                   ""),
                                           enqSlotWireName,
                                           ExpressionType.WireName));
      enqSlotRstLogic += String.format("%s <= %d'd%d;\n", enqSlotWireName, Log2.clog2(queueDesc.depth), iEnq);
      enqSlotRstLogic += String.format("%s_valid <= 1;\n", enqSlotWireName);

      // Clear valid if the enqueue is being performed.
      enqSlotNorstLogic += tab + tab +
                           String.format("if (%s_valid && %s && !(%s)) begin\n", enqSlotWireName, enqPushRequests.get(iEnq),
                                         addrStageStallConds.get(iEnq));
      enqSlotNorstLogic += tab + tab + tab + String.format("%s_valid <= 0;\n", enqSlotWireName);
      enqSlotNorstLogic += tab + tab + "end\n";
      // Apply scan
      enqSlotNorstLogic += tab + tab + String.format("if (!%s_valid || %s) begin\n", enqSlotWireName, enqPushRequests.get(iEnq));
      enqSlotNorstLogic += tab + tab + tab +
                           String.format("for (iEnqscan = 0; iEnqscan < %d; iEnqscan=iEnqscan+1) if (!enqscanResultUsed[iEnqscan] && "
                                             + "enqscanResults[iEnqscan].valid) begin\n",
                                         scanWidth);
      enqSlotNorstLogic += tab + tab + tab + tab + "enqscanResultUsed[iEnqscan] = 1;\n";
      enqSlotNorstLogic += tab + tab + tab + tab + String.format("%s <= iEnqscan;\n", enqSlotWireName);
      enqSlotNorstLogic += tab + tab + tab + tab + String.format("%s_valid <= 1;\n", enqSlotWireName);
      enqSlotNorstLogic += tab + tab + tab + "end\n";
      enqSlotNorstLogic += tab + tab + "end\n";
      // This would be the place to add additional balancing between enqueue slots,
      //  e.g. to prevent possibly starving out the higher slot numbers.
      //  (only relevant for multi-issue cores)
    }
    String enqSlotLogic = String.format("always_ff @(posedge %s) begin\n", language.clk);
    enqSlotLogic += tab + String.format("if (%s) begin\n", language.reset);
    enqSlotLogic += enqSlotRstLogic;
    enqSlotLogic += tab + "end\n";
    enqSlotLogic += tab + String.format("else begin : %s_enqslot_scope\n", queueStorageName);
    enqSlotLogic += enqSlotNorstDecl;
    enqSlotLogic += enqSlotNorstLogic;
    enqSlotLogic += tab + "end\n";

    enqSlotLogic += "end\n";
    ret.logic += enqSlotLogic;
    ret.declarations += String.format("%s %s[%d];\n", queueDatatype, queueStorageName, queueDesc.depth);
    return getEnqSlotWireName;
  }
  
  private String buildExpr_SourceStalling(NodeRegistryRO registry, IDSource idSource, boolean checkFlush) {
    if (idSource.getTriggerStage().getKind() == StageKind.Root) {
      if (idSource.node_response_stall.isPresent())
        return registry.lookupExpressionRequired(new NodeInstanceDesc.Key(idSource.node_response_stall.get(), idSource.getTriggerStage(), ""));
      return "1'b0";
    }
    String stallCond = "";
    if (idSource.node_response_stall.isPresent() && !idSource.node_response_stall.get().equals(bNodes.WrStall))
      stallCond = registry.lookupRequired(new NodeInstanceDesc.Key(idSource.node_response_stall.get(), idSource.getTriggerStage(), "")).getExpressionWithParens();
    //if (idSource.key_valid.isPresent())
    //  return "!" + registry.lookupRequired(idSource.key_valid.get()).getExpressionWithParens();
    return stallCond + (stallCond.isEmpty() ? "" : " || ")
                     + SCALUtil.buildCond_StageStalling(bNodes, registry, idSource.getTriggerStage(), checkFlush);
  }

  @SuppressWarnings("unused")
  boolean implementSingle(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey, boolean isLast) {
    if (implementSingle_Struct(out, nodeKey, isLast))
      return true;
    Optional<String> queueNameForRdNode = getQueueNameFromRdNode(nodeKey.getNode());
    if (nodeKey.getPurpose().matches(Purpose.REGULAR) && queueNameForRdNode.isPresent() &&
        nodeKey.getNode().getAdj() == AdjacentNode.none && writeQueues.containsKey(queueNameForRdNode.get()) &&
        nodeKey.getISAX().isEmpty()) {
      // Handle queue read nodes - directly output the array element.
      WriteQueueDesc queueDesc = writeQueues.get(queueNameForRdNode.get());
      if (!Stream.concat(queueDesc.addrMapping.stream(), queueDesc.commitMapping.stream()).map(idSource -> idSource.getTriggerStage())
              .anyMatch(stage -> stage.equals(nodeKey.getStage()))) {
        logger.warn("Node {} requested in stage {}, which is neither address nor commit stage", nodeKey.getNode(),
                    nodeKey.getStage().getName());
      }
      out.accept(NodeLogicBuilder.fromFunction("WriteQueue_" + nodeKey.toString(false), registry -> {
        var ret = new NodeLogicBlock();
        String queueStorageName = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(queueDesc.mainNode, core.GetRootStage(), ""));
        String queueAddr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
            Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN, queueDesc.rdAddrNode, nodeKey.getStage(), "", nodeKey.getAux()));
        ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.REGULAR),
                                             String.format("%s[%s]", queueStorageName, queueAddr),
                                             ExpressionType.AnyExpression_Noparen)); // Assume precedence of array access is sufficient
        return ret;
      }));
      return true;
    }
    Optional<String> queueNameForWrNode = getQueueNameFromWrNode(nodeKey.getNode());
    if (nodeKey.getPurpose().matches(Purpose.WIREDIN_FALLBACK) && queueNameForWrNode.isPresent() &&
        nodeKey.getNode().getAdj() == AdjacentNode.validResp && writeQueues.containsKey(queueNameForWrNode.get())) {
      // Handle queue write validResp nodes - add a validResp=1 fallback builder, add to a list to process via the main builder.
      WriteQueueDesc queueDesc = writeQueues.get(queueNameForWrNode.get());
      queueDesc.requestedWrResps.add(nodeKey);
      out.accept(NodeLogicBuilder.fromFunction("WriteQueue_" + nodeKey.toString(false), registry -> {
        var ret = new NodeLogicBlock();
        registry.lookupRequired(new NodeInstanceDesc.Key(queueDesc.mainNode, core.GetRootStage(), ""));
        ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.WIREDIN_FALLBACK), "1'b1",
                                             ExpressionType.AnyExpression));
        return ret;
      }));
      if (queueDesc.queueBuilder != null)
        queueDesc.queueBuilder.trigger(out);
      return true;
    }
    Optional<String> queueNameForMainNode = getQueueNameFromMainNode(nodeKey.getNode());
    if (nodeKey.getPurpose().matches(Purpose.REGULAR) && queueNameForMainNode.isPresent() &&
        writeQueues.containsKey(queueNameForMainNode.get()) && nodeKey.getStage().getKind() == StageKind.Root &&
        nodeKey.getISAX().isEmpty() && nodeKey.getAux() == 0) {
      // Actual queue logic
      WriteQueueDesc queueDesc = writeQueues.get(queueNameForMainNode.get());
      assert (queueDesc.queueBuilder == null);
      assert (!queueDesc.addrMapping.isEmpty() && !queueDesc.commitMapping.isEmpty());
      // assert(queueDesc.numConcurrentEnqs > 0);
      if (queueDesc.depth == 0) {
        logger.error("Not creating an empty WriteQueue ({})", nodeKey.toString(false));
        return true;
      }
      List<Integer> wrstallAuxsByAddrfront = new ArrayList<>();
      queueDesc.queueBuilder = TriggerableNodeLogicBuilder.fromFunction("WriteQueue_" + nodeKey.toString(false), nodeKey, (registry, aux) -> {
        String tab = language.tab;
        var ret = new NodeLogicBlock();

        List<String> addrStageStallConds =
            queueDesc.addrMapping.stream()
                .map(idSource -> buildExpr_SourceStalling(registry, idSource, true))
                .toList();
        List<NodeInstanceDesc> addrStageIDs =
            queueDesc.addrMapping.stream().map(idSource -> registry.lookupRequired(idSource.key_ID)).toList();
        List<String> enqPushRequests = IntStream.range(0, queueDesc.addrMapping.size())
                                         .mapToObj(iPort -> new NodeInstanceDesc.Key(
                                                                    queueDesc.pushReqNode,
                                                                    queueDesc.addrMapping.get(iPort).getTriggerStage(),
                                                                    ""))
                                         .map(reqKey -> registry.lookupRequired(reqKey).getExpressionWithParens())
                                         .toList();
        List<NodeInstanceDesc> enqPushData =
            IntStream.range(0, queueDesc.addrMapping.size())
                .mapToObj(iPort -> new NodeInstanceDesc.Key(
                                           queueDesc.pushDataNode,
                                           queueDesc.addrMapping.get(iPort).getTriggerStage(),
                                           ""))
                .map(reqKey -> registry.lookupRequired(reqKey))
                .toList();
        int numConcurrentEnqs = queueDesc.addrMapping.size();
        assert (enqPushRequests.size() == numConcurrentEnqs);

        List<NodeInstanceDesc> commitStageIDs =
            queueDesc.commitMapping.stream().map(idSource -> registry.lookupRequired(idSource.key_ID)).toList();

        // There should be only one instruction ID length.
        int instrIDLen = Stream.concat(addrStageIDs.stream(), commitStageIDs.stream())
                             .map(idNodeInst -> idNodeInst.getKey().getNode().size)
                             .reduce((a, b) -> {
                               assert (a == b);
                               return a;
                             })
                             .orElseThrow();

        int queueIDCount = this.writeQueues.size() + 1; // extra ID for 'invalid'
        int maxQueueDepth = this.writeQueues.values().stream().mapToInt(queueDesc_ -> queueDesc_.depth).max().getAsInt();

        String queueStorageName = String.format("writequeue_%s", queueDesc.queueName);
        boolean hasWriteResp = true;
        var metaStructKey = this.makeMetaStructKey(queueIDCount, maxQueueDepth, queueDesc.regWidth, queueDesc.regIDLen, instrIDLen,
                                                   queueDesc.forwardIDLen, queueDesc.forwardIDCount, hasWriteResp);
        String queueDatatype = registry.lookupExpressionRequired(metaStructKey);
        // Output the struct key aux.
        ret.outputs.add(
            new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, queueDesc.entryStructAuxNode, core.GetRootStage(), ""),
                                 "" + metaStructKey.getAux(), ExpressionType.AnyExpression_Noparen));
        ret.declarations += String.format("%s %s [%d];\n", queueDatatype, queueStorageName, queueDesc.depth);

        //-> WIP: Dynamic allocation. Would require one additional layer of indirection for ID->queue translation.
        //   Would also need retire support.
        //boolean needsStallIfNoFreeSlot = true;
        //IntFunction<String> getEnqSlotWireName =
        //    enqueuePosByAllocation(queueDesc, numConcurrentEnqs, enqPushRequests, addrStageStallConds, queueStorageName, queueDatatype, tab, ret);
        //Retrieve enqueue index by external inner ID translation
        //String[] enqInnerID = new String[queueDesc.addrMapping.size()];

        //Stall condition set by ID source (-> IDMapperStrategy), included in valid condition.
        boolean needsStallIfNoFreeSlot = false;
        IntFunction<String> getEnqSlotWireName = iEnq -> String.format("%s_nextenqslot_%d", queueStorageName, iEnq);
        for (int iEnq = 0; iEnq < queueDesc.addrMapping.size(); ++iEnq) {
          String enqSlotWireName = getEnqSlotWireName.apply(iEnq);
          var idSource = queueDesc.addrMapping.get(iEnq);
          var innerIDInst = registry.lookupRequired(idSource.makeKey_RdInnerID(0, 0));
          String innerID = innerIDInst.getExpression();
          String innerID_valid = "";
          boolean stall_included = false;
          //Base valid condition (excl. node_response_stall/WrStall)
          innerID_valid = registry.lookupRequired(idSource.makeKey_RdInnerIDValid()).getExpressionWithParens();

          //Stall condition
          var stallKey = new NodeInstanceDesc.Key(idSource.node_response_stall.orElse(bNodes.WrStall), idSource.getTriggerStage(), "");
          registry.lookupRequired(stallKey);
          var stallKey_write = NodeInstanceDesc.Key.keyWithPurposeAux(stallKey, Purpose.REGULAR, aux);

          ret.outputs.add(new NodeInstanceDesc(
                                stallKey_write,
                                String.format("%s && %s[%s].valid", innerID_valid, queueStorageName, innerID),
                                ExpressionType.AnyExpression));

          ret.declarations += String.format("logic [%d-1:0] %s;\n", innerIDInst.getKey().getNode().size, enqSlotWireName);
          ret.declarations += String.format("logic %s_valid;\n", enqSlotWireName);
          
          ret.logic += String.format("assign %s = %s;\n", enqSlotWireName, innerID);
          ret.logic += String.format("assign %s_valid = %s;\n", enqSlotWireName, innerID_valid);

          ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(
                                                       queueDesc.pushReadyNode,
                                                       queueDesc.addrMapping.get(iEnq).getTriggerStage(),
                                                       ""),
                                               String.format("%s_valid", enqSlotWireName),
                                               ExpressionType.WireName));
          ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(
                                                       queueDesc.pushAddrRespNode,
                                                       queueDesc.addrMapping.get(iEnq).getTriggerStage(),
                                                       ""),
                                               enqSlotWireName,
                                               ExpressionType.WireName));
        }


        String queueValidSetFF = String.format("always_ff @(posedge %s) begin\n", language.clk);
        queueValidSetFF += tab + String.format("if (%s) begin\n", language.reset);
        //queueValidSetFF += tab + tab + String.format("%s_level <= 0;\n", queueStorageName);
        for (int i = 0; i < queueDesc.depth; ++i) {
          queueValidSetFF += tab + tab + String.format("%s[%d].valid <= 0;\n", queueStorageName, i);
          queueValidSetFF += tab + tab + String.format("%s[%d].data_present <= 0;\n", queueStorageName, i);
        }
        queueValidSetFF += tab + "end\n";
        queueValidSetFF += tab + "else begin\n";
        for (int iEnq = 0; iEnq < numConcurrentEnqs; ++iEnq) {
          String enqSlotWireName = getEnqSlotWireName.apply(iEnq);
          String enqReq = enqPushRequests.get(iEnq);
          NodeInstanceDesc enqData = enqPushData.get(iEnq);
          int dataLen = queueDesc.regIDLen + queueDesc.forwardIDLen;
          if (enqData.getKey().getNode().size != dataLen) {
            logger.warn("WriteForwardQueue: Wrong data size (got {}, expected {}) for node key {}", enqData.getKey().getNode().size,
                        dataLen, enqData.getKey().toString());
          }
          String instrID = addrStageIDs.get(iEnq).getExpression();

          queueValidSetFF +=
              tab + tab + String.format("if (%s_valid && %s) begin : %s_enq%d_scope\n", enqSlotWireName, enqReq, enqSlotWireName, iEnq);

          queueValidSetFF += tab + tab + tab + String.format("logic [%d-1:0] enq_data;\n", instrIDLen + queueDesc.regIDLen);
          if (queueDesc.regIDLen > 0)
            queueValidSetFF += tab + tab + tab + String.format("enq_data = {%s,%s};\n", instrID, enqData.getExpression());
          else
            queueValidSetFF += tab + tab + tab + String.format("enq_data = %s;\n", instrID);

          queueValidSetFF +=
              tab + tab + tab + String.format("%s[%s].valid <= !(%s);\n", queueStorageName, enqSlotWireName, addrStageStallConds.get(iEnq));
          queueValidSetFF += tab + tab + tab + String.format("%s[%s].flushing <= 0;\n", queueStorageName, enqSlotWireName);
          if (hasWriteResp)
            queueValidSetFF += tab + tab + tab + String.format("%s[%s].pending <= 0;\n", queueStorageName, enqSlotWireName);
          queueValidSetFF += tab + tab + String.format("%s[%s].data_present <= 0;\n", queueStorageName, enqSlotWireName);
          if (queueDesc.regIDLen > 0) {
            queueValidSetFF +=
                tab + tab + tab + String.format("%s[%s].regID <= enq_data[%d-1:0];\n", queueStorageName, enqSlotWireName, queueDesc.regIDLen);
          }
          int pushDataOffs = queueDesc.regIDLen;
          if (supportsWriteOrdering) {
            queueValidSetFF += tab + tab + tab + String.format("%s[%s].is_overwritten <= 0;\n", queueStorageName, enqSlotWireName);
            for (int i = 0; i < queueDesc.forwardIDCount; ++i) {
              queueValidSetFF += tab + tab + tab + String.format("%s[%s].forwardTo[%d] <= '1;\n", queueStorageName, enqSlotWireName, i);
            }
            queueValidSetFF += tab + tab + tab + String.format("%s[%s].ref_queueid <= '1;\n", queueStorageName, enqSlotWireName);
            if (maxQueueDepth > 1)
              queueValidSetFF += tab + tab + tab + String.format("%s[%s].ref_inqueueidx <= 'x;\n", queueStorageName, enqSlotWireName);
            queueValidSetFF += tab + tab + tab + String.format("%s[%s].ref_supersededby_queueid <= '1;\n", queueStorageName, enqSlotWireName);
            if (maxQueueDepth > 1)
              queueValidSetFF +=
                  tab + tab + tab + String.format("%s[%s].ref_supersededby_inqueueidx <= 'x;\n", queueStorageName, enqSlotWireName);
          }
          queueValidSetFF += tab + tab + tab +
                             String.format("%s[%s].instrID <= enq_data[%d-1:%d];\n", queueStorageName, enqSlotWireName,
                                           pushDataOffs + instrIDLen, pushDataOffs);

          queueValidSetFF += tab + tab + "end\n";

          // Stall if there is an enqueue request but no queue slot was found (so far)
          if (needsStallIfNoFreeSlot) {
            SCAIEVNode stallNode = queueDesc.addrMapping.get(iEnq).node_response_stall.orElse(bNodes.WrStall);
            if (iEnq >= wrstallAuxsByAddrfront.size()) {
              assert (iEnq == wrstallAuxsByAddrfront.size());
              wrstallAuxsByAddrfront.add(
                  registry.deduplicateNodeKeyAux(new NodeInstanceDesc.Key(stallNode, queueDesc.addrMapping.get(iEnq).getTriggerStage(), "")).getAux());
            }
            int wrstallAux = wrstallAuxsByAddrfront.get(iEnq);
            ret.outputs.add(
                new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, stallNode, queueDesc.addrMapping.get(iEnq).getTriggerStage(), "", wrstallAux),
                                     String.format("(!%s_valid && %s)", enqSlotWireName, enqReq), ExpressionType.AnyExpression_Noparen));
          }
        }
        //Process external write requests to fields of the queue.
        for (NodeInstanceDesc.Key wrValidRespKey : queueDesc.requestedWrResps) {
          NodeInstanceDesc writeDataInst = registry.lookupRequired(
              new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN, queueDesc.wrNode, wrValidRespKey.getStage(),
                                       wrValidRespKey.getISAX(), wrValidRespKey.getAux()));
          int writeDataSize = writeDataInst.getKey().getNode().size;
          String writeData = writeDataInst.getExpression();
          String writeReq = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN,
                                                                                       queueDesc.wrReqNode, wrValidRespKey.getStage(),
                                                                                       wrValidRespKey.getISAX(), wrValidRespKey.getAux()));
          NodeInstanceDesc writeAddrInst = registry.lookupRequired(
              new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN, queueDesc.wrAddrNode, wrValidRespKey.getStage(),
                                       wrValidRespKey.getISAX(), wrValidRespKey.getAux()));
          String writeAddr = writeAddrInst.getExpression();
          if (writeAddrInst.getKey().getNode().size != Log2.clog2(queueDesc.depth)) {
            logger.warn("WriteQueue: Wrong address size (got {}, expected {}) for key {}", writeAddrInst.getKey().getNode().size,
                        Log2.clog2(queueDesc.depth), writeAddrInst.getKey().toString());
          }

          int expectedSize = -1;
          String fieldExpr = wrValidRespKey.getISAX();
          switch (wrValidRespKey.getISAX()) {
          case "valid":
            expectedSize = 1;
            break;
          case "flushing":
            expectedSize = 1;
            break;
          case "is_overwritten":
            if (supportsWriteOrdering)
              expectedSize = 1;
            break;
          case "pending":
            if (hasWriteResp)
              expectedSize = 1;
            else {
              logger.error("WriteQueue: Queue {} does not have a pending field (request from key {})", queueDesc.queueName,
                           wrValidRespKey.toString());
            }
            break;
          case "regID":
            expectedSize = queueDesc.regIDLen;
            break;
          case "instrID":
            expectedSize = instrIDLen;
            break;
          case "ref_queueid":
          case "ref_supersededby_queueid":
            if (supportsWriteOrdering)
              expectedSize = Log2.clog2(queueIDCount);
            break;
          case "ref_inqueueidx":
          case "ref_supersededby_inqueueidx":
            if (supportsWriteOrdering)
              expectedSize = Log2.clog2(maxQueueDepth);
            break;
          case "ref_scanned":
            if (supportsWriteOrdering)
              expectedSize = 1;
            break;
          case "data_present":
            expectedSize = 1;
            break;
          case "data":
            expectedSize = queueDesc.regWidth;
            break;
          default:
            if (supportsWriteOrdering && wrValidRespKey.getISAX().startsWith("forwardTo")) {
              // forwardTo0..forwardTo<N-1>
              try {
                int idx = Integer.parseInt(wrValidRespKey.getISAX().substring("forwardTo".length()));
                if (idx < 0 || idx >= queueDesc.forwardIDCount) {
                  logger.error("WriteQueue: Out of range forwardTo index %d (from key {})\n", idx, wrValidRespKey.toString());
                  break;
                }
                fieldExpr = String.format("forwardTo[%d]", idx);
                expectedSize = queueDesc.forwardIDLen;
                break;
              } catch (NumberFormatException e) {
              }
            }
          }
          if (expectedSize == -1) {
            logger.error("WriteQueue: Unrecognized structure key {} (from key {})\n", wrValidRespKey.getISAX(), wrValidRespKey.toString());
          }
          if (expectedSize >= 0 && writeDataSize != expectedSize) {
            logger.error("WriteQueue: Wrong {} size for queue {} (got {}, expected {}) (from key {})\n", wrValidRespKey.getISAX(),
                         queueDesc.queueName, writeDataSize, expectedSize, wrValidRespKey.toString());
          }
          if (expectedSize > 0) {
            queueValidSetFF += tab + tab + String.format("if (%s) begin\n", writeReq);
            queueValidSetFF += tab + tab + tab + String.format("%s[%s].%s <= %s;\n", queueStorageName, writeAddr, fieldExpr, writeData);
            queueValidSetFF += tab + tab + "end\n";
          }
        }

        for (int iCommit = 0; iCommit < queueDesc.commitMapping.size(); ++iCommit) {
          String isDiscardExpr = registry.lookupRequired(queueDesc.commitIsDiscardKeys.get(iCommit)).getExpressionWithParens();
          // Check for common constant expressions
          boolean alwaysIsCommit = (isDiscardExpr.equals("0") || isDiscardExpr.equals("1'b0"));
          boolean alwaysIsDiscard = (isDiscardExpr.equals("1") || isDiscardExpr.equals("1'b1"));
          IDSource commitSource = queueDesc.commitMapping.get(iCommit);

          var innerIDInst = registry.lookupRequired(commitSource.makeKey_RdInnerID(0, 0));
          //assert innerIDInst node: size==innerIDWidth, elements==innerIDCount
          String innerID = innerIDInst.getExpression();
          String innerID_valid = registry.lookupExpressionRequired(commitSource.makeKey_RdInnerIDValid());

          String sourceStallingCond = buildExpr_SourceStalling(registry, commitSource, false);

          //Stall the retire if data is missing
          // (given that the entry is not flushed/cancelled already
          //  and the retire itself is not a discard)
          var stallKey = new NodeInstanceDesc.Key(commitSource.node_response_stall.orElse(bNodes.WrStall), commitSource.getTriggerStage(), "");
          registry.lookupRequired(stallKey);
          var stallKey_write = NodeInstanceDesc.Key.keyWithPurposeAux(stallKey, Purpose.REGULAR, aux);
          ret.outputs.add(new NodeInstanceDesc(
                                stallKey_write,
                                String.format("%1$s && %2$s[%3$s].valid && !%2$s[%3$s].data_present && !%2$s[%3$s].flushing && !%4$s",
                                              innerID_valid, queueStorageName, innerID, isDiscardExpr),
                                ExpressionType.AnyExpression));

          queueValidSetFF += tab + tab + String.format("if (%s && !(%s)) begin\n", innerID_valid, sourceStallingCond);
          assert(hasWriteResp);
          queueValidSetFF += tab + tab + tab + String.format("%s[%s].valid <= 0;", queueStorageName, innerID);
          queueValidSetFF += tab + tab + "end\n";

          if (supportsWriteOrdering) {
            //TODO: Write back the correct data, or flush and write back from the previous entry, ...
          }
        }

        if (supportsWriteOrdering) {
          // TODO: Handle forwarding - efficient search through the write queue
          // TODO: for numConcurrentEnqs>1 (i.e. multi-issue cores), handle WaW / sorting between parallel enqueues
          //       also handle RaW where R and W are in different address stages in parallel
        }

        queueValidSetFF += tab + "end\n";
        queueValidSetFF += "end\n";
        ret.logic += queueValidSetFF;

        ret.outputs.add(
            new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.REGULAR), queueStorageName, ExpressionType.WireName));
        return ret;
      });
      out.accept(queueDesc.queueBuilder);
      return true;
    }

    return false;
  }
}
