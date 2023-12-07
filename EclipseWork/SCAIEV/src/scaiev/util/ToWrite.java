package scaiev.util;


public class ToWrite
{
	public String text = ""; 
	public boolean prereq = false; 
	public boolean prereq_val = true;  // if prereq with previous condition, the start value should be false. If prereq with no previous condition = run once, start value should be true. If no prereq, set to whatever
	public String prereq_text = ""; 
	public boolean before; 
	public boolean replace;
	public String in_module;
	boolean found_module = true;

	/**
	 * Constructor for a ToWrite descriptor object, with implicit 'prereq = false', 'prereq_val = true'.
	 * @see ToWrite#ToWrite(String, boolean, boolean, String, boolean, String)
	 */
	public ToWrite(String text, boolean before, String in_module) {
		this.text = text;	
		this.before = before;
		this.replace = false;
		this.in_module = in_module;
		if(!in_module.contentEquals(""))
			this.found_module = false;	
		
	}
	
	/**
	 * Constructor for a ToWrite descriptor object.
	 * @param text The text to insert; use "\n" line breaks for multi-line text. The final line break is added implicitly.
	 * @param prereq Set to true to enable scanning for a 'prerequisite' line that should appear before the match. The ToWrite object can match once per prerequisite line.
	 * @param prereq_val Initial value of prereq_val, which marks whether this ToWrite descriptor should be considered for matching before the prerequisite line is found.
	 *                   During file processing, will be set to true when the prerequisite line is found, and reset to false after a match with this ToWrite object is found.
	 *                   In case prereq==false, this needs to be set to true, as otherwise, no matches can be produced with this object. 
	 * @param prereq_text Portion of the 'prerequisite' line to search for.
	 * @param before Set to true (false) if the text should be inserted before (after) the matched line.  
	 * @param in_module The name of the module to search within (e.g. in the block starting after the line {@code 'module <name>'} and ending with {@code 'endmodule'} in Verilog), or "" to search in the whole file. 
	 */
	public ToWrite(String text, boolean prereq,boolean prereq_val,String prereq_text, boolean before, String in_module) {
		this.text = text;
		this.prereq = prereq;
		this.prereq_val = prereq_val;
		this.prereq_text = prereq_text; 	
		this.before = before;
		this.replace = false;
		this.in_module = in_module;
		if(!in_module.contentEquals(""))
			this.found_module = false;	
		
	}
	
	/**
	 * Constructor for a ToWrite descriptor object, with implicit 'before = false'.
	 * @see ToWrite#ToWrite(String, boolean, boolean, String, boolean, String)
	 */
	public ToWrite(String text, boolean prereq,boolean prereq_val,String prereq_text, String in_module) {
		this.text = text;
		this.prereq = prereq;
		this.prereq_val = prereq_val;
		this.prereq_text = prereq_text; 	
		this.before = false;
		this.replace = false;
		this.in_module = in_module;
		if(!in_module.contentEquals(""))
			this.found_module = false;	
		
	}

	/**
	 * Constructor for a ToWrite descriptor object, with implicit 'in_module = ""'.
	 * @see ToWrite#ToWrite(String, boolean, boolean, String, boolean, String)
	 */
	public ToWrite(String text, boolean prereq,boolean prereq_val,String prereq_text, boolean before) {
		this.text = text;
		this.prereq = prereq;
		this.prereq_val = prereq_val;
		this.prereq_text = prereq_text; 	
		this.before = before;
		this.replace = false;
		this.in_module = "";
		this.found_module = true;
		
	}

	/**
	 * Constructor for a ToWrite descriptor object, with implicit 'before = false', 'in_module = ""'.
	 * @see ToWrite#ToWrite(String, boolean, boolean, String, boolean, String)
	 */
	public ToWrite(String text, boolean prereq,boolean prereq_val,String prereq_text) {
		this.text = text;
		this.prereq = prereq;
		this.prereq_val = prereq_val;
		this.prereq_text = prereq_text; 	
		this.before = false;
		this.in_module = "";
		this.found_module = true;
		
	}
	
	public void AllignText(String allignment) {
		String newText = allignment + this.text;
		newText = newText.replaceAll("(\\r|\\n)","\n"+allignment);
		this.text = newText;
		
	}
	@Override
	public String toString() {
		return "Text to be added: "+text+". Prereq requested: "+prereq+". Text to be added before greped line: "+before+". In module "+in_module;
	}
}