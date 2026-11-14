> Historical image reference from design revision 3. For the current baseline, read the [alignment-revision-22 Technical Approach](../../Technical_Approach/UACRF_Technical_Approach_Design_v1_0_alignment_revision_22.docx) and [58-view UI prototype](../ui_workloads/index.html).

# UACRF revised screen register

Version 1.0 design revision 3, aligned to architecture revision 5. The set contains 25 revised images and eight new knowledge and agent workflow images. These are concept images, not an implemented product.

[Open the gallery](index.html) · [Experience guide](EXPERIENCE_GUIDE_v1_0.md)

| Screen | Change | Workspace | Purpose | Specification |
| --- | --- | --- | --- | --- |
| [Overview](UACRF_Overview_Concept_v1_0.png) | Revised | Overview | See which journeys need attention and what to review next. | Section 19; Section 12.4 and Appendix W |
| [Services](UACRF_Services_Concept_v1_0.png) | Revised | Services | Confirm service ownership, scope and dependencies. | Sections 5.1 and 17; Section 12.4 and Appendix W |
| [Assess](UACRF_Assess_Concept_v1_0.png) | Revised | Assess | Review the evidence for a resilience assessment. | Sections 26 and 27; Section 12.4 and Appendix W |
| [Control](UACRF_Control_Concept_v1_0.png) | Revised | Control | Connect an observed change to its customer impact. | Sections 7, 9 and 28; Section 12.4 and Appendix W |
| [Recover](UACRF_Recover_Concept_v1_0.png) | Revised | Recover | Understand the proposed action, required authority and validation. | Sections 10 and 29; Section 12.4 and Appendix W |
| [Evidence](UACRF_Evidence_Concept_v1_0.png) | Revised | Evidence | Inspect what is retained and what remains missing. | Sections 5.7 and 30; Section 12.4 and Appendix W |
| [Browse governed knowledge](UACRF_Knowledge_Library_Concept_v1_0.png) | New | Evidence | Approved reference versions are distinct from qualified operational evidence. | Appendix W2 and W3 |
| [Submit knowledge for review](UACRF_Knowledge_Intake_Concept_v1_0.png) | New | Evidence | Uploaded knowledge remains quarantined until reviewed. | Appendix W2 |
| [Review a knowledge version](UACRF_Knowledge_Review_Concept_v1_0.png) | New | Evidence | Human approval and withdrawal preserve immutable versions and access limits. | Appendix W2 and W3 |
| [Inspect grounded search results](UACRF_Knowledge_Search_Concept_v1_0.png) | New | Evidence | Exact source citations and limitations stay visible. | Appendix W3 |
| [Set up bounded agent assistance](UACRF_Agent_Run_Setup_Concept_v1_0.png) | New | Assess | The operator selects an evaluated advisory workflow and bounded context. | Appendix W4 and W5 |
| [Inspect agent run progress](UACRF_Agent_Run_Detail_Concept_v1_0.png) | New | Assess | Role attempts, budgets, cancellation and rejected outputs remain attributable. | Appendix W4 and W5 |
| [Review agent output and dissent](UACRF_Agent_Output_Review_Concept_v1_0.png) | New | Assess | People inspect support, uncertainty and dissent before accepting draft text. | Appendix W3 W4 W5 |
| [Govern knowledge and agent capabilities](UACRF_Assistance_Settings_Concept_v1_0.png) | New | Settings | Promotion, scope, workflow evaluation and kill switch are separate controls. | Appendix W4 W5 W8 |
| [Connect an environment](UACRF_Workspace_Onboarding_Concept_v1_0.png) | Revised | Settings | Connect a provider with limited discovery access before activating collection. | Sections 6.1, 14 and 16; Section 12.4 and Appendix W |
| [Register a service](UACRF_Service_Onboarding_Concept_v1_0.png) | Revised | Services | Confirm ownership, journey meaning and declared dependencies before activation. | Sections 5.1, 5.3 and 17; Section 12.4 and Appendix W |
| [Connection health](UACRF_Connections_Concept_v1_0.png) | Revised | Settings | Show collection scope, source freshness and permission failures. | Sections 6, 16 and 31.1; Section 12.4 and Appendix W |
| [Explore dependencies](UACRF_Dependency_Graph_Concept_v1_0.png) | Revised | Services | Inspect dependency direction, evidence and customer impact without implying causation. | Sections 5.2, 9 and Appendix Q; Section 12.4 and Appendix W |
| [Review recovery independence](UACRF_Recovery_Paths_Concept_v1_0.png) | Revised | Assess | Explain whether alternate paths share a failure domain for a stated scenario. | Sections 5.2.3, 5.2.6 and Appendix P.5; Section 12.4 and Appendix W |
| [Review changed decision context](UACRF_Graph_Changes_Concept_v1_0.png) | Revised | Control | Compare retained decision context with current dependencies before action. | Sections 5.2.8, Appendix P.2 and Appendix T; Section 12.4 and Appendix W |
| [Review evidence quality](UACRF_Evidence_Quality_Concept_v1_0.png) | Revised | Evidence | Separate observed health from evidence sufficient for a recovery conclusion. | Sections 5.2.2, Appendix P.4 and Appendix U; Section 12.4 and Appendix W |
| [Define service objectives](UACRF_Service_Objectives_Concept_v1_0.png) | Revised | Services | Explain service and recovery targets, their measurement and approval status. | Sections 5.3 and 17.1; Section 12.4 and Appendix W |
| [Review an AI dependency](UACRF_AI_Dependencies_Concept_v1_0.png) | Revised | Services | Separate model availability, quality, data freshness and fallback readiness. | Sections 5.8, 8.4 and 12; Section 12.4 and Appendix W |
| [Investigate an incident](UACRF_Incident_Analysis_Concept_v1_0.png) | Revised | Control | Support human hypotheses, evidence and dissent before an action is selected. | Section 7.6; Section 12.4 and Appendix W |
| [Review an approval request](UACRF_Approval_Review_Concept_v1_0.png) | Revised | Recover | Show exact action scope, policy limits and independent approval conditions. | Sections 7.5, 10 and 29; Section 12.4 and Appendix W |
| [Review recovery runbooks](UACRF_Runbook_Catalog_Concept_v1_0.png) | Revised | Recover | Separate draft action definitions from tested and authorized runbooks. | Sections 5.6, 17.2, 29.1 and 29.2; Section 12.4 and Appendix W |
| [Plan a controlled test](UACRF_Controlled_Test_Concept_v1_0.png) | Revised | Assess | Define experiment scope, prerequisites and abort controls before any fault. | Section 18 and integration test plan; Section 12.4 and Appendix W |
| [Validate the recovery outcome](UACRF_Recovery_Validation_Concept_v1_0.png) | Revised | Recover | Show dependency-ordered checks and prevent premature recovery success. | Sections 10, 29 and Appendix P.6; Section 12.4 and Appendix W |
| [Track learning and retesting](UACRF_Learning_Concept_v1_0.png) | Revised | Control | Track proposed improvements through implementation and verified retesting. | Sections 3, 20 and FR-LRN requirements; Section 12.4 and Appendix W |
| [Manage access and responsibilities](UACRF_Access_Management_Concept_v1_0.png) | Revised | Settings | Make tenant scope and separation of duties visible without exposing secrets. | Sections 14, 14.1 and 31.1; Section 12.4 and Appendix W |
| [Review an assessment report](UACRF_Assessment_Report_Concept_v1_0.png) | Revised | Assess | Present a bounded conclusion with gaps, review, recipients and limitations. | Sections 26.10–26.12, 27.3 and 30; Section 12.4 and Appendix W |
| [Configure decision policies](UACRF_Policy_Settings_Concept_v1_0.png) | Revised | Settings | Configure tested policy settings with preview, review and staged activation. | Sections 7.5 and 31.1; Section 12.4 and Appendix W |
| [Route findings and escalation](UACRF_Notifications_Concept_v1_0.png) | Revised | Settings | Route actionable findings with context, ownership and auditability. | Sections 20.2, 31.2 and 34.1; Section 12.4 and Appendix W |

The earlier six-screen and 25-screen archives remain preserved. Exact built-in image-generation prompts are retained in IMAGE_PROMPTS_v1_0.json.
