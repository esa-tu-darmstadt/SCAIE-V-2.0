package scaiev.backend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.frontend.FNode;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.frontend.SCAIEVNode.NodeTypeTag;

/***********************
 *
 * Backend supported SCAIEV nodes. 'isInput' in nodes is from the core's point of view.
 *
 */
public class BNode extends FNode {
  // logging
  protected static final Logger logger = LogManager.getLogger();

  // To add a new node, add it here and in GetAllBackNodes.
  // If you need a node to have valid and addr signals, add validSuffix & addrSuffix to their names. This suffix is used in GenerateText
  // class and other classes. If you don't use this pattern, some functions might not work. This could be improved in future versions of the
  // tool Please consider that _valid , _addr nodes are used in backend just for the names , in order to generate interf text & logic. They
  // will not be in op_stage_instr, because it would be redundant. We would have same data stored multiple times in op_stage_instr
  public static String validSuffix = AdjacentNode.validReq.suffix;
  public static String addrSuffix = AdjacentNode.addr.suffix;

  public SCAIEVNode WrRD_valid = new SCAIEVNode(WrRD, AdjacentNode.validReq, 1, true, false);
  public SCAIEVNode WrRD_validData = new SCAIEVNode(WrRD, AdjacentNode.validData, 1, true, false) {
    {
      noInterfToISAX = true;
      DH = true;
    }
  };
  public SCAIEVNode WrRD_addr = new SCAIEVNode(WrRD, AdjacentNode.addr, 5, true, false) {
    { validBy = AdjacentNode.addrReq; }
  }; // JUST for dynamic decoupled wrrd
  public SCAIEVNode WrRD_addr_valid = new SCAIEVNode(WrRD, AdjacentNode.addrReq, 5, true, false);
  public SCAIEVNode RdMem_validReq = new SCAIEVNode(RdMem, AdjacentNode.validReq, 1, true, false);
  public SCAIEVNode WrMem_validReq = new SCAIEVNode(WrMem, AdjacentNode.validReq, 1, true, false);

  public SCAIEVNode RdMem_validResp = new SCAIEVNode(RdMem, AdjacentNode.validResp, 1, false, false) {
    {
      oneInterfToISAX = true;
      tags.add(NodeTypeTag.defaultNotprovidedByCore);
    }
  };
  public SCAIEVNode WrMem_validResp = new SCAIEVNode(WrMem, AdjacentNode.validResp, 1, false, false) {
    {
      oneInterfToISAX = true;
      tags.add(NodeTypeTag.defaultNotprovidedByCore);
    }
  };
  public SCAIEVNode RdMem_defaultAddr = new SCAIEVNode(RdMem, AdjacentNode.defaultAddr, datawidth, false, false) {
    {
      noInterfToISAX = true;
      tags.add(NodeTypeTag.staticReadResult);
    }
  }; // Provided by SCAL, core can output efficient version by setting mustToCore=true or adding the node to Core.
  public SCAIEVNode RdMem_addr = new SCAIEVNode(RdMem, AdjacentNode.addr, datawidth, true, false) {
    { validBy = AdjacentNode.addrReq; }
  };

  public SCAIEVNode RdMem_size = new SCAIEVNode(RdMem, AdjacentNode.size, 3, true, false) {
    {
      validBy = AdjacentNode.addrReq;
      mustToCore = true;
    }
  }; // funct3 value
  public SCAIEVNode RdMem_addr_valid = new SCAIEVNode(RdMem, AdjacentNode.addrReq, 1, true, false);
  public SCAIEVNode WrMem_defaultAddr = new SCAIEVNode(WrMem, AdjacentNode.defaultAddr, datawidth, false, false) {
    {
      noInterfToISAX = true;
      tags.add(NodeTypeTag.staticReadResult);
    }
  }; // If the core provides RdMem_defaultAddr, this must also be provided.
  public SCAIEVNode WrMem_addr = new SCAIEVNode(WrMem, AdjacentNode.addr, datawidth, true, false) {
    { validBy = AdjacentNode.addrReq; }
  };
  public SCAIEVNode WrMem_size = new SCAIEVNode(WrMem, AdjacentNode.size, 3, true, false) {
    {
      validBy = AdjacentNode.addrReq;
      mustToCore = true;
    }
  }; // funct3 value
  public SCAIEVNode WrMem_addr_valid = new SCAIEVNode(WrMem, AdjacentNode.addrReq, 1, true, false);
  // public static SCAIEVNode RdMem_validResp   = new SCAIEVNode(RdMem      , AdjacentNode.validResp, 1 , false, false) ; // valdir read
  // data
  public SCAIEVNode WrPC_valid = new SCAIEVNode(WrPC, AdjacentNode.validReq, 1, true, false);
  public SCAIEVNode WrPC_spawn = new SCAIEVNode(WrPC, AdjacentNode.none, datawidth, true, true) {
    {
      oneInterfToISAX = true;
      allowMultipleSpawn = false;
      validBy = AdjacentNode.addrReq;
    }
  };
  public SCAIEVNode WrPC_spawn_valid = new SCAIEVNode(WrPC_spawn, AdjacentNode.validReq, 1, true, true) {
    {
      oneInterfToISAX = true;
      allowMultipleSpawn = false;
    }
  };
  public SCAIEVNode WrRD_spawn = new SCAIEVNode(WrRD, AdjacentNode.none, datawidth, true, true) {
    {
      DH = true;
      this.elements = 32;
      validBy = AdjacentNode.validReq;
    }
  };
  public SCAIEVNode WrRD_spawn_valid = new SCAIEVNode(WrRD_spawn, AdjacentNode.validReq, 1, true, true);
  public SCAIEVNode WrRD_spawn_validResp = new SCAIEVNode(WrRD_spawn, AdjacentNode.validResp, 1, false, true) {
    { oneInterfToISAX = false; }
  };
  public SCAIEVNode WrRD_spawn_addr = new SCAIEVNode(WrRD_spawn, AdjacentNode.addr, 5, true, true) {
    {
      noInterfToISAX = true;
      mandatory = true;
      this.validBy = AdjacentNode.validReq;
    }
  };
  //	public SCAIEVNode WrRD_spawn_addrCommited = new SCAIEVNode(WrRD_spawn, AdjacentNode.addrCommited, 5 , true, false);
  public SCAIEVNode WrRD_spawn_allowed = new SCAIEVNode(WrRD_spawn, AdjacentNode.spawnAllowed, 1, false, true);

  public SCAIEVNode RdMem_spawn = new SCAIEVNode(RdMem, AdjacentNode.none, datawidth, false, true) {
    {
      this.familyName = "Mem";
      oneInterfToISAX = false;
      nameCousinNode = "WrMem_spawn";
      allowMultipleSpawn = true;
    }
  }; // TODO unstable solution with nameCousin here
  // If an ISAX performs multiple memory accesses, it must wait for validResp of the previous one to prevent potential FIFO overflows in
  // SCAL.
  public SCAIEVNode RdMem_spawn_validReq = new SCAIEVNode(RdMem_spawn, AdjacentNode.validReq, 1, true, true);
  public SCAIEVNode RdMem_spawn_validResp = new SCAIEVNode(RdMem_spawn, AdjacentNode.validResp, 1, false, true) {
    { oneInterfToISAX = false; }
  };
  public SCAIEVNode RdMem_spawn_addr = new SCAIEVNode(RdMem_spawn, AdjacentNode.addr, datawidth, true, true) {
    { validBy = AdjacentNode.validReq; }
  };
  public SCAIEVNode RdMem_spawn_size = new SCAIEVNode(RdMem_spawn, AdjacentNode.size, 3, true, true) {
    {
      validBy = AdjacentNode.validReq;
      mustToCore = true;
    }
  }; // funct3 value

  public SCAIEVNode RdMem_spawn_defaultAddr = new SCAIEVNode(RdMem_spawn, AdjacentNode.defaultAddr, datawidth, false, true) {
    {
      noInterfToISAX = true; /*mustToCore = true;*/
      tags.add(NodeTypeTag.staticReadResult);
    }
  }; // Analogous to Rd/WrMem_defaultAddr
  public SCAIEVNode RdMem_spawn_write = new SCAIEVNode(RdMem_spawn, AdjacentNode.isWrite, 1, true, true) {
    {
      noInterfToISAX = true;
      mustToCore = true;
    }
  };
  public SCAIEVNode RdMem_spawn_allowed = new SCAIEVNode(RdMem_spawn, AdjacentNode.spawnAllowed, 1, false, true);

  public SCAIEVNode WrMem_spawn = new SCAIEVNode(WrMem, AdjacentNode.none, datawidth, true, true) {
    {
      familyName = "Mem";
      nameCousinNode = RdMem_spawn.name;
      allowMultipleSpawn = true;
      validBy = AdjacentNode.validReq;
    }
  };
  // If an ISAX performs multiple memory accesses, it must wait for validResp of the previous one to prevent potential FIFO overflows in
  // SCAL.
  public SCAIEVNode WrMem_spawn_validReq = new SCAIEVNode(WrMem_spawn, AdjacentNode.validReq, 1, true, true);
  public SCAIEVNode WrMem_spawn_addr = new SCAIEVNode(WrMem_spawn, AdjacentNode.addr, datawidth, true, true) {
    { validBy = AdjacentNode.validReq; }
  };
  public SCAIEVNode WrMem_spawn_size = new SCAIEVNode(WrMem_spawn, AdjacentNode.size, 3, true, true) {
    {
      validBy = AdjacentNode.validReq;
      mustToCore = true;
    }
  }; // funct3 value
  public SCAIEVNode WrMem_spawn_defaultAddr = new SCAIEVNode(WrMem_spawn, AdjacentNode.defaultAddr, datawidth, false, true) {
    {
      noInterfToISAX = true;
      tags.add(NodeTypeTag.staticReadResult);
    }
  }; // Analogous to Rd/WrMem_defaultAddr.
  public SCAIEVNode WrMem_spawn_validResp = new SCAIEVNode(WrMem_spawn, AdjacentNode.validResp, 1, false, true) {
    {
      oneInterfToISAX = false;
      mustToCore = true;
    }
  };
  public SCAIEVNode WrMem_spawn_write = new SCAIEVNode(WrMem_spawn, AdjacentNode.isWrite, 1, true, true) {
    {
      noInterfToISAX = true;
      mustToCore = true;
    }
  };
  public SCAIEVNode WrMem_spawn_allowed = new SCAIEVNode(WrMem_spawn, AdjacentNode.spawnAllowed, 1, false, true);

  // public static SCAIEVNode WrJump_spawn_valid  = new SCAIEVNode(WrJump		, AdjacentNode.validReq	, 1 , true, true)
  // {{this.oneInterfToISAX = true; this.allowMultipleSpawn = false;}};

  public SCAIEVNode WrCSR_valid = new SCAIEVNode(WrCSR, AdjacentNode.validReq, 1, true, false);

  public SCAIEVNode committed_rd_spawn = new SCAIEVNode("Committed_rd_spawn", 5, false);
  public SCAIEVNode committed_rd_spawn_valid = new SCAIEVNode(committed_rd_spawn, AdjacentNode.validReq, 1, false, false);
  /**
   * For {@link scaiev.scal.strategy.decoupled.DecoupledDHStrategy}:
   * The original logical ISA register number to unlock (decoupled stage) in the data hazard module.
   * If not given to the core, the default unlock register is from the lower bits of WrRD_spawn_addr.
   */
  public SCAIEVNode rd_dh_spawn_addr = new SCAIEVNode("Rd_dh_spawn_addr", 5, false);

  public SCAIEVNode cancel_from_user = new SCAIEVNode("cancel_frUser", 5, false);
  public SCAIEVNode cancel_from_user_valid = new SCAIEVNode(cancel_from_user, AdjacentNode.validReq, 1, false, false);

  // Local Signal Names used in all cores for logic  of spawn
  public SCAIEVNode ISAX_spawnAllowed = new SCAIEVNode("ISAX_spawnAllowed", 1, false) {
    { noInterfToISAX = true; }
  };

  public SCAIEVNode ISAX_spawnStall_regF_s = new SCAIEVNode("isax_spawnStall_regF_s", 1, false);
  public SCAIEVNode ISAX_spawnStall_mem_s = new SCAIEVNode("isax_spawnStall_mem_s", 1, false);
  public SCAIEVNode IsBranch = new SCAIEVNode("IsBranch", 1, false);
  public SCAIEVNode IsZOL = new SCAIEVNode("IsZOL", 1, false);

  // Deprecated
  /** Deprecated global RdStall, automatic conversion from RdStall in core description. */
  public SCAIEVNode RdStallLegacy = new SCAIEVNode("RdStallLegacy", 1, false) {
    {
      oneInterfToISAX = true;
      tags.add(NodeTypeTag.perStageStatus);
      tags.add(NodeTypeTag.noCoreInterface);
    }
  };

  // Core<->SCAL additional nodes
  /**
   * core->SCAL: For the given next stage, whether the instruction is being pipelined into that stage.
   */
  public SCAIEVNode RdPipeInto = new SCAIEVNode("RdPipeInto", 1, false);

  //	/**
  //	 * Flush the pipeline up until the current instruction in the given stage, rerunning the instruction from scratch.
  //	 * For most in-order cores, this is equivalent to WrFlush with accompanying WrPC(RdPC)
  //	 */
  //	public SCAIEVNode WrRerunCurrent = new SCAIEVNode("WrRerunCurrent", 1, true);
  /**
   * Flush the pipeline up until the instruction after the given stage's current instruction, rerunning the instructions from scratch.
   * For most in-order cores, this is equivalent to WrFlush in the previous stage with accompanying WrPC(RdPC_next).
   *  If RdPC_next is not known, the implementation could instead wait for the next instruction (with its PC) to materialize and then flush.
   */
  public SCAIEVNode WrRerunNext = new SCAIEVNode("WrRerunNext", 1, true);

  /**
   * The original PC before Fetch-stage WrPC.
   * Only present if WrPC was used in this "instruction"-'s lifetime
   * Generated as needed by DefaultRerunStrategy.
   */
  public SCAIEVNode RdOrigPC = new SCAIEVNode("RdOrigPC", datawidth, false) {{ validBy = AdjacentNode.validReq; }};
  public SCAIEVNode RdOrigPC_valid = new SCAIEVNode(RdOrigPC, AdjacentNode.validReq, 1, false, false);

  //	/**
  //	 * Sets the wait count for the current instruction entering the scoreboard.
  //	 * The core ensures that the instruction is not issued as long as the value is not zero.
  //	 * The node size is defined by SCAL.
  //	 */
  //	public SCAIEVNode WrInitScoreboardWaitCount = new SCAIEVNode("WrInitScoreboardWaitCount", 0, true);
  //	/**
  //	 * Reduces the wait count for the instruction given by the corresponding .
  //	 * The node is generally multi-ported. The node size is defined by SCAL.
  //	 */
  //	public SCAIEVNode WrReduceScoreboardWaitCount = new SCAIEVNode("WrReduceScoreboardWaitCount", 0, true);

  // Pipelined Execution Unit support
  /**
   * SCAL->core: The current ISAX instruction is entering the ISAX-pipeline, and the core is allowed to get the next instruction in the
   * current stage _without_ moving the current instruction further. Always comes with a WrStall. If the core does not support having
   * several instructions within the given stage, it can safely ignore this request.
   */
  public SCAIEVNode WrDeqInstr = new SCAIEVNode("WrDeqInstr", 1, true);

