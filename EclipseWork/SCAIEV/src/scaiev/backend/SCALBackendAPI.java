package scaiev.backend;

import java.util.HashMap;

import scaiev.frontend.SCAIEVNode;
import scaiev.util.GenerateText;

/**
 * Interface to configure aspects of SCAL for a core backend.
 */
public interface SCALBackendAPI {

	public static class CustomCoreInterface
	{
		public String name;
		public String dataT;
		public int stage;
		public int size;
		public boolean isInputToSCAL;
		public String instr_name;

		public CustomCoreInterface(String name, String dataT, int stage, int size, boolean isInputToSCAL, String instr_name) {
			this.name = name;
			this.dataT = dataT;
			this.stage = stage;
			this.size = size;
			this.isInputToSCAL = isInputToSCAL;
			this.instr_name = instr_name;
		}
		public String getSignalName(GenerateText language, boolean forCoreSide) {
			return language.CreateNodeName(new SCAIEVNode(name, size, isInputToSCAL ^ forCoreSide), stage, instr_name);
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
			if (stage != other.stage)
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
	 * Request SCAL to generate a RdIValid interface, regardless of whether an ISAX uses it directly.
	 */
	void RequestRdIValid(int stage, String instruction);

	/**
	 * Register an override of an otherwise shared read operation for a particular ISAX.
	 * Does not work for operations with adjacent nodes, and does not work for RdFlush.
	 * @param node operation node
	 * @param instruction ISAX to apply the override to
	 */
	void RegisterNodeOverrideForISAX(SCAIEVNode node, String instruction);
	
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
	 *  -> If the core backend  injects a writeback into the instruction pipeline, stalling the pipeline until completion could lead to a deadlock.
	 * @param spawn_node Node whose family does not need stalls on commit
	 */
	void DisableStallForSpawnCommit(SCAIEVNode spawn_node);
    
}
