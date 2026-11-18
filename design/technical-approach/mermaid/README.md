# Technical-approach diagram previews

These are zoomable Mermaid previews of two historical graph-vocabulary examples.
They show possible typed relationships, not current runtime behavior or proof
that a particular observation is qualified. The [data dictionary](../data-dictionary/)
defines the endpoint roles; [implementation status](../../../docs/IMPLEMENTATION_STATUS.md)
records what is actually implemented.

The source files remain editable separately; the sample-data tests check that
the previews below match their corresponding sources and that the labeled
relationship endpoints match the dictionary. Other Mermaid sources and the
[archival images](../diagrams/) are not covered by that preview check.

## Evidence to outcome

Source: [02_evidence_to_outcome.mmd](02_evidence_to_outcome.mmd).

```mermaid
flowchart LR
  %% 02 Evidence To Outcome
  subgraph SG0["Evidence"]
    N0["EvidenceSource<br/>source"]
    N1["EvidenceEvent<br/>normalized"]
    N2["EvidenceQualification<br/>qualified"]
  end
  subgraph SG1["Runtime"]
    N3["CloudResource<br/>resource"]
    N4["Deployment<br/>release"]
    N5["Service<br/>service"]
  end
  subgraph SG2["Customer"]
    N6["JourneyStep<br/>step"]
    N7["CustomerJourney<br/>journey"]
    N8["Impact<br/>impact"]
    N9["Outcome<br/>outcome"]
  end
  N0 -->|EMITS| N1
  N1 -->|QUALIFIED_BY| N2
  N1 -->|DESCRIBES_STATE_OF| N3
  N3 -->|INSTANCE_OF| N4
  N4 -->|IMPLEMENTS| N5
  N5 -->|SUPPORTS_STEP| N6
  N6 -->|PART_OF_JOURNEY| N7
  N7 -->|AFFECTS| N8
  N8 -->|CONTRIBUTES_TO| N9
```

## Recovery relationships

Source: [04_recovery_propagation.mmd](04_recovery_propagation.mmd).
This depicts graph relationships, not a recovery execution sequence.

```mermaid
flowchart LR
  %% 04 Recovery Propagation
  subgraph SG0["Assess"]
    N9["EvidenceEvent<br/>observation"]
    N0["EvidenceQualification<br/>qualified"]
    N1["GraphRevision<br/>revision"]
    N2["RecoveryPath<br/>certified"]
  end
  subgraph SG1["Control"]
    N3["CandidateAction<br/>intent"]
    N4["Authorization<br/>bounded"]
  end
  subgraph SG2["Recover"]
    N5["Execution<br/>executed"]
    N6["ValidationPlan<br/>plan"]
    N7["ValidationResult<br/>proof"]
  end
  subgraph SG3["Evidence"]
    N8["DecisionEnvelope<br/>reconstructable"]
  end
  N9 -->|QUALIFIED_BY| N0
  N2 -->|BOUND_TO_REVISION| N1
  N3 -->|USES_RECOVERY_PATH| N2
  N3 -->|AUTHORIZED_BY| N4
  N4 -->|EXECUTES| N5
  N5 -->|VALIDATED_BY| N6
  N5 -->|VALIDATED_BY| N7
  N7 -->|RECORDED_IN| N8
```
