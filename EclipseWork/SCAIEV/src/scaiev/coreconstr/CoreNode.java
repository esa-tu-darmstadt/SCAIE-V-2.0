package scaiev.coreconstr;

public class CoreNode {
	int earliestTime;
	int latency;
	int latestTime;
	int expensiveTime; //rd - timeslot starting with which it gets expensive // wr - timeslot untill which it was expensive 
    String name; 
    
	public CoreNode(int earliest_time, int latency, int latestTime, int expensiveTime, String name) {
		this.earliestTime = earliest_time;
		this.latency = latency;
		this.latestTime = latestTime;
		this.expensiveTime = expensiveTime;	
		this.name = name;
	}
	
	// Function for writing data to the constraints file in the format required. 
	@Override
    public String toString() { 
		String to_print;
		to_print ="earliestTime = "+ earliestTime + " latency = "+latency+" latestTime = "+latestTime+" expensiveTime = "+expensiveTime;
		return to_print;
	}
	
	public int GetLatest() {
		return this.latestTime;
	}
	
	public int GetEarliest() {
		return this.earliestTime;
	}
	
	public int GetLatency() {
		return this.latency;
	}
	
	public int GetExpensive() {
		return this.expensiveTime;
	}
	
	public String GetName() {
		return this.name;
	}
}
