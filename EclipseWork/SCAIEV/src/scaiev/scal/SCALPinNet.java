package scaiev.scal;

import java.util.ArrayList;
import java.util.List;
public class SCALPinNet {
	public int size; //Size of the pin (bits).
	public String scal_module_pin; //Name of the pin in the SCAL module interface.
	public String core_module_pin; //Name of the pin in the core module interface ("" for N/A)
	public String isax_module_pin; //Name of the pin in the ISAX module interface ("" for N/A)
	public List<String> isaxes = new ArrayList<>(); //List of ISAXes this net applies to
	public SCALPinNet(int size, String scal_module_pin, String core_module_pin, String isax_module_pin)
	{
		this.size = size;
		this.scal_module_pin = scal_module_pin;
		this.core_module_pin = core_module_pin;
		this.isax_module_pin = isax_module_pin;
	}
	
	//Boilerplate for serialization / JavaBean
	
	public int getSize() {
		return size;
	}
	public void setSize(int size) {
		this.size = size;
	}
	public String getScal_module_pin() {
		return scal_module_pin;
	}
	public void setScal_module_pin(String scal_module_pin) {
		this.scal_module_pin = scal_module_pin;
	}
	public String getCore_module_pin() {
		return core_module_pin;
	}
	public void setCore_module_pin(String core_module_pin) {
		this.core_module_pin = core_module_pin;
	}
	public String getIsax_module_pin() {
		return isax_module_pin;
	}
	public void setIsax_module_pin(String isax_module_pin) {
		this.isax_module_pin = isax_module_pin;
	}
	public List<String> getIsaxes() {
		return isaxes;
	}
	public void setIsaxes(List<String> isaxes) {
		this.isaxes = isaxes;
	}
}

