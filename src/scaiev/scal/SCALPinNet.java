package scaiev.scal;

import java.util.ArrayList;
import java.util.List;
/** A net to connect between SCAL, Core and ISAXes */
public class SCALPinNet {
  /** Size of the pin (bits). */
  public int size; 
  /** Name of the pin in the SCAL module interface. */
  public String scal_module_pin; 
  /** Name of the pin in the core module interface ("" for N/A) */
  public String core_module_pin;
  /** Name of the pin in the ISAX module interface ("" for N/A) */
  public String isax_module_pin;
  /** List of ISAXes this net applies to */
  public List<String> isaxes = new ArrayList<>();
  /** 
   * @param size Size of the pin (bits).
   * @param scal_module_pin Name of the pin in the SCAL module interface.
   * @param core_module_pin Name of the pin in the core module interface ("" for N/A)
   * @param isax_module_pin Name of the pin in the ISAX module interface ("" for N/A)
   */
  public SCALPinNet(int size, String scal_module_pin, String core_module_pin, String isax_module_pin) {
    this.size = size;
    this.scal_module_pin = scal_module_pin;
    this.core_module_pin = core_module_pin;
    this.isax_module_pin = isax_module_pin;
  }

  // Boilerplate for serialization / JavaBean

  /**
   * @return the size
   */
  public int getSize() { return size; }
  /**
   * @param size the size
   */
  public void setSize(int size) { this.size = size; }
  /**
   * @return the name of the pin in the SCAL module interface
   */
  public String getScal_module_pin() { return scal_module_pin; }
  /**
   * @param scal_module_pin the name of the pin in the SCAL module interface
   */
  public void setScal_module_pin(String scal_module_pin) { this.scal_module_pin = scal_module_pin; }
  /**
   * @return the name of the pin in the core module interface ("" for N/A)
   */
  public String getCore_module_pin() { return core_module_pin; }
  /**
   * @param core_module_pin the name of the pin in the core module interface ("" for N/A)
   */
  public void setCore_module_pin(String core_module_pin) { this.core_module_pin = core_module_pin; }
  /**
   * @return the name of the pin in the ISAX module interface ("" for N/A)
   */
  public String getIsax_module_pin() { return isax_module_pin; }
  /**
   * @param isax_module_pin the name of the pin in the ISAX module interface ("" for N/A)
   */
  public void setIsax_module_pin(String isax_module_pin) { this.isax_module_pin = isax_module_pin; }
  /**
   * @return the list of ISAXes this net applies to
   */
  public List<String> getIsaxes() { return isaxes; }
  /**
   * @param isaxes the list of ISAXes this net applies to
   */
  public void setIsaxes(List<String> isaxes) { this.isaxes = isaxes; }
}