  /**
   * core->SCAL: The current instruction ID for differentiation between subsequent instructions. Does not have to be consistent across
   * stages of the core, and does not have to be continuous. If the core does not have a native instruction ID, it can also provide a
   * toggling bit to indicate when a new instruction has entered a stage. Whether the instruction actually is valid is to be determined
   * through RdStall.
   */
  public SCAIEVNode RdInStageID = new SCAIEVNode("RdInStageID", 0, false);
  /**
   * core->SCAL: Indicates valid for {@link BNode#WrInStageID} and all other non-handshakey read nodes associated with the instruction
   * (RdRS1, RdInstr, etc.) If not supported, will be set to !RdStall by SCAL.
   */
  public SCAIEVNode RdInStageValid = new SCAIEVNode("RdInStageValid", 1, false);
  /**
   * SCAL->core: The overridden instruction ID to commit / to leave the stage.
   *              If the core does not support having several instructions within the given stage, it can ignore this request (and commit
   * the current instruction eventually). Otherwise, the core should commit the instruction based on the given ID, comb. setting validResp
   * once SCAL no longer needs to hold the signal&validReq. WrStall in the same stage must not prevent WrInStageID from completing. Note:
   * The core should also set RdStall during WrInStageID if the overridden instruction ID is not the current instruction in the core
   * pipeline. Hint: SCAL only uses WrInStageID after a corresponding WrDeqInstr.
   */
  public SCAIEVNode WrInStageID = new SCAIEVNode("WrInStageID", 0, true);
  /** SCAL->core: validReq for {@link BNode#WrInStageID} */
  public SCAIEVNode WrInStageID_valid = new SCAIEVNode(WrInStageID, AdjacentNode.validReq, 1, true, false);
  /**
   * core->SCAL: validResp for {@link BNode#WrInStageID};
   * is allowed to be 1 spuriously as long as WrInStageID_validReq is not set;
   * when WrInStageID_validReq is set, must be equal to !RdStall&&!WrStall or !RdStall&&!WrStall&&!RdFlush&&!WrFlush
   * */
  public SCAIEVNode WrInStageID_validResp = new SCAIEVNode(WrInStageID, AdjacentNode.validResp, 1, false, false);

  /** ISAX->SCAL: ISAX commit marker */
  public SCAIEVNode WrCommit_spawn = new SCAIEVNode(WrCommit, AdjacentNode.none, 1, true, true) {
    { validBy = AdjacentNode.validReq; }
  };
  /** ISAX->SCAL: 'validReq' for ISAX commit marker, allowed exactly once (i.e. for one cycle) per ISAX */
  public SCAIEVNode WrCommit_spawn_validReq = new SCAIEVNode(WrCommit_spawn, AdjacentNode.validReq, 1, true, true);
  /** ISAX->SCAL: 'validReq' for ISAX commit marker */
  public SCAIEVNode WrCommit_spawn_validResp = new SCAIEVNode(WrCommit_spawn, AdjacentNode.validResp, 1, false, true);

  /** Pseudo-node for custom register read core constraints. */
  public SCAIEVNode RdCustReg_data_constraint = new SCAIEVNode("RdCustReg.data_constraint", 0, false) {
    { tags.add(NodeTypeTag.constraintMarkerOnlyNode); }
  };
  /** Pseudo-node for custom register read&write addr constraints. */
  public SCAIEVNode CustReg_addr_constraint = new SCAIEVNode("CustReg.addr_constraint", 0, true) {
    { tags.add(NodeTypeTag.constraintMarkerOnlyNode); }
  };
  /** Pseudo-node for custom register write data constraints. */
  public SCAIEVNode WrCustReg_data_constraint = new SCAIEVNode("WrCustReg.data_constraint", 0, true) {
    { tags.add(NodeTypeTag.constraintMarkerOnlyNode); }
  };

  public HashSet<SCAIEVNode> user_BNode = new HashSet<SCAIEVNode>();
  protected List<SCAIEVNode> core_BNode = new ArrayList<SCAIEVNode>();

  /**
   * Adds a core-specific SCAIEVNode.
   */
  public void AddCoreBNode(SCAIEVNode coreNode) {
    core_BNode.add(coreNode);
    refreshAllNodesSet();
  }

