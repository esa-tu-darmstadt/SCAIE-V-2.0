The second version of the SCAIE-V Abstraction Layer (SCAL 2.0) comes with a new HDL logic generation approach. This document serves as an introduction to this approach and is mostly directed at developers working on SCAL components or new SCAIE-V processor backends.

## Motivation
The different logic components produced by SCAL usually provide a `SCAIEVNode` in a given stage and possibly are for an individual ISAX. Each logic component can require other components or module interfaces.
The previous implementation had no notion of components, generating the logic of SCAL with static rules based on the ISAX schedule and core datasheets. As the feature set and cores list of SCAIE-V has grown, so has the complexity of SCAL.

The revised implementation has two main goals:
- Interchangeability of components: The ability to adapt implementation strategies depending on the target processor microarchitecture or other configuration options.
- Automatic construction of dependencies: Reduce static rule complexity by having component builders report their dependencies and having a mechanism that constructs/resolves the logic components and interfaces accordingly. Assumptions on the specific naming of another component's HDL variables should be avoided.

## Concepts
- Node key: The identifier of a node, consisting of a `SCAIEVNode`, a stage, an optional ISAX name, and an optional unique counter (`aux`) to enable reduction (e.g., stall requests). Also a Purpose field to mark the stage of processing a node is in (with associated priority/visibility semantics) or for meta-requests.
- Node Strategies: Objects that decide whether (and how) to construct one or several nodes, given their keys, optionally emitting Builders. Strategies are passed a key only if no node matching that key exists yet.
- Node Logic Builders: Objects that can emit HDL declarations and logic (as strings), output node keys for use by other builders with corresponding expression strings and lookup nodes output by other builders. Builders also can be used in a meta sense, e.g., as markers for the existence or properties of some component that drive other builders.

  As the dependency set of a builder changes during composition (e.g., if a new node matches to the lookups of a builder), the builder will be enqueued for repeated invocation. Correspondingly, if the builder's outputs change (different/new node keys or expressions), the dependant builders of those outputs will be enqueued.
  
  Dependency cycles between builders are allowed only if the involved outputs of each involved builder stabilize. It is often a good idea to assign combinational logic to HDL wires and output the wire name as the node expression, as the wire name can stay fixed even if the logic changes. Using wire names also helps with debugging, grouping the HDL that a builder is responsible for together; this also improves composition performance.

  Builders are only guaranteed to be reevaluated if a possible change to the dependency set was observed. If a strategy wants to have one of its previously returned builders reevaluated (e.g. to add another element to a FIFO builder), it has to do something that changes the dependency set; `SwitchableNodeLogicBuilder` and `TriggerableNodeLogicBuilder` implement such a technique.

- Module Composer: The composer implements the main loop for logic generation. It invokes a root strategy to resolve still-missing node keys, and handles the builder invocation queue for correct resolution of dependencies.
- Node Registry: The one-stop-shop for builders to look up and request dependencies by node key. Lookups can be 'required' and 'optional'; a required lookup can lead to construction of a missing node, and always returns a value. Expressions of required lookups start with "MISSING_" if the dependency cannot be resolved yet. All kinds of lookups will be noted as a trigger, so the builder is invocated once more if the lookup results change. 

- Purpose: A tag for the processing stage or kind of a node.
  The default Purpose for lookups matches, among others, `PIPEDIN` (value from a previous stage), `WIREDIN` (unprocessed value from an external input) and `REGULAR` (internally processed value) with an increasing priority, such that `WIREDIN` nodes overwrite `PIPEDIN`, whereas `REGULAR` overwrites both. Different Purposes can be chosen for both lookups and node outputs.
  For instance, custom marker Purposes can be used in the node key to convey non-HDL metadata.
  
  The marker purpose family `MARKER_(TO|FROM)(CORE|ISAX)_PIN` allows builders to request module interface pin creation; another strategy then handles these markers, creating a builder that outputs the marker node and creates the interface pin. Special marker purposes are also used to wake up builders; this is how `SwitchableNodeLogicBuilder` and `TriggerableNodeLogicBuilder` are implemented.

  While the predefined Purpose objects represent semantic concepts, there are no hard rules on their use. However, it is strongly recommended to adhere to these concepts so strategies can easily interoperate without errors.

## Examples
TODO

