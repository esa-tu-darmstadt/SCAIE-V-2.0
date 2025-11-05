package scaiev.scal.strategy.state;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.pipeline.PipelineStage.StageTag;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistry;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.SCALUtil;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.strategy.pipeline.IDMapperStrategy;
import scaiev.scal.strategy.pipeline.IDRetireSerializerStrategy;
import scaiev.scal.strategy.state.SCALStateStrategy.CommitHandler;
import scaiev.scal.strategy.state.SCALStateStrategy.ReadForwardCond;
import scaiev.scal.strategy.state.SCALStateStrategy.ReadForwardHandler;
import scaiev.scal.strategy.state.SCALStateStrategy.ReadreqExpr;
import scaiev.scal.strategy.state.SCALStateStrategy.RegInternalUtils;
import scaiev.scal.strategy.state.SCALStateStrategy.RegfileInfo;
import scaiev.scal.strategy.state.SCALStateStrategy.ResetDirtyCond;
import scaiev.scal.strategy.state.SCALStateStrategy.WriteToBackingCond;
import scaiev.scal.strategy.state.SCALStateStrategy.WritebackExpr;
import scaiev.scal.strategy.state.WriteQueues.WriteQueueDesc;
import scaiev.util.Log2;
import scaiev.util.Verilog;

/**
 * Scoreboarding for SCALStateStrategy, with retire/discard support.
 * <br/>
 * CommitHandler: {@link SCALStateStrategy_IDBasedDHCommit.IDBasedDHCommitHandler}
 * <br/>
 * Additional object to feed into IDBasedDHCommitHandler: {@link SCALStateStrategy_IDBasedDHCommit.DH_IDMapping}
 */
public class SCALStateStrategy_IDBasedDHCommit {
  interface IDIsValidCallback {
    /**
     * Builds the 'inner ID is valid' expression.
     * Should be true iff the inner ID points to a valid reg-DH/scoreboard entry.
     * @param idExpr the inner ID expression (with parens if needed)
     * @param registry node registry
     * @param logicBlock logic block, to add any additional logic
     * @return the condition expression (with parens if needed)
     */
    String buildIDIsValidCond(String idExpr, NodeRegistryRO registry, NodeLogicBlock logicBlock);
  }

  /**
   * ID-based data hazard logic base, connects the IDRetireSerializerStrategy to an IDMapperStrategy.
   * Per Register File.
   */
  protected static class DH_IDMapping {
    Core core;
    Verilog language;
    BNode bNodes;

    public IDMapperStrategy mapper;
    public List<IDMapperStrategy.IDSource> assignSources;
    public List<IDMapperStrategy.IDSource> serializedRetireSources;
    public int innerIDWidth;
    public int innerRetireChannels;
    RegInternalUtils utils;
    IDRetireSerializerStrategy serializer;
    RegfileInfo regfile;
    List<IDIsValidCallback> isValidRetireCallbacks = new ArrayList<>();
    private SCAIEVNode node_out_relevant;
    private SCAIEVNode node_out_mapperIsFlushing;
    private int aux = 0;
    private int[] aux_retire;
    /**
     * Adds a callback that determines whether a given inner ID points to a valid reg-DH entry.
     */
    public void addIsValidRetireCallback(IDIsValidCallback callb) {
      isValidRetireCallbacks.add(callb);
    }

    /**
     * 
     * @param serializer
     * @param retireChannels the max. number of retire channels to generate for the outer core IDs
     * @param innerRetireChannels the number of inner retire channels
     *                            (usually 1 -> physical writeback channel)
     * @param innerIDWidth the width of the inner ID space, used for the construction of an internal mapper
     * @param utils
     * @param regfile
     */
    DH_IDMapping(Core core, Verilog language, BNode bNodes,
        IDRetireSerializerStrategy serializer,
        int retireChannels, int innerRetireChannels, int innerIDWidth,
        RegInternalUtils utils,
        RegfileInfo regfile) {
      this.core = core;
      this.language = language;
      this.bNodes = bNodes;

      this.node_out_relevant = new SCAIEVNode("Regfile_DH_Issue_relevant_" + regfile.regName);
      this.node_out_mapperIsFlushing = new SCAIEVNode("Regfile_Commit_flushing_" + regfile.regName);

      this.utils = utils;
      this.regfile = regfile;
      this.serializer = serializer;
      this.innerIDWidth = innerIDWidth;
      this.innerRetireChannels = innerRetireChannels;
      if (retireChannels <= 0)
        throw new IllegalArgumentException("retireChannels must be at least 1");
      if (innerIDWidth <= 0)
        throw new IllegalArgumentException("innerIDWidth must be at least 1");
      if (innerRetireChannels <= 0)
        throw new IllegalArgumentException("innerRetireChannels must be at least 1");
      List<PipelineStage> assignStages = regfile.issueFront.asList();
      this.assignSources = IntStream.range(0, assignStages.size()).mapToObj(iAssign -> {
        var idSource = new IDMapperStrategy.IDSource(
            new NodeInstanceDesc.Key(bNodes.RdIssueID, assignStages.get(iAssign), ""),
            Optional.empty(),
            new NodeInstanceDesc.Key(node_out_relevant, assignStages.get(iAssign), ""),
            Optional.empty());
        return idSource;
      }).toList();
      serializer.registerRetireConsumer(retireChannels);
      int channels_actual = Math.min(retireChannels, serializer.getSignals().getWidthLimit());
      assert(channels_actual > 0);
      serializedRetireSources = new ArrayList<>(channels_actual);
      for (int i = 0; i < channels_actual; ++i) {
        var key_ID = serializer.getSignals().getKey_retireID(i);
        var key_valid = serializer.getSignals().getKey_valid(i);
        var key_stall = serializer.getSignals().getKey_stall(i);
        //var key_isDiscard = serializer.getSignals().getKey_isDiscard(i);
        assert(key_stall.getStage() == key_ID.getStage());
        assert(key_stall.getISAX().equals(key_ID.getISAX()));
        serializedRetireSources.add(new IDMapperStrategy.IDSource(
                                         key_ID,
                                         Optional.of(key_valid),
                                         null,
                                         Optional.of(key_stall.getNode())));
      }
      this.mapper = new IDMapperStrategy(language, bNodes,
          assignStages.get(0).getTags().contains(StageTag.Issue)
            ? Optional.empty()
            : Optional.of(new NodeInstanceDesc.Key(bNodes.RdIssueFlushID, assignStages.get(0), "")),
          //Key to set to whether the serializedRetireSources have a discard.
          Optional.of(new NodeInstanceDesc.Key(this.node_out_mapperIsFlushing, core.GetRootStage(), "")),
          innerIDWidth,
          assignSources,
          true, true, //May be core-specific
          serializedRetireSources);
      this.aux_retire = null;
    }