  /**
   * Adds a user-defined register node.
   */
  @Override
  public void AddUserNode(String name, int width, int elements) {
    SCAIEVNode RdNode = new SCAIEVNode(rdPrefix + name, width, false);
    RdNode.elements = elements;
    RdNode.tags.add(NodeTypeTag.staticReadResult); // Assuming there is only one global read stage/front.
    RdNode.tags.add(NodeTypeTag.supportsPortNodes);
    SCAIEVNode WrNode = new SCAIEVNode(wrPrefix + name, width, true) {
      { this.validBy = AdjacentNode.validReq; }
    };
    WrNode.elements = elements;
    WrNode.tags.add(NodeTypeTag.supportsPortNodes);
    WrNode.tags.add(NodeTypeTag.accumulatesUntilCommit);
    user_FNode.add(RdNode);
    user_FNode.add(WrNode);
    user_BNode.add(RdNode);
    user_BNode.add(WrNode);

    int addr_size = (int)Math.ceil((Math.log10(elements) / Math.log10(2)));
    user_BNode.add(new SCAIEVNode(WrNode, AdjacentNode.validReq, 1, true, false));
    user_BNode.add(new SCAIEVNode(WrNode, AdjacentNode.cancelReq, 1, true, false));
    user_BNode.add(new SCAIEVNode(RdNode, AdjacentNode.validReq, 1, true, false) {
      { noInterfToISAX = true; }
    });
    user_BNode.add(new SCAIEVNode(RdNode, AdjacentNode.validReq, 1, true, false) {
      { noInterfToISAX = true; }
    });
    // user_BNode.add(new SCAIEVNode(WrNode,AdjacentNode.validData , 1, true, false) {{noInterfToISAX = true;}});
    SCAIEVNode RdNode_Addr = new SCAIEVNode(RdNode, AdjacentNode.addr, addr_size, true, false) {
      { validBy = AdjacentNode.addrReq; }
    };
    user_BNode.add(RdNode_Addr);
    SCAIEVNode WrNode_Addr = new SCAIEVNode(WrNode, AdjacentNode.addr, addr_size, true, false) {
      { validBy = AdjacentNode.addrReq; }
    };
    user_BNode.add(WrNode_Addr);
    SCAIEVNode RdNode_AddrValid = new SCAIEVNode(RdNode, AdjacentNode.addrReq, 1, true, false);
    RdNode_AddrValid.elements = elements;
    SCAIEVNode WrNode_AddrValid = new SCAIEVNode(WrNode, AdjacentNode.addrReq, 1, true, false);
    WrNode_AddrValid.elements = elements;
    user_BNode.add(WrNode_AddrValid);
    user_BNode.add(RdNode_AddrValid);

    // Spawn just for write
    // Set allowMultipleSpawn COULD BE SET HERE to false, and in AddUserNodesToCore(..) we check how many isaxes need spawn and update this
    // param. For the moment by default true to generate in SCAL the fire logic
    SCAIEVNode WrNode_spawn = new SCAIEVNode(WrNode, AdjacentNode.none, width, true, true) {
      {
        oneInterfToISAX = false;
        allowMultipleSpawn = true;
        validBy = AdjacentNode.validReq;
      }
    };
    WrNode_spawn.tags.add(NodeTypeTag.supportsPortNodes);
    // removed 'DH = true;' in WrNode, WrNode_spawn and WrNode_validData
    user_BNode.add(WrNode_spawn);
    user_BNode.add(new SCAIEVNode(WrNode_spawn, AdjacentNode.validReq, 1, true, true) {
      {
        oneInterfToISAX = false;
        allowMultipleSpawn = true;
      }
    });
    user_BNode.add(new SCAIEVNode(WrNode_spawn, AdjacentNode.cancelReq, 1, true, true) {
      { allowMultipleSpawn = true; }
    });
    if (elements > 1) // Don.t generate addr signal for spawn if not required
      user_BNode.add(new SCAIEVNode(WrNode_spawn, AdjacentNode.addr, addr_size, true, true) {
        {
          oneInterfToISAX = false;
          allowMultipleSpawn = true;
          mandatory = true;
          validBy = AdjacentNode.validReq;
        }
      });
    user_BNode.add(new SCAIEVNode(WrNode_spawn, AdjacentNode.validResp, 1, false, true) {
      { oneInterfToISAX = false; }
    });
    //	user_BNode.add(new SCAIEVNode(WrNode_spawn  , AdjacentNode.spawnAllowed, 1, false, true)  {{noInterfToISAX = false;}}); // For the
    // moment by default always allowed for internal state. Yet, core spawnAllowed must still be checked due to stalling

    // Read spawn for direct reads (no DH )
    // SCAIEVNode  RdNode_spawn = new SCAIEVNode(RdNode ,       AdjacentNode.none		, width, false, true) {{oneInterfToISAX =
    // true; DH = false;}}; user_BNode.add(RdNode_spawn);

    refreshAllNodesSet();
  }

  /**
   * Adds a user-defined register node additional port.
   */
  @Override
  public SCAIEVNode AddUserNodePort(SCAIEVNode userNode, String portName) {
    if (!user_BNode.contains(userNode)) {
      logger.error("AddUserNodePort called on a non-registered user node {}", userNode.name);
      return null;
    }
    if (portName.isEmpty()) {
      throw new IllegalArgumentException("portName must not be empty");
    }
    SCAIEVNode portNode = SCAIEVNode.makePortNodeOf(userNode, portName);
    var existingPorts = GetAllPortsByBaseName().get(userNode.name);
    if (existingPorts != null) {
      Optional<SCAIEVNode> existingPortNode =
          existingPorts.stream().filter(existingPortNode_ -> existingPortNode_.name.equals(portNode.name)).findAny();
      if (existingPortNode.isPresent())
        return existingPortNode.get();
    }

    if (user_FNode.contains(userNode))
      user_FNode.add(portNode);
    user_BNode.add(portNode);
    for (SCAIEVNode adjNode : this.GetAdjSCAIEVNodes(userNode)) {
      user_BNode.add(SCAIEVNode.makePortNodeOf(adjNode, portName));
    }

    refreshAllNodesSet();
    return portNode;
  }

