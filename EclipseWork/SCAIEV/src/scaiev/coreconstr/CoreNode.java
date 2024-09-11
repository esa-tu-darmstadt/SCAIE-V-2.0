package scaiev.coreconstr;

import scaiev.pipeline.ScheduleFront;

public class CoreNode {
	ScheduleFront earliestTime;
	int latency;
	ScheduleFront latestTime;
	ScheduleFront expensiveTime; //rd - timeslot starting with which it gets expensive // wr - timeslot untill which it was expensive 
    String name; 
    
	public CoreNode(int earliestTime, int latency, int latestTime, int expensiveTime, String name) {
		this.earliestTime = new ScheduleFront(earliestTime);
		this.latency = latency;
		this.latestTime = new ScheduleFront(latestTime);
		this.expensiveTime = new ScheduleFront(expensiveTime);	
		this.name = name;
	}
	public CoreNode(ScheduleFront earliestTime, int latency, ScheduleFront latestTime, ScheduleFront expensiveTime, String name) {
		this.earliestTime = earliestTime;
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
	
	public ScheduleFront GetLatest() {
		return this.latestTime;
	}
	public void OverrideLatest(ScheduleFront newLatest) { this.latestTime = newLatest; }
	
	public ScheduleFront GetEarliest() {
		return this.earliestTime;
	}
	public void OverrideEarliest(ScheduleFront newEarliest) { this.earliestTime = newEarliest; }
	
	public int GetLatency() {
		return this.latency;
	}
	
	public ScheduleFront GetExpensive() {
		return this.expensiveTime;
	}
	public void OverrideExpensive(ScheduleFront newExpensive) { this.expensiveTime = newExpensive; }
	
	public String GetName() {
		return this.name;
	}
}