    NodeLogicBlock build(NodeRegistryRO registry) {
      var ret = new NodeLogicBlock();
      final String tab = language.tab;

      if (this.aux == 0) {
        this.aux = registry.newUniqueAux();
        this.aux_retire = IntStream.range(0, this.serializedRetireSources.size())
                                   .map(i->registry.newUniqueAux()).toArray();
      }

      String isDiscarding_wireName = String.format("regCommit_%s_isDiscard", regfile.regName);
      String[] validExprs = new String[this.serializedRetireSources.size()];

      // Manage discards, set IDMapperStrategy's key_isFlushing appropriately
      // -> If the first retire is a discard, stall anything that is not a discard
      //    and the other way round.
      ret.declarations += String.format("logic %s;\n", isDiscarding_wireName);
      String retireSum_wireName = ""; int retireSum_width = 0;
      if (this.serializedRetireSources.size() > this.innerRetireChannels) {
        retireSum_wireName = String.format("regCommit_%s_tmp_sum", regfile.regName);
        retireSum_width = Log2.clog2(this.serializedRetireSources.size());
        ret.declarations += String.format("logic [%d-1:0] %s;\n", retireSum_width, retireSum_wireName);
      }
      String[] stallWires = new String[this.serializedRetireSources.size()];
      String flushLogicInit = "";
      flushLogicInit += tab + String.format("%s = 0;\n", isDiscarding_wireName);
      if (!retireSum_wireName.isEmpty())
        flushLogicInit += tab + String.format("%s = 0;\n", retireSum_wireName);
      String flushLogic = "";
      for (int i = 0; i < this.serializedRetireSources.size(); ++i) {
        var retireSource = this.serializedRetireSources.get(i);
        String isDiscardExpr = registry.lookupRequired(serializer.getSignals().getKey_isDiscard(i)).getExpressionWithParens();

        assert(retireSource.key_valid.isPresent());
        validExprs[i] = registry.lookupRequired(retireSource.key_valid.get()).getExpressionWithParens();
        String validExpr = validExprs[i];
        if (mapper.hasDedicatedInvalidID()) {
          // The ID can be disregarded if it is not mapped.
          String mappingValidExpr = registry.lookupRequired(mapper.addTranslatedIDSource(retireSource.key_ID, retireSource.key_valid)
                                                                  .makeKey_RdInnerIDValid())
                                            .getExpressionWithParens();
          validExpr += " && " + mappingValidExpr;
        }
        else {
          String innerIDExpr = registry.lookupRequired(mapper.addTranslatedIDSource(retireSource.key_ID, retireSource.key_valid)
                                                             .makeKey_RdInnerID(0, 0)).getExpressionWithParens();
          String isValidCond = isValidRetireCallbacks.stream()
              .map(callb -> callb.buildIDIsValidCond(innerIDExpr, registry, ret))
              .reduce((a,b) -> a + " || " +b)
              .map(a -> "("+a+")")
              .orElse("1'b0");
          validExpr += " && " + isValidCond;
        }

        if (i == 0) {
          flushLogic += tab + String.format("%s = %s;\n", isDiscarding_wireName, isDiscardExpr);
        }

        // serializer should ensure valid[i+1] => valid[i].
        if (i > 0)
          flushLogic += tab + String.format("if (%s && !%s) $error(\"SCALState DH: Got an invalid retire serialization\");\n",
                                            validExprs[i], validExprs[i-1]);

        if (i > 0) {
          var stallKey = new NodeInstanceDesc.Key(retireSource.node_response_stall.orElse(bNodes.WrStall), retireSource.getTriggerStage(), "");
          stallWires[i] = String.format("regCommit_%s_stall_%d", regfile.regName, i);
          ret.declarations += String.format("logic %s;\n", stallWires[i]);
          String mismatchingDiscardExpr = String.format("%s && %s != %s", validExpr, isDiscarding_wireName, isDiscardExpr);
          String stallCond = "";
          if (i > 1)
            stallCond = stallWires[i-1];
          stallCond += (stallCond.isEmpty() ? "" : " || ") + mismatchingDiscardExpr;
          if (!retireSum_wireName.isEmpty()) {
            stallCond += (stallCond.isEmpty() ? "" : " || ")
                         + String.format("%s >= %d'd%d", retireSum_wireName, retireSum_width, this.innerRetireChannels);
          }
          flushLogic += tab + String.format("%s = %s;\n", stallWires[i], stallCond);

          registry.lookupExpressionRequired(stallKey);
          ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurposeAux(stallKey, Purpose.REGULAR, this.aux_retire[i]),
                                               stallWires[i],
                                               ExpressionType.WireName));
        }
        if (!retireSum_wireName.isEmpty()) {
          flushLogic += tab + String.format("%s = %s + %d'(%s);\n",
                                            retireSum_wireName,
                                            retireSum_wireName,
                                            retireSum_width, validExpr);
        }
      }
      ret.logic += String.format("always_comb begin\n%s%send\n", flushLogicInit, flushLogic);
      ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(node_out_mapperIsFlushing, core.GetRootStage(), ""),
          isDiscarding_wireName,
          ExpressionType.WireName));

      //Generate issue relevance conditions for the mapper.
      for (PipelineStage assignStage : regfile.issueFront.asList()) {
        String isRelevant_wireName = String.format("regIssue_%s_isRelevant_%s", regfile.regName, assignStage.getName());
        ret.declarations += String.format("logic %s;\n", isRelevant_wireName);
        String relevantExpr = IntStream.range(0, utils.writeNodes.size())
                                       .mapToObj(iNode -> utils.getWireName_WrIssue_addr_valid_perstage(iNode, assignStage))
                                       .reduce((a,b) -> a+" || "+b)
                                       .orElse("0");
        ret.logic += String.format("assign %s = %s;\n", isRelevant_wireName, relevantExpr);
        ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(node_out_relevant, assignStage, ""),
                                             isRelevant_wireName,
                                             ExpressionType.WireName));

        //Stall assign while discarding a retire
        registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrStall, assignStage, ""));
        ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrStall, assignStage, "", aux),
                                             isRelevant_wireName + " && " + isDiscarding_wireName + " && " + validExprs[0],
                                             ExpressionType.AnyExpression));
      }
      return ret;
    }

    void implementInner(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
      this.mapper.implement(out, nodeKeys, isLast);
    }
  }

  protected static class IDBasedDHForwardHandler extends ReadForwardHandler {
    Core core;
    Verilog language;
    BNode bNodes;

    RegfileInfo regfile;
    RegInternalUtils utils;

    /**
     * @param core
     * @param language
     * @param bNodes
     * @param regfile
     * @param utils
     */
    IDBasedDHForwardHandler(Core core, Verilog language, BNode bNodes, RegfileInfo regfile, RegInternalUtils utils) {
      this.core = core;
      this.language = language;
      this.bNodes = bNodes;
      this.regfile = regfile;
      this.utils = utils;
    }

    private List<Integer> queueReadAux = new ArrayList<>();

    private static record QueueWriteCond (int queueIdx, String validCond, String regAddrExpr, String queueAddrExpr) {}
    List<QueueWriteCond> queueWriteConds = new ArrayList<>();
    List<WriteQueueDesc> queueDescs = new ArrayList<>();
    List<ReadForwardCond> forwards = new ArrayList<>();
    int queueMaxDepth = 0;
    @Override
    void reset() {
      queueWriteConds.clear();
      queueDescs.clear();
      forwards.clear();
      queueMaxDepth = 0;
    }

    /**
     * Processes a push into a write queue
     * @param queue
     * @param assignStage
     * @param validCond expression: push is valid and not stalling/being flushed
     * @param regAddrExpr expression: register address
     * @param queueAddrExpr expression: write queue index
     * @param registry
     */
    void processQueueWriteIssue(WriteQueueDesc queue, PipelineStage assignStage, String validCond, String regAddrExpr, String queueAddrExpr,
                                NodeRegistryRO registry) {
      int queueIdx = queueDescs.indexOf(queue);
      if (queueIdx == -1) {
        queueIdx = queueDescs.size();
        queueDescs.add(queue);

        if (queueReadAux.size() <= queueIdx)
          queueReadAux.add(registry.newUniqueAux());
        assert(queueReadAux.size() >= queueDescs.size());
      }
      queueMaxDepth = Math.max(queueMaxDepth, queue.depth);
      queueWriteConds.add(new QueueWriteCond(queueIdx, validCond, regAddrExpr, queueAddrExpr));
    }

    private String getForwardTableName() {
      return "regForward_%s_forwardTable".formatted(regfile.regName);
    }

    @Override
    void processReadPort(int iPort, ReadreqExpr readRequest, NodeRegistryRO registry, NodeLogicBlock logicBlock) {
      if (queueDescs.size() == 0 || queueMaxDepth == 0)
        return;
      int queueIDWidth = Log2.clog2(queueDescs.size()+1);
      int queueAddrWidth = Log2.clog2(queueMaxDepth);

      //Access the mapping table for the read request, forward if there is a valid queue entry with data present.

      String validWire = "regForward_%s_%d_valid".formatted(regfile.regName, iPort);
      logicBlock.declarations += "logic %s;\n".formatted(validWire);
      String dataWire = "regForward_%s_%d_data".formatted(regfile.regName, iPort);
      logicBlock.declarations += "logic [%d-1:0] %s;\n".formatted(regfile.width, dataWire);

      String forwardFromWire = "regForward_%s_%d_forwardFrom".formatted(regfile.regName, iPort);
      logicBlock.declarations += "logic [%d+%d-1:0] %s;\n".formatted(queueIDWidth, queueAddrWidth, forwardFromWire);
      logicBlock.logic += "assign %s = %s[%s];\n".formatted(forwardFromWire, getForwardTableName(), readRequest.addrExpr());
      String forwardFromWire_queueID = "%s[%d-1:%d]".formatted(forwardFromWire, queueIDWidth+queueAddrWidth, queueAddrWidth);
      String forwardFromWire_queueAddr = (queueMaxDepth > 1) ? "%s[%d-1:0]".formatted(forwardFromWire, queueAddrWidth) : "0";

      String forwardLogic = """
          always_comb begin
              %s = 1'b0;
              %s = '0;
              unique case (%s)
          """.formatted(validWire, dataWire, forwardFromWire_queueID);
      for (int iQueue = 0; iQueue < queueDescs.size(); ++iQueue) {
        WriteQueueDesc queueDesc = queueDescs.get(iQueue);
        int aux = queueReadAux.get(iQueue);

        var rdaddrKey = new NodeInstanceDesc.Key(Purpose.REGULAR, queueDesc.getRdAddrNode(), core.GetRootStage(), "", aux);
        logicBlock.outputs.add(new NodeInstanceDesc(rdaddrKey, forwardFromWire_queueAddr, ExpressionType.AnyExpression));
        var rdKey = new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN, queueDesc.getRdNode(), core.GetRootStage(), "", aux);
        String queueEntry = registry.lookupExpressionRequired(rdKey);
        String queueAddrValidCond = "";
        if (queueMaxDepth > 1 && ((1 << queueAddrWidth) > queueDesc.depth)) {
          queueAddrValidCond = "%s < %d'd%d && ".formatted(forwardFromWire_queueAddr, queueAddrWidth, queueDesc.depth);
        }
        if (regfile.depth > 1) {
          queueAddrValidCond = "%s == %s.regID && %s".formatted(readRequest.addrExpr(), queueEntry, queueAddrValidCond);
        }

        forwardLogic += """
                    %1$d'd%2$d: if (%3$s.valid && %3$s.data_present) begin
                        %4$s = %5$s;
                        %6$s = %3$s.data;
                    end
            """.formatted(queueIDWidth, iQueue, queueEntry, //1,2,3
                          validWire, queueAddrValidCond + readRequest.addrValidExpr(), //4,5
                          dataWire); //6
      }
      forwardLogic += """
                  default: ;
              endcase
          end
          """;
      logicBlock.logic += forwardLogic;
      forwards.add(new ReadForwardCond(validWire, dataWire, iPort));
    }

    @Override
    List<ReadForwardCond> getForwardConds() {
      return forwards;
    }

    @Override
    void buildPost(NodeRegistryRO registry, NodeLogicBlock logicBlock) {
      if (queueDescs.size() == 0 || queueMaxDepth == 0)
        return;
      int queueIDWidth = Log2.clog2(queueDescs.size()+1);
      int queueAddrWidth = Log2.clog2(queueMaxDepth);
      String tableName = getForwardTableName();

      //Build the register -> {write queue ID, address in write queue} mapping table
      logicBlock.declarations += "logic [%d+%d-1:0] %s [%d];\n".formatted(queueIDWidth, queueAddrWidth, tableName, regfile.depth);
      String tableLogic = """
          always_ff @(posedge %1$s) begin
              if (%2$s) begin%3$s
              end
              else begin
          """.formatted(language.clk, language.reset,
                        IntStream.range(0, regfile.depth)
                                 .mapToObj(i->"\n        %s[%d] <= '1;".formatted(tableName, i))
                                 .reduce((a,b)->a+b).orElse(" ;"));
      for (var writeCond : queueWriteConds) {
        tableLogic += """
                    if (%s) begin
                        %s[%s] <= {%d'd%d, %d'(%s)};
                    end
            """.formatted(writeCond.validCond,
                          tableName, regfile.depth > 1 ? writeCond.regAddrExpr : "0",
                          queueIDWidth, writeCond.queueIdx,
                          queueAddrWidth, writeCond.queueAddrExpr);
      }
      tableLogic += """
              end
          end
          """;
      logicBlock.logic += tableLogic;
    }
  }

  protected static class IDBasedDHCommitHandler extends CommitHandler {
    Core core;
    Verilog language;
    BNode bNodes;

    DH_IDMapping baseLogic;
    WriteQueues writeQueues;
    RegfileInfo regfile;

    List<ResetDirtyCond> resetDirtyConds = new ArrayList<>();
    List<WriteToBackingCond> writeToBackingConds = new ArrayList<>();
    List<WriteQueueDesc> queueDescs = new ArrayList<>();

    SCAIEVNode node_out_relevant;
    SCAIEVNode node_writeback_id_valid;
    RegInternalUtils utils;
    private int aux = 0;
    private List<int[]> retireAux = new ArrayList<>();
    private List<int[]> assignAux = new ArrayList<>();
    private IDBasedDHForwardHandler readForwarder = null;

    /**
     * @param core
     * @param language
     * @param bNodes
     * @param baseLogic the DH_IDMapping object. Assumes this is the only CommitHandler using it.
     *                  Note: {@link IDBasedDHCommitHandler#implementInner(Consumer, Iterable, boolean)} will call baseLogic.implementInner(...).
     * @param regfile
     * @param utils
     */
    IDBasedDHCommitHandler(Core core, Verilog language, BNode bNodes, DH_IDMapping baseLogic, RegfileInfo regfile, RegInternalUtils utils) {
      this.core = core;
      this.language = language;
      this.bNodes = bNodes;
      this.baseLogic = baseLogic;
      this.writeQueues = new WriteQueues(core, language, bNodes, regfile.regName);
      this.regfile = regfile;
      this.node_out_relevant = new SCAIEVNode("Regfile_DH_Issue_relevant_" + regfile.regName);
      this.node_writeback_id_valid = new SCAIEVNode("Regfile_DH_Write_valid_or_cancel_" + regfile.regName);
      this.utils = utils;
    }
    /**
     * Returns a forwarder for this object.
     * NOTE: The forwarder assumes it is the only one of its kind for the register file.
     */
    ReadForwardHandler getReadForwarder() {
      if (this.readForwarder == null)
        this.readForwarder = new IDBasedDHForwardHandler(core, language, bNodes, regfile, utils);
      return this.readForwarder;
    }

    @Override
    void reset() {
      resetDirtyConds.clear();
      writeToBackingConds.clear();
    }
    /** Performs an additional build step after all processWritePort calls have been made. */
    @Override
    void buildPost(NodeRegistryRO registry, NodeLogicBlock logicBlock) {
      logicBlock.addOther(this.baseLogic.build(registry));
    }

    /** Processes a write port. */
    @Override
    void processWritePort(int iPort, WritebackExpr writeback, NodeRegistryRO registry, NodeLogicBlock logicBlock) {
      if (this.aux == 0) {
        this.aux = registry.newUniqueAux();
      }
      if (iPort >= queueDescs.size() || queueDescs.get(iPort) == null) {
        // Register the write port; Setup the write queue.
        baseLogic.assignSources.forEach(source -> {
          assert(source.key_relevant.getPurpose().matches(Purpose.REGULAR));
          assert(source.key_relevant.getNode().equals(baseLogic.node_out_relevant));
          assert(source.key_relevant.getISAX().isEmpty());
          assert(source.key_relevant.getAux() == 0);
        });
        List<IDMapperStrategy.IDSource> assignSources_specificRelevance = 
            baseLogic.assignSources.stream().map(source -> new IDMapperStrategy.IDSource(
                                                               source,
                                                               new NodeInstanceDesc.Key(Purpose.REGULAR,
                                                                                        baseLogic.node_out_relevant,
                                                                                        source.key_relevant.getStage(),
                                                                                        "",
                                                                                        iPort + 1))).toList();
        var queue = new WriteQueueDesc(
            regfile.regName + "_" + iPort, //-> queueName
            iPort, //-> physicalWriteChannel
            this.baseLogic.mapper.getInnerIDCount(), //-> depth
            0, 0, //-> forwardIDLen, forwardIDCount
            regfile.width, //-> regWidth
            Log2.clog2(regfile.depth), //-> regIDLen
            assignSources_specificRelevance, //-> addrMapping
            baseLogic.serializedRetireSources, //-> commitMapping
            IntStream.range(0, baseLogic.serializedRetireSources.size())
                     .mapToObj(i -> baseLogic.serializer.getSignals().getKey_isDiscard(i))
                     .toList() //-> commitIsDiscardKeys
            );
        while (iPort >= queueDescs.size()) {
          queueDescs.add(null);
          assignAux.add(null);
          retireAux.add(null);
        }
        retireAux.set(iPort, IntStream.range(0, baseLogic.serializedRetireSources.size())
                            .map(i->registry.newUniqueAux())
                            .toArray());
        assignAux.set(iPort, IntStream.range(0, baseLogic.assignSources.size())
                             .map(i->registry.newUniqueAux())
                             .toArray());
        queueDescs.set(iPort, queue);
        writeQueues.addWriteQueue(queue);
        int id_valid_aux = registry.newUniqueAux();
        //Provide the 'inner ID valid' logic to baseLogic.
        baseLogic.addIsValidRetireCallback(new IDIsValidCallback() {
          @Override
          public String buildIDIsValidCond(String idExpr, NodeRegistryRO registry, NodeLogicBlock logicBlock) {
            var rdaddrKey = new NodeInstanceDesc.Key(Purpose.REGULAR,
                                                     queue.getRdAddrNode(), core.GetRootStage(), "", id_valid_aux);
            logicBlock.outputs.add(new NodeInstanceDesc(rdaddrKey, idExpr, ExpressionType.AnyExpression));

            var rdKey = new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN,
                                                 queue.getRdNode(), core.GetRootStage(), "", id_valid_aux);
            String queueEntryExpr = registry.lookupExpressionRequired(rdKey);
            return queueEntryExpr + ".valid";
          }
        });
      }

      var queue = queueDescs.get(iPort);
      int iNode = utils.writeNodes.indexOf(writeback.baseNode);
      assert(iNode != -1);
      // The logic below assumes that the writeback write corresponds exactly to a port.
      assert(regfile.writeback_writes.stream().filter(write -> write.getNode().equals(writeback.baseNode)).count() == 1);

      IDMapperStrategy.IDSource translatedWriteback = null;
      boolean writebackDuringAssign = false;
      //The 'write queue push' condition in case we have writebackDuringAssign.
      String queuePushValidCond_concurrentWriteback = null;

      String issueRegAddr = utils.getWireName_WrIssue_addr(iNode);
      for (int i = 0; i < this.baseLogic.assignSources.size(); ++i) {
        var assignSource = this.baseLogic.assignSources.get(i);
        //Generate the per-writeback queue relevance signals.
        String relevanceWireName = utils.getWireName_WrIssue_addr_valid_perstage(iNode, assignSource.getTriggerStage());
        var specificRelevanceKey = new NodeInstanceDesc.Key(Purpose.REGULAR,
                                                            baseLogic.node_out_relevant,
                                                            assignSource.key_relevant.getStage(),
                                                            "",
                                                            iPort + 1);
        logicBlock.outputs.add(new NodeInstanceDesc(specificRelevanceKey, relevanceWireName, ExpressionType.AnyExpression_Noparen));

        //Generate the queue 'push' inputs to allocate a scoreboard entry.
        PipelineStage stage = assignSource.getTriggerStage();
        var queuePushRegAddrKey = new NodeInstanceDesc.Key(queue.getPushDataNode(), stage, "");
        logicBlock.outputs.add(new NodeInstanceDesc(queuePushRegAddrKey, issueRegAddr, ExpressionType.AnyExpression_Noparen));

        var queuePushReqKey = new NodeInstanceDesc.Key(queue.getPushReqNode(), stage, "");
        String validCond = assignSource.key_valid.map(key -> registry.lookupRequired(key).getExpressionWithParens())
                                                  .orElseGet(() -> SCALUtil.buildCond_StageNotStalling(bNodes, registry, stage, true));
        validCond = validCond + " && " + relevanceWireName;
        String nativeStallOrFlush = "";
        if (assignSource.node_response_stall.isPresent() && !assignSource.node_response_stall.get().equals(bNodes.WrStall)) {
          var stallKey = new NodeInstanceDesc.Key(assignSource.node_response_stall.get(), stage, "");
          validCond = validCond + " && !" + registry.lookupRequired(stallKey).getExpressionWithParens();
        }
        else {
          nativeStallOrFlush = SCALUtil.buildCond_StageNotStalling(bNodes, registry, stage, true);
        }
        logicBlock.outputs.add(new NodeInstanceDesc(queuePushReqKey, validCond, ExpressionType.AnyExpression));

        //Apply backpressure if the queue isn't ready.
        // -> This will affect validCond, but readyCond for this assignSource should not be affected.
        String readyCond = registry.lookupRequired(new NodeInstanceDesc.Key(queue.getPushReadyNode(), stage, "")).getExpressionWithParens();
        logicBlock.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR,
                                                                             assignSource.node_response_stall.orElse(bNodes.WrStall),
                                                                             stage,
                                                                             "",
                                                                             assignAux.get(iPort)[i]),
                                                    "!"+readyCond+" && "+relevanceWireName,
                                                    ExpressionType.AnyExpression));

        //Assertion: Scoreboard index equal to inner ID
        String pushedInto = registry.lookupRequired(new NodeInstanceDesc.Key(queue.getPushAddrRespNode(), stage, "")).getExpressionWithParens();
        String assignedInnerID = registry.lookupRequired(assignSource.makeKey_RdInnerID(0, 0)).getExpressionWithParens();
        logicBlock.logic += """
            `ifndef SYNTHESIS
            always_ff @(posedge %1$s) begin
                if (%2$s === 1'b0 && %4$s && %5$s != %6$s)
                    $error("SCAL Custom reg %3$s: Mismatch between write queue index and inner ID");
            end
            `endif
            """.formatted(language.clk, language.reset, regfile.regName, validCond, pushedInto, assignedInnerID);

        if (this.readForwarder != null) {
          String validAndNotFlush = validCond;
          if (!nativeStallOrFlush.isEmpty())
            validAndNotFlush += " && " + nativeStallOrFlush;
          this.readForwarder.processQueueWriteIssue(queue, stage, validAndNotFlush, issueRegAddr, pushedInto, registry);
        }

        if (stage == writeback.stage) {
          //Assuming no duplicate assigns per stage to the same writeback port node.
          assert(translatedWriteback == null);
          translatedWriteback = assignSource;
          writebackDuringAssign = true;
          queuePushValidCond_concurrentWriteback = validCond;
        }
      }
      if (translatedWriteback == null) {
        //If the writeback is not in the assign stage, add a translation.
        var instrIDKey = new NodeInstanceDesc.Key(
            bNodes.GetAdjSCAIEVNode(writeback.baseNode, AdjacentNode.instrID).orElseThrow(),
            writeback.stage,
            "");

        //The ID is assumed valid if validReq || cancelReq. Build the condition for that.
        var validOrCancelKey = new NodeInstanceDesc.Key(Purpose.REGULAR, node_writeback_id_valid, writeback.stage, "", iPort);
        String validExpr = registry.lookupRequired(new NodeInstanceDesc.Key(
            bNodes.GetAdjSCAIEVNode(writeback.baseNode, AdjacentNode.validReq).orElseThrow(),
            writeback.stage,
            "")).getExpressionWithParens();
        var cancelInst = registry.lookupRequired(new NodeInstanceDesc.Key(
              bNodes.GetAdjSCAIEVNode(writeback.baseNode, AdjacentNode.cancelReq).orElseThrow(),
              writeback.stage,
            ""));
        String cancelExpr = (cancelInst.getExpression().startsWith(NodeRegistry.MISSING_PREFIX)) ? "1'b0" : cancelInst.getExpressionWithParens();
        logicBlock.outputs.add(new NodeInstanceDesc(validOrCancelKey,
                                                    "%s || %s".formatted(validExpr, cancelExpr),
                                                    ExpressionType.AnyExpression));

        translatedWriteback = this.baseLogic.mapper.addTranslatedIDSource(instrIDKey, Optional.of(validOrCancelKey));
      }

      //For now, assume there is one writeback per reg channel (per cycle).
      var rdaddrOnWritebackKey = new NodeInstanceDesc.Key(Purpose.REGULAR, queue.getRdAddrNode(), core.GetRootStage(), "", aux);
      var rddataOnWritebackKey = new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN, queue.getRdNode(), core.GetRootStage(), "", aux);
      String rddataOnWritebackExpr = registry.lookupRequired(rddataOnWritebackKey).getExpressionWithParens();
      String translatedIDValidExpr = registry.lookupRequired(translatedWriteback.makeKey_RdInnerIDValid()).getExpressionWithParens();
      String translatedIDExpr = registry.lookupRequired(translatedWriteback.makeKey_RdInnerID(0,0)).getExpressionWithParens();
      logicBlock.outputs.add(new NodeInstanceDesc(rdaddrOnWritebackKey, translatedIDExpr, ExpressionType.AnyExpression_Noparen));

      //Assertions
      if (writebackDuringAssign) {
        logicBlock.logic += """
            `ifndef SYNTHESIS
            always_ff @(posedge %1$s) begin
                if (%2$s === 1'b0 && (%4$s || %5$s) && !(%6$s)) begin
                    if (!%7$s)
                        $error("SCAL Custom reg %3$s: Writeback without inner ID assignment");
                    else if (%8$s.valid)
                        $error("SCAL Custom reg %3$s: Writeback-during-assign with already-valid scoreboard entry");
                    else if (!(%9$s))
                        $error("SCAL Custom reg %3$s: Writeback-during-assign without concurrent scoreboard push request");
                end
            end
            `endif
            """.formatted(language.clk, language.reset, regfile.regName, //1,2,3
                writeback.wrValidExpr, writeback.wrCancelExpr, writeback.stallDataStageCond, //4,5,6
                translatedIDValidExpr, //7
                rddataOnWritebackExpr, //8
                queuePushValidCond_concurrentWriteback //9
                );
      }
      else {
        logicBlock.logic += """
            `ifndef SYNTHESIS
            always_ff @(posedge %1$s) begin
                if (%2$s === 1'b0 && (%4$s || %5$s) && !(%6$s)) begin
                    if (!%7$s)
                        $error("SCAL Custom reg %3$s: Writeback without inner ID assignment");
                    else if (!%8$s.valid)
                        $error("SCAL Custom reg %3$s: Writeback with invalid scoreboard entry");
                    else begin
                        %9$s
                            $error("SCAL Custom reg %3$s: Mismatching register ID in scoreboard");
                        if (%8$s.instrID != %10$s)
                            $error("SCAL Custom reg %3$s: Mismatching instruction ID in scoreboard");
                    end
                end
            end
            `endif
            """.formatted(language.clk, language.reset, regfile.regName, //1,2,3
                writeback.wrValidExpr, writeback.wrCancelExpr, writeback.stallDataStageCond, //4,5,6
                translatedIDValidExpr, //7
                rddataOnWritebackExpr, //8
                (regfile.depth > 1) ? String.format("if (%s.regID != %s)", rddataOnWritebackExpr, writeback.wrAddrExpr) : "if (1'b0)", //9
                registry.lookupRequired(translatedWriteback.key_ID).getExpressionWithParens() //10
                );
      }

      // Apply register write to WriteQueue/scoreboard.
      registry.lookupRequired(new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN,
                                                       queue.getWrRespNode(), core.GetRootStage(), "data_present", aux));
      registry.lookupRequired(new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN,
                                                       queue.getWrRespNode(), core.GetRootStage(), "data", aux));
      registry.lookupRequired(new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN,
                                                       queue.getWrRespNode(), core.GetRootStage(), "flushing", aux));
      //- Queue write address = inner ID
      var wraddrOnWritebackKey_data        = new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN,
                                                                      queue.getWrAddrNode(), core.GetRootStage(), "data", aux);
      var wraddrOnWritebackKey_datapresent = new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN,
                                                                      queue.getWrAddrNode(), core.GetRootStage(), "data_present", aux);
      var wraddrOnWritebackKey_flushing    = new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN,
                                                                      queue.getWrAddrNode(), core.GetRootStage(), "flushing", aux);
      logicBlock.outputs.add(new NodeInstanceDesc(wraddrOnWritebackKey_data, translatedIDExpr, ExpressionType.AnyExpression_Noparen));
      logicBlock.outputs.add(new NodeInstanceDesc(wraddrOnWritebackKey_datapresent, translatedIDExpr, ExpressionType.AnyExpression_Noparen));
      logicBlock.outputs.add(new NodeInstanceDesc(wraddrOnWritebackKey_flushing, translatedIDExpr, ExpressionType.AnyExpression_Noparen));
      //- Queue write data.
      var wrdataOnWritebackKey_data        = new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN,
                                                                      queue.getWrNode(regfile.width), core.GetRootStage(), "data", aux);
      var wrdataOnWritebackKey_datapresent = new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN,
																	  queue.getWrNode(1), core.GetRootStage(), "data_present", aux);
      var wrdataOnWritebackKey_flushing    = new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN,
																	  queue.getWrNode(1), core.GetRootStage(), "flushing", aux);
      logicBlock.outputs.add(new NodeInstanceDesc(wrdataOnWritebackKey_data, writeback.wrDataExpr, ExpressionType.AnyExpression));
      logicBlock.outputs.add(new NodeInstanceDesc(wrdataOnWritebackKey_datapresent, "!"+writeback.wrCancelExpr, ExpressionType.AnyExpression));
      logicBlock.outputs.add(new NodeInstanceDesc(wrdataOnWritebackKey_flushing, "1'b1", ExpressionType.AnyExpression_Noparen));
      //- Queue write valid condition. Update data and set data_present on 'valid || cancel'; clear data_present and set flushing on cancel.
      var wrreqOnWritebackKey_data        = new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN,
																	 queue.getWrReqNode(), core.GetRootStage(), "data", aux);
      var wrreqOnWritebackKey_datapresent = new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN,
																	 queue.getWrReqNode(), core.GetRootStage(), "data_present", aux);
      var wrreqOnWritebackKey_flushing    = new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN,
																	 queue.getWrReqNode(), core.GetRootStage(), "flushing", aux);
      String writebackValidOrCancelCond = String.format("(%s || %s) && !(%s)", writeback.wrValidExpr, writeback.wrCancelExpr, writeback.stallDataStageCond);
      logicBlock.outputs.add(new NodeInstanceDesc(wrreqOnWritebackKey_data, writebackValidOrCancelCond, ExpressionType.AnyExpression));
      logicBlock.outputs.add(new NodeInstanceDesc(wrreqOnWritebackKey_datapresent, writebackValidOrCancelCond, ExpressionType.AnyExpression));
      String writebackCancelCond = String.format("%s && !(%s)", writeback.wrCancelExpr, writeback.stallDataStageCond, writeback.flushDataStageCond);
      logicBlock.outputs.add(new NodeInstanceDesc(wrreqOnWritebackKey_flushing, writebackCancelCond, ExpressionType.AnyExpression));

      //Selection logic for the retire to apply.
      String selectedCommitIsDiscardWire = String.format("regCommit_%s_isDiscard_%d", regfile.regName, iPort);
      String selectedCommitScoreboardValidWire = String.format("regCommit_%s_scoreboardValid_%d", regfile.regName, iPort);
      String selectedCommitScoreboardCancelWire = String.format("regCommit_%s_scoreboardCancel_%d", regfile.regName, iPort);
      String selectedCommitScoreboardDataWire = String.format("regCommit_%s_scoreboardData_%d", regfile.regName, iPort);
      String selectedCommitScoreboardRegIDWire = String.format("regCommit_%s_scoreboardRegID_%d", regfile.regName, iPort);
      logicBlock.declarations += String.format("logic %s;\n", selectedCommitIsDiscardWire);
      logicBlock.declarations += String.format("logic %s;\n", selectedCommitScoreboardValidWire);
      logicBlock.declarations += String.format("logic %s;\n", selectedCommitScoreboardCancelWire);
      logicBlock.declarations += String.format("logic [%d-1:0] %s;\n", regfile.width, selectedCommitScoreboardDataWire);
      if (regfile.depth > 1)
        logicBlock.declarations += String.format("logic [%d-1:0] %s;\n", Log2.clog2(regfile.depth), selectedCommitScoreboardRegIDWire);
      String commitCombLogic = """
          always_comb begin
              %1$s = 1'b0;
              %2$s = 1'b0;
              %3$s = 1'b0;
              %4$s = 'x;
          """.formatted(selectedCommitIsDiscardWire,
              selectedCommitScoreboardValidWire,
              selectedCommitScoreboardCancelWire,
              selectedCommitScoreboardDataWire);
      if (regfile.depth > 1)
        commitCombLogic += "    %s = '0;\n".formatted(selectedCommitScoreboardRegIDWire);
      for (int i = 0; i < this.baseLogic.serializedRetireSources.size(); ++i) {
        var retireSource = this.baseLogic.serializedRetireSources.get(i);
        String isDiscardExpr = registry.lookupRequired(this.baseLogic.serializer.getSignals().getKey_isDiscard(i)).getExpressionWithParens();
        var translatedCommit = this.baseLogic.mapper.addTranslatedIDSource(retireSource.key_ID, retireSource.key_valid);

        //Read the scoreboard entry, retrieve the valid and stall conditions
        //-> RdInnerValid already checks for RdStall (but not for WrStall)
        String commitIDValidExpr = registry.lookupRequired(translatedCommit.makeKey_RdInnerIDValid()).getExpressionWithParens();
        String commitIDExpr = registry.lookupRequired(translatedCommit.makeKey_RdInnerID(0,0)).getExpressionWithParens();
        //ASSUMPTION: The serialized retires are not in the same cycle as the assign stage.
        var rdaddrOnCommitKey = new NodeInstanceDesc.Key(Purpose.REGULAR,
                                                         queue.getRdAddrNode(), core.GetRootStage(), "",
                                                         retireAux.get(iPort)[i]);
        var rddataOnCommitKey = new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN,
                                                         queue.getRdNode(), core.GetRootStage(), "",
                                                         retireAux.get(iPort)[i]);
        logicBlock.outputs.add(new NodeInstanceDesc(rdaddrOnCommitKey, commitIDExpr, ExpressionType.AnyExpression));
        String scoreboardEntryExpr = registry.lookupRequired(rddataOnCommitKey).getExpressionWithParens();
        String commitWrStalledExpr;
        commitWrStalledExpr = registry.lookupRequired(new NodeInstanceDesc.Key(
                                                                 retireSource.node_response_stall.orElse(bNodes.WrStall),
                                                                 translatedCommit.getTriggerStage(),
                                                                 "")).getExpressionWithParens();
        commitCombLogic += "    if (%s && !%s && %s.valid) begin\n".formatted(commitIDValidExpr, commitWrStalledExpr, scoreboardEntryExpr);
        commitCombLogic += """
            `ifndef SYNTHESIS
                    if (%s) $error("SCAL Custom reg %s: Got several matching retires (expected only one) that are not stalling");
            `endif
            """.formatted(selectedCommitScoreboardValidWire, regfile.regName);
        commitCombLogic += """
                    %s = %s;
                    %s = 1'b1;
                    %s = %s.flushing;
                    %s = %s.data;
            """.formatted(selectedCommitIsDiscardWire, isDiscardExpr,
                selectedCommitScoreboardValidWire,
                selectedCommitScoreboardCancelWire, scoreboardEntryExpr,
                selectedCommitScoreboardDataWire, scoreboardEntryExpr);
        if (regfile.depth > 1)
          commitCombLogic += "    %s = %s.regID;\n".formatted(selectedCommitScoreboardRegIDWire, scoreboardEntryExpr);
        //WriteQueues is supposed to stall the retire if data_present is missing.
        // This point in logic should only be reached if the retire is not stalling.
        commitCombLogic += """
            `ifndef SYNTHESIS
                    if (!%s && !%s && !%s.data_present) $error("SCAL Custom reg %3$s: Missing scoreboard data");
            `endif
            """.formatted(selectedCommitIsDiscardWire, selectedCommitScoreboardCancelWire, scoreboardEntryExpr, regfile.regName);

        commitCombLogic += "    end\n";
      }
      commitCombLogic += "end\n";
      logicBlock.logic += commitCombLogic;
      //- WriteQueue already invalidates the scoreboard entry
      //Given there is no retire stall:
      //- On retire (either way), feed the dirtyCond
      //- On retire (no discard), feed the scoreboard entries into writeCond
      //- On retire (with discard), writeCond should be false
      ResetDirtyCond dirtyCond = new ResetDirtyCond();
      dirtyCond.baseNode = writeback.baseNode;
      dirtyCond.condExpr = selectedCommitScoreboardValidWire;
      dirtyCond.addrExpr = selectedCommitScoreboardRegIDWire;
      resetDirtyConds.add(dirtyCond);
      WriteToBackingCond writeCond = new WriteToBackingCond();
      writeCond.condExpr = String.format("%s && !%s && !%s",
                                         selectedCommitScoreboardValidWire,
                                         selectedCommitIsDiscardWire,
                                         selectedCommitScoreboardCancelWire);
      writeCond.addrExpr = selectedCommitScoreboardRegIDWire;
      writeCond.dataExpr = selectedCommitScoreboardDataWire;
      writeCond.iPort = iPort;
      writeToBackingConds.add(writeCond);
      if (!writeback.validRespWireName.isEmpty()) {
        String writebackPassingCond = String.format("(%s || %s) && !(%s) && !(%s)",
                                                    writeback.wrValidExpr, writeback.wrCancelExpr,
                                                    writeback.stallDataStageCond, writeback.flushDataStageCond);
        logicBlock.logic += String.format("assign %s = %s;\n", writeback.validRespWireName, writebackPassingCond);
      }
    }
    @Override
    List<ResetDirtyCond> getResetDirtyConds() {
      return resetDirtyConds;
    }
    @Override
    List<WriteToBackingCond> getWriteToBackingConds() {
      return writeToBackingConds;
    }
    @Override
    public void implementInner(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
      baseLogic.implementInner(out, nodeKeys, isLast);
      Iterator<NodeInstanceDesc.Key> nodeKeyIter = nodeKeys.iterator();
      while (nodeKeyIter.hasNext()) {
        var nodeKey = nodeKeyIter.next();
        if (this.writeQueues.implementSingle(out, nodeKey, isLast)) {
          nodeKeyIter.remove();
        }
      }
    }
  }
}