  public boolean IsUserBNode(SCAIEVNode node) { return user_BNode.contains(node); }
  private ArrayList<SCAIEVNode> allBackNodes = null;
  private HashMap<String, SCAIEVNode> allBackNodesByName = null;
  private HashMap<String, List<SCAIEVNode>> allPortsByBaseName = null;
  @Override
  protected void refreshAllNodesSet() {
    super.refreshAllNodesSet();
    allBackNodes = null;
    allBackNodesByName = null;
    allPortsByBaseName = null;
  }
  public List<SCAIEVNode> GetAllBackNodes() {
    if (allBackNodes != null)
      return allBackNodes;
    HashSet<SCAIEVNode> bnodes = new HashSet<>(GetAllFrontendNodes());
    if (!this.user_BNode.isEmpty())
      bnodes.addAll(this.user_BNode);
    bnodes.add(WrRD_valid);
    bnodes.add(WrRD_validData);
    //	bnodes.add(WrRD_addr);
    bnodes.add(RdMem_validReq);
    bnodes.add(RdMem_validResp);
    bnodes.add(RdMem_defaultAddr);
    bnodes.add(RdMem_addr);
    bnodes.add(RdMem_size);
    bnodes.add(RdMem_addr_valid);
    bnodes.add(WrMem_validReq);
    bnodes.add(WrMem_validResp);
    bnodes.add(WrMem_defaultAddr);
    bnodes.add(WrMem_addr);
    bnodes.add(WrMem_size);
    bnodes.add(WrMem_addr_valid);
    bnodes.add(WrPC_valid);
    bnodes.add(WrPC_spawn_valid);
    bnodes.add(WrPC_spawn);
    bnodes.add(WrRD_spawn_valid);
    bnodes.add(WrRD_spawn_addr);
    bnodes.add(WrRD_spawn_validResp);
    bnodes.add(WrRD_spawn);
    bnodes.add(WrRD_spawn_allowed);
    bnodes.add(RdMem_spawn_validResp);
    bnodes.add(RdMem_spawn_addr);
    bnodes.add(RdMem_spawn_size);
    bnodes.add(RdMem_spawn_defaultAddr);
    bnodes.add(RdMem_spawn);
    bnodes.add(RdMem_spawn_write);
    bnodes.add(RdMem_spawn_validReq);
    bnodes.add(RdMem_spawn_allowed);
    bnodes.add(WrMem_spawn);
    bnodes.add(WrMem_spawn_validReq);
    bnodes.add(WrMem_spawn_validResp);
    bnodes.add(WrMem_spawn_addr);
    bnodes.add(WrMem_spawn_size);
    bnodes.add(WrMem_spawn_defaultAddr);
    bnodes.add(WrMem_spawn_write);
    bnodes.add(WrMem_spawn_allowed);
    // bnodes.add(WrJump_spawn_valid);

    bnodes.add(WrCSR_valid);
    bnodes.add(committed_rd_spawn);
    bnodes.add(committed_rd_spawn_valid);
    bnodes.add(ISAX_spawnAllowed);
    bnodes.add(ISAX_spawnStall_regF_s);
    bnodes.add(ISAX_spawnStall_mem_s);

    bnodes.add(IsBranch);
    bnodes.add(IsZOL);

    bnodes.add(RdPipeInto);

    bnodes.add(WrRerunNext);

    bnodes.add(RdOrigPC);
    bnodes.add(RdOrigPC_valid);

    bnodes.add(WrDeqInstr);
    bnodes.add(RdInStageID);
    bnodes.add(RdInStageValid);
    bnodes.add(WrInStageID);
    bnodes.add(WrInStageID_valid);
    bnodes.add(WrInStageID_validResp);

    bnodes.add(WrCommit_spawn);
    bnodes.add(WrCommit_spawn_validReq);
    bnodes.add(WrCommit_spawn_validResp);

    bnodes.add(RdStallLegacy);
    bnodes.add(RdCustReg_data_constraint);
    bnodes.add(CustReg_addr_constraint);
    bnodes.add(WrCustReg_data_constraint);

    bnodes.addAll(core_BNode);
    allBackNodes = new ArrayList<>(bnodes);
    allBackNodesByName = new HashMap<>();
    allPortsByBaseName = new HashMap<>();

    allBackNodes.stream().forEach(bnode -> {
      if (allBackNodesByName.put(bnode.name, bnode) != null) {
        // Assumption broken - Another node with that name exists.
        // Nodes should have equals and hashCode determined by name only.
        assert (false);
      }
      if (bnode.tags.contains(NodeTypeTag.isPortNode) && !bnode.isAdj()) {
        // Name should look like <node name>_port<port name>
        assert (bnode.name.startsWith(bnode.nameParentNode + SCAIEVNode.portbaseSuffix) &&
                SCAIEVNode.portnamePattern.matcher(bnode.name.substring(bnode.nameParentNode.length() + SCAIEVNode.portbaseSuffix.length()))
                    .matches());
        allPortsByBaseName.computeIfAbsent(bnode.nameParentNode, _name -> new ArrayList<>()).add(bnode);
      }
    });
    return Collections.unmodifiableList(allBackNodes);
  }
  public Map<String, SCAIEVNode> GetAllBackNodesByName() {
    if (allBackNodesByName == null)
      GetAllBackNodes();
    assert (allBackNodesByName != null);
    return Collections.unmodifiableMap(allBackNodesByName);
  }
  /** Returns the map from the base node name (e.g. WrCustomReg) to its port base nodes (e.g. WrCustomReg_port0) */
  public Map<String, List<SCAIEVNode>> GetAllPortsByBaseName() {
    if (allPortsByBaseName == null)
      GetAllBackNodes();
    assert (allPortsByBaseName != null);
    return Collections.unmodifiableMap(allPortsByBaseName);
  }

  /** Searches a SCAIEVNode with the given name. As default, returns a SCAIEVNode with an empty name. */
  public SCAIEVNode GetSCAIEVNode(String nodeName) {
    SCAIEVNode ret = GetAllBackNodesByName().get(nodeName);
    if (ret == null)
      return new SCAIEVNode("", 0, false);
    return ret;
  }
  /** Searches a SCAIEVNode with the given name. Returns Optional.empty() if no such node exists. */
  public Optional<SCAIEVNode> GetSCAIEVNode_opt(String nodeName) {
    SCAIEVNode ret = GetAllBackNodesByName().get(nodeName);
    if (ret == null)
      return Optional.empty();
    return Optional.of(ret);
  }

  /** Tests if a SCAIEVNode exists with the given name under BNode. */
  public boolean HasSCAIEVBNode(String nodeName) { return GetAllBackNodesByName().containsKey(nodeName); }
  @Override
  public boolean HasSCAIEVNode(String nodeName) {
    return HasSCAIEVBNode(nodeName);
  }

  /**
   * Returns AdjacentNodes present for given SCAIEVNode. For exp. in case of WrPC: validReq (valid request bit)
   * @return
   */
  public ArrayList<AdjacentNode> GetAdj(SCAIEVNode look4Node) {
    ArrayList<AdjacentNode> returnList = new ArrayList<>();
    for (AdjacentNode possibleAdj : AdjacentNode.values())
      if (possibleAdj != AdjacentNode.none) {
        if (GetAdjSCAIEVNode(look4Node, possibleAdj).isPresent())
          returnList.add(possibleAdj);
      }
    return returnList;
  }

  /**
   * Returns adjacent SCAIEVNodes of given SCAIEVNode. For exp. in case of WrPC: WrPC_validReq
   * @return
   */
  public ArrayList<SCAIEVNode> GetAdjSCAIEVNodes(SCAIEVNode look4Node) {
    ArrayList<SCAIEVNode> returnList = new ArrayList<>();
    for (AdjacentNode possibleAdj : AdjacentNode.values())
      if (possibleAdj != AdjacentNode.none) {
        Optional<SCAIEVNode> adjSCAIEVNode_opt = GetAdjSCAIEVNode(look4Node, possibleAdj);
        if (adjSCAIEVNode_opt.isPresent())
          returnList.add(adjSCAIEVNode_opt.get());
      }
    return returnList;
  }

  /**
   * Returns  a SCAIEVNode, which is the child of the given SCAIEVNode and implements the given adjacent signal
   * @return
   */
  public Optional<SCAIEVNode> GetAdjSCAIEVNode(SCAIEVNode parentNode, AdjacentNode adj) {
    var ret = GetSCAIEVNode_opt(parentNode.name + adj.suffix);
    if (ret.isPresent() && adj != AdjacentNode.none)
      assert (ret.get().nameParentNode.equals(parentNode.name));
    return ret;
  }

  /**
   * Returns the non-adj node of a given node. If the given node is non-adj, returns node itself.
   * Returns a node with an empty name if it doesn't exist.
   * @return
   */
  public SCAIEVNode GetNonAdjNode(SCAIEVNode node) { return node.isAdj() ? GetSCAIEVNode(node.nameParentNode) : node; }
  /**
   * Returns the equivalent spawn adj/non-adj node to a non-spawn adj/non-adj node, or an empty Optional if it doesn't exist
   * @return
   */
  public Optional<SCAIEVNode> GetEquivalentSpawnNode(SCAIEVNode node) {
    if (node.isSpawn())
      return Optional.of(node);
    SCAIEVNode nodeNonadj = node.isAdj() ? GetSCAIEVNode(node.nameParentNode) : node;
    SCAIEVNode nodeNonportNonadj = node;
    String portName = "";
    if (nodeNonadj.tags.contains(NodeTypeTag.isPortNode)) {
      portName = getPortName(nodeNonadj);
      assert(!portName.isEmpty());
      //Retrieve the non-ported spawn node
      var nodeNonportNonadj_opt = GetSCAIEVNode_opt(nodeNonadj.nameParentNode);
      if (!nodeNonportNonadj_opt.isPresent())
        return Optional.empty();
      nodeNonportNonadj = nodeNonportNonadj_opt.get();
    }
    String portName_ = portName;
    return GetMySpawnNode(nodeNonportNonadj)
        .flatMap(nodeSpawnNonport -> Optional.ofNullable(portName_.isEmpty() ? nodeSpawnNonport : AddUserNodePort(nodeSpawnNonport, portName_)))
        .flatMap(nodeSpawnNonadj -> node.isAdj() ? GetAdjSCAIEVNode(nodeSpawnNonadj, node.getAdj()) : Optional.of(nodeSpawnNonadj));
  }
  /**
   * Determines the port name of a node, or returns "" if it is not a port of a base node.
   */
  public String getPortName(SCAIEVNode node) {
    SCAIEVNode nodeNonadj = node.isAdj() ? GetSCAIEVNode(node.nameParentNode) : node;
    if (!nodeNonadj.tags.contains(NodeTypeTag.isPortNode))
      return "";
    assert(nodeNonadj.name.startsWith(nodeNonadj.nameParentNode + SCAIEVNode.portbaseSuffix));
    String ret = nodeNonadj.name.substring(nodeNonadj.nameParentNode.length() + SCAIEVNode.portbaseSuffix.length());
    assert(!ret.isEmpty());
    return ret;
  }
  /**
   * Returns the equivalent non-spawn adj/non-adj node to a spawn adj/non-adj node, or an empty Optional if it doesn't exist
   * @return
   */
  public Optional<SCAIEVNode> GetEquivalentNonspawnNode(SCAIEVNode node) {
    SCAIEVNode nodeNonadj = node.isAdj() ? GetSCAIEVNode(node.nameParentNode) : node;
    SCAIEVNode nodeNonspawnNonadj = nodeNonadj;
    if (nodeNonadj.isSpawn()) {
      String portName = getPortName(nodeNonadj);
      SCAIEVNode nodeNonportNonadj = nodeNonadj;
      //Special case: port nodes
      if (nodeNonadj.tags.contains(NodeTypeTag.isPortNode)) {
        assert(!portName.isEmpty());
        //Retrieve the non-ported spawn node
        var nodeNonportNonadj_opt = GetSCAIEVNode_opt(node.nameParentNode);
        if (!nodeNonportNonadj_opt.isPresent())
          return Optional.empty();
        nodeNonportNonadj = nodeNonportNonadj_opt.get();
      }
      
      //Retrieve the non-spawn base node (non-ported)
      var nodeNonspawnNonadj_opt = GetSCAIEVNode_opt(nodeNonportNonadj.nameParentNode);
      if (!nodeNonspawnNonadj_opt.isPresent())
        return Optional.empty();
      nodeNonspawnNonadj = nodeNonspawnNonadj_opt.get();
      
      if (!portName.isEmpty()) {
        //Retrieve the ported non-spawn node
        nodeNonspawnNonadj = AddUserNodePort(nodeNonspawnNonadj, portName);
      }
    }
    
    return node.isAdj() ? GetAdjSCAIEVNode(nodeNonspawnNonadj, node.getAdj()) : Optional.of(nodeNonspawnNonadj);
  }

  /**
   * Function to get a list of spawn nodes. For exp for WrRD, it would be WrRD_spawn, WrRD_spawn_valid and WrRd_spawn_addr
   *
   * @param look4Node
   * @return null if no adjacent spawn node was found, otherwise an ArrayList of the spawn node and its adjacents.
   */
  public ArrayList<SCAIEVNode> GetMySpawnNodes(SCAIEVNode look4Node) {
    Optional<SCAIEVNode> spawnBaseNode = GetMySpawnNode(look4Node);
    if (spawnBaseNode.isEmpty())
      return null;
    ArrayList<SCAIEVNode> ret = GetAdjSCAIEVNodes(spawnBaseNode.get());
    ret.add(0, spawnBaseNode.get());
    return ret;
    //		 HashSet<SCAIEVNode> returnSet = new  HashSet<SCAIEVNode>();
    //		 SCAIEVNode mainSpawnNode = look4Node;
    //		 for(SCAIEVNode checkNode : GetAllBackNodes()) {
    //			 if(checkNode.HasParentNode(look4Node) && checkNode.isSpawnOf(look4Node)) {
    //				 mainSpawnNode = checkNode;
    //				 break;
    //			 }
    //		 }
    //
    //		 // Node not found
    //		 if(mainSpawnNode.equals(look4Node))
    //			 return null;
    //
    //		 for(SCAIEVNode checkNode : GetAllBackNodes()) {
    //			 if(checkNode.HasParentNode(mainSpawnNode)) {
    //				 returnSet.add(checkNode);
    //			 }
    //		 }
    //		 returnSet.add(mainSpawnNode);
    //
    //		 return returnSet;
  }

  /**
   * Function to get this node's main spawn node. For exp for WrRD, it would be WrRD_spawn
   *
   * @param look4Node
   * @return
   */
  public Optional<SCAIEVNode> GetMySpawnNode(SCAIEVNode look4Node) {
    if (look4Node.isAdj())
      return Optional.empty();
    String baseName = look4Node.name;
    if (look4Node.tags.contains(NodeTypeTag.isPortNode)) {
      // Not likely to be intended, as spawn ports are allocated separately from non-spawn ports.
      logger.warn("BNode.GetMySpawnNode called on a port node; if intentional, consider using GetEquivalentSpawnNode instead");
      return Optional.empty();
      //			//Name should look like <node name>_port<n>
      //			assert(look4Node.name.startsWith(look4Node.nameParentNode + SCAIEVNode.portbaseSuffix)
      //					&& Pattern.matches("^\\d+$", look4Node.name.substring(look4Node.nameParentNode.length() +
      // SCAIEVNode.portbaseSuffix.length())) ); 			baseName = look4Node.nameParentNode;
    }
    Optional<SCAIEVNode> spawnBaseNode = GetSCAIEVNode_opt(baseName + SCAIEVNode.spawnSuffix);
    if (spawnBaseNode.isPresent()) {
      assert (!spawnBaseNode.get().isAdj() && spawnBaseNode.get().nameParentNode.equals(baseName));
    }
    return spawnBaseNode;
    //		for(SCAIEVNode checkNode : GetAllBackNodes()) {
    //			if(checkNode.HasParentNode(look4Node) && checkNode.isSpawnOf(look4Node) && !checkNode.isAdj()) {
    //				return Optional.of(checkNode);
    //			}
    //		}
    //		return Optional.empty();
  }
}
