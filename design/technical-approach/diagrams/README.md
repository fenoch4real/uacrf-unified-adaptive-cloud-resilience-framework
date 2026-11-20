> Historical diagram reference. This set records its original alignment-revision-5 scope. For current design and implementation status, see the [MS01 architecture](../../ms01/README.md), [architecture overview and diagrams](../../../docs/architecture-overview.md), and [implementation status](../../../docs/IMPLEMENTATION_STATUS.md). The revision-22 Technical Approach is maintained privately under `private-docs/`, when available.

# Historical graph-schema diagram authority

Release 1.0, alignment revision 5. Figures summarize the graph vocabulary recorded at that revision. Displayed fields are abbreviated, not complete required-property declarations. The accompanying field contracts and endpoint-role expansions are in [schema.json](../data-dictionary/schema.json), [endpoint_roles.json](../data-dictionary/endpoint_roles.json) and [field_aliases.json](../data-dictionary/field_aliases.json). These diagrams do not grant authorization or demonstrate a completed test.

The PNG and SVG illustrations are archival, not generated from the Mermaid files; they have
not been fully checked against the dictionary. Some labels and lines overlap, and
`12_core_runtime_sequence.png` clips its final label. For a zoomable view, open the
[Mermaid previews](../mermaid/README.md) or open the sources in a Mermaid-compatible
preview. In particular,
the evidence and recovery Mermaid sources have corrected relationship endpoints that the
historical illustrations do not reflect. The runtime sequence is a target workflow, not a
claim that recovery or authorization is implemented. Do not rely on the images for exact
relationship semantics; use the dictionary.

The full-schema Mermaid source lists all 58 dictionary labels; its unlabeled arrows are
illustrative, not typed relationship declarations. The relationship-vocabulary Mermaid
source lists all 55 dictionary relationship types with matching endpoint roles and
cardinalities, but abbreviates some properties. The three labeled evidence, failure, and
recovery examples are checked against dictionary endpoint roles by the sample-data tests.
