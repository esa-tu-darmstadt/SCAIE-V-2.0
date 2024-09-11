package scaiev.backend;

import java.util.HashMap;

import scaiev.backend.SCALBackendAPI.CustomCoreInterface;
import scaiev.frontend.SCAIEVNode;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.strategy.StrategyBuilders;
import scaiev.util.GenerateText;

/**
 * Interface to configure aspects of SCAL for a core backend.
 */
public interface SCALBackendAPI {

	public static class CustomCoreInterface
	{
		public String name;
		public String dataT;
		public PipelineStage stage;
		public int size;
		public boolean isInputToSCAL;
		public String instr_name;

		public CustomCoreInterface(String name, String dataT, PipelineStage stage, int size, boolean isInputToSCAL, String instr_name) {
			this.name = name;
			this.dataT = dataT;
			this.stage = stage;
			this.size = size;
			this.isInputToSCAL = isInputToSCAL;
			this.instr_name = instr_name;
		}
		public SCAIEVNode makeNode(boolean forCoreSide) {
			return new SCAIEVNode(name, size, isInputToSCAL ^ forCoreSide);
		}
		public NodeInstanceDesc.Key makeKey(Purpose purpose) {
			return new NodeInstanceDesc.Key(purpose, makeNode(false), stage, instr_name);
		}
		public String getSignalName(GenerateText language, boolean forCoreSide) {
			return language.CreateNodeName(makeNode(forCoreSide), stage, instr_name);
		}
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null || getClass() != obj.getClass())
				return false;
			CustomCoreInterface other = (CustomCoreInterface) obj;
			if (!name.equals(other.name) || !instr_name.equals(other.instr_name))
				return false;
			if (!stage.equals(other.stage))
				return false;
			return true;
		}
	}

	/**
	 * Register a valid condition (with a stage and an optional ISAX name) to apply for all matching RdIValid outputs.
	 * @param valid_signal_from_core signal from the core with a set stage and an optional ISAX name ("" to apply to all ISAXes)
	 */
	void RegisterRdIValid_valid(CustomCoreInterface valid_signal_from_core);

	/**
	 * Request SCAL to generate a to-core interface pin.
	 */
	void RequestToCorePin(SCAIEVNode node, PipelineStage stage, String instruction);


	/**
	 * Register an override of an otherwise shared read operation for a particular ISAX.
	 * Does not work for operations with adjacent nodes, and does not work for RdFlush.
	 * @param node operation node
	 * @param instruction ISAX to apply the override to
	 */
	void RegisterNodeOverrideForISAX(SCAIEVNode node, String instruction);
	
	/**
	 * Override the WrRD_spawn destination address signal (default: take from the `rd` instruction field).
	 * @param addr_signal_from_core signal from the core with a set stage; ISAX name is unused.
	 */
	void OverrideSpawnRDAddr(CustomCoreInterface addr_signal_from_core);
	
	/**
	 * Register a spawnAllowed adjacent node to SCAL.
	 * @param spawnAllowed Adjacent spawnAllowed node
	 */
	void SetHasAdjSpawnAllowed(SCAIEVNode spawnAllowed);
	
	/**
	 * Prevent instantiation of SCAL's data hazard unit, while retaining the optional address FIFOs and valid shiftregs for WrRD_spawn.
	 */
	void SetUseCustomSpawnDH();
	
	/**
	 * Prevent SCAL from stalling the core if a given type of spawn operation (by family name) is to be committed.
	 *  -> The core backend may handle collisions between in-pipeline and spawn operations in a different way.
	 *  -> If the core backend (e.g. CVA5) injects a writeback into the instruction pipeline, stalling the pipeline until completion could lead to a deadlock.
	 * @param spawn_node Node whose family does not need stalls on commit
	 */
	void DisableStallForSpawnCommit(SCAIEVNode spawn_node);

	/**
	 * Some nodes like Memory require the Valid bit in earlier stages. In case of Memory for exp. for computing dest. address. SCAL will generate these valid bits, 
	 * but SCAL must know from which stage they are requried. These valid bits are not combined with the optional user valid bit. They are just based on instr decoding
	 * Function to be overwritten by cores which need this.
	 * @param node ...node
	 * @param stage_for_valid the corresponding earliest stage front where the valid bit is requested
	 */

	void OverrideEarliestValid(SCAIEVNode node, PipelineFront stage_for_valid);

	
	/**
	 * Adds a custom SCAL->Core interface pin. The builder should provide an output expression for interfacePin.makeKey(Purpose.REGULAR).
	 * @param interfacePin
	 * @param builder
	 */
	void AddCustomToCorePinUsing(CustomCoreInterface interfacePin, NodeLogicBuilder builder);


	/**
	 * Adds a custom Core->SCAL interface pin that will be visible to SCAL logic generation.
	 * @param interfacePin
	 */
	void AddCustomToSCALPin(CustomCoreInterface interfacePin);
	
	/**
	 * Retrieves the StrategyBuilders object of SCAL.
	 * The caller is allowed to use {@link StrategyBuilders#put(java.util.UUID, java.util.function.Function)}.
	 */
	StrategyBuilders getStrategyBuilders();
    

}
