package scaiev.pipeline;

import java.util.Objects;
import java.util.Optional;
import scaiev.coreconstr.Core;

/**
 * Wrapper either around a PipelineFront or a 'stagePos' integer value.
 * Intended for use in scheduling constraints, as the final core pipeline graph may not be available at the beginning.
 * Can be resolved using {@link Core#TranslateStageScheduleNumber(ScheduleFront)}.
 */
public class ScheduleFront {
  PipelineFront asFront = null;
  int asStagePosInt = Integer.MIN_VALUE;
  public ScheduleFront(PipelineFront asFront) { this.asFront = asFront; }
  public ScheduleFront(int asStagePosInt) { this.asStagePosInt = asStagePosInt; }
  /** Constructs a ScheduleFront with an empty PipelineFront. */
  public ScheduleFront() { this.asFront = new PipelineFront(); }

  @Override
  public int hashCode() {
    return Objects.hash(asFront, asStagePosInt);
  }
  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    ScheduleFront other = (ScheduleFront)obj;
    if (asFront != null || other.asFront != null) {
      return Objects.equals(asFront, other.asFront);
    }
    return asStagePosInt == other.asStagePosInt;
  }
  public Optional<PipelineFront> tryGetAsFront() { return Optional.ofNullable(asFront); }
  /**
   * Returns the integer 'stagePos' value. If a PipelineFront is present or the value is invalid, the method will throw an
   * UnsupportedOperationException.
   */
  public int asInt() {
    if (asStagePosInt == Integer.MIN_VALUE)
      throw new java.lang.UnsupportedOperationException("TimeFront#asInt is not supported for PipelineFront");
    return asStagePosInt;
  }
  @Override
  public String toString() {
    if (asStagePosInt != Integer.MIN_VALUE)
      return Integer.toString(asStagePosInt);
    return asFront.asList().stream().map(stage->stage.getName()).reduce((a,b) -> a+", "+b).orElse("");
  }
}