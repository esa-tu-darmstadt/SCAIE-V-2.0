package scaiev.ui;

/**
 * Data-Class to hold tool options.
 */
public class SCAIEVConfig {

  public int number_of_contexts = 1;
  public boolean decoupled_data_hazard_handling = true;
  public boolean decoupled_with_input_fifo = true;
  public boolean decoupled_retire_hazard_handling = true;
  
  public int spawn_input_fifo_depth = 4;
  public int semicoupled_fifo_depth = 4;
  public int decoupled_parallel_max = 8;

  public boolean cva5_wrrdspawn_injectmode = false;
  public boolean cva5_fetchdecodepipe_wideid = false;

  public boolean maygenerate_disaxkill = true;
  public boolean maygenerate_disaxfence = true;
}