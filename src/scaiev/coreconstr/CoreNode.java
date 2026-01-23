package scaiev.coreconstr;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import scaiev.pipeline.ScheduleFront;

public class CoreNode {
  ScheduleFront earliestTime;
  int latency;
  ScheduleFront latestTime;
  ScheduleFront expensiveTime; // rd - timeslot starting with which it gets expensive // wr - timeslot untill which it was expensive
  String name;

  /** Any additional tags a node can have */
  public enum CoreNodeTag {
    //Empty for now
  }
  Set<CoreNodeTag> tags = EnumSet.noneOf(CoreNodeTag.class);

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
    to_print =
        "earliestTime = " + earliestTime.toString() + " latency = " + latency
        + " latestTime = " + latestTime.toString() + " expensiveTime = " + expensiveTime.toString();
    return to_print;
  }

  public ScheduleFront getLatest() { return this.latestTime; }
  public void overrideLatest(ScheduleFront newLatest) { this.latestTime = newLatest; }

  public ScheduleFront getEarliest() { return this.earliestTime; }
  public void overrideEarliest(ScheduleFront newEarliest) { this.earliestTime = newEarliest; }

  public int getLatency() { return this.latency; }

  public ScheduleFront getExpensive() { return this.expensiveTime; }
  public void overrideExpensive(ScheduleFront newExpensive) { this.expensiveTime = newExpensive; }

  public String getName() { return this.name; }

  public Set<CoreNodeTag> getTags() { return Collections.unmodifiableSet(tags); }
  public void addTag(CoreNodeTag tag) { tags.add(tag); }
}
