# Setup guidance and navigation refinement

Version 1.0 · UI design revision 8 · Alignment revision 22 · Guide revision 8

The next-review explanation now lives inside How to set up UACRF. The earlier revision-7 baseline covered 49 screens; alignment revision 22 retains those destinations and adds nine customer-administration views for 58 registered views total. Each workspace's main action remains its primary control. The former How this screen connects card is removed from the work area. Its replacement is a secondary text link with a visible focus state and comfortable target spacing.

Primary action links now share their geometry across containers: inline flex alignment, padding, border, minimum height and wrapping. The earlier connection card link inherited the action colors without the panel-specific spacing; the common rule closes that styling gap.

The top bar shows a short workspace section label. The selected navigation item and page heading provide the current location. No full breadcrumb trail is added. The setup entry point, twelve-stage guide, customer business flow and local planning behavior are retained. This is a presentation revision; API, schema, SQL and actual execution permissions remain unchanged.

Source and package checks remain separate from browser, keyboard, responsive and assistive-technology acceptance, which are still pending.


## Alignment revision 22 · Customer administration

The primary menu now separates Work, Operations, Resources, Advisory tools and Customer administration. The customer administration home links to organization/workspaces/environments, people/groups/access, connections, governance/approvals, assessments/objectives, notifications/integrations, data/privacy/audit/retention, and usage/entitlements. Existing detailed destinations remain directly reachable. UACRF advisory workflows are not customer administration or protected customer agents. All links are navigation-only previews.