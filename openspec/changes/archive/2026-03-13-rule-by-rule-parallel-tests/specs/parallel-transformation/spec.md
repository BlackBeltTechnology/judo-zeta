## MODIFIED Requirements

### Requirement: Element Staging for Parallel Execution

The transformation framework MUST support staging of created target elements during parallel transformation to avoid concurrent modification of EMF Resources. This applies to both ELEMENT_BY_ELEMENT and RULE_BY_RULE parallel strategies.

#### Scenario: Staging is enabled during parallel transformation
**Given** a transformation with parallel execution enabled
**And** the source element count exceeds the parallel threshold (5000)
**When** the transformation begins
**Then** element staging mode is activated
**And** created elements are queued instead of added to the target Resource

#### Scenario: Staging is disabled during sequential transformation
**Given** a transformation with parallel execution disabled
**Or** the source element count is below the parallel threshold
**When** the transformation begins
**Then** element staging mode remains disabled
**And** created elements are added directly to the target Resource

#### Scenario: Staged elements are committed after parallel phase
**Given** a parallel transformation has completed the transformation phase
**When** all worker threads have finished
**Then** all staged elements are added to the target Resource
**And** the staging queue is empty
**And** only root elements (not contained) are added to Resource.contents

#### Scenario: RULE_BY_RULE parallel uses incremental commits between rules
**Given** a RULE_BY_RULE parallel transformation with rules A, B, C
**When** Rule A's parallel chunks complete
**Then** `commitDeferredOperationsIncremental()` is called before Rule B starts
**And** Rule B's parallel chunks can see Rule A's committed results
**And** after Rule C completes, final staged elements are committed
