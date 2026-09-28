# UI Controls Reference (Menus and Buttons)

This reference explains what each main menu and button does.

## Global Navigation Menu

| Menu | Purpose | Typical user |
|---|---|---|
| Dashboard | Health overview, recent activity, quick actions | all users |
| Modules | Create and version module logic | maintainers, admins |
| Pipelines | Build/run pipelines and inspect runs | maintainers, operators |
| Connectors | Manage external integrations and broker users | operators, admins |
| Data | Query tables, analyze trends, export data | analysts, operators |
| Metadata | Types and objects (devices, meters, locations …), as-of history, CSV import, templates | maintainers, analysts |
| API keys | Create and revoke ingest keys for sending systems | admins with *secret:manage* |
| Alerts | Configure channels and inspect delivery history (rules are ALERT nodes in pipelines) | operators, admins |
| Users | Invite users and manage memberships | tenant admins |
| Tenants | Create/manage tenant hierarchy and defaults | tenant/platform admins |

## Header Controls

| Control | What it does | Notes |
|---|---|---|
| Tenant selector | Switches active tenant context | If data "disappears", check this first |
| Language selector | Changes UI language | Does not change data |
| Theme toggle | Switches light/dark mode | Personal preference |
| Logout | Ends session and returns to login | Use on shared devices |

## Dashboard Buttons

| Button/Action | Function |
|---|---|
| Create Module | Opens module creation flow |
| Create Pipeline | Opens pipeline creation flow |
| Add Connector | Opens connector creation flow |
| Open Problem View | Filters to failing/running pipelines |
| Time window switch (`1h/24h/7d`) | Changes reliability trend window |

## Modules Page Buttons

| Button/Action | Function |
|---|---|
| Add Module / Create Module | Creates a new module |
| Edit Module | Opens module editor |
| Delete | Removes module |
| Import/Processing/Alert/Export type selector | Chooses module behavior class |
| Version History | Lists and loads previous module versions |
| Save (in editor) | Saves new module version |
| Back to Modules | Returns to modules list |

## Pipelines Page Buttons and Controls

| Button/Control | Function |
|---|---|
| Create Pipeline | Creates pipeline configuration |
| Trigger / Run Pipeline | Starts execution |
| Retry run | Re-runs failed/cancelled run |
| Cancel run | Stops active run |
| Load lineage | Loads run lineage graph/details |
| Refresh now / Refresh run | Reloads list/details |
| Sort / Status filters / Time filters | Refines run list and triage view |
| Copy link / Copy lineage link | Shares current filtered context |
| Save preset | Saves triage filter profile |
| Reset usage | Resets preset usage counters |

## Pipeline Editor Controls

| Control | Function |
|---|---|
| Import Module selector | Defines ingestion stage |
| + Add Module (processing/alert/export) | Appends stage modules |
| Remove | Removes selected stage module |
| Export connector selector | Sets connector for export stage |
| Save | Persists pipeline configuration |
| Run | Triggers immediate test execution |
| Help | Explains each stage role |

## Connectors Page Buttons and Controls

| Button/Control | Function |
|---|---|
| Create Connector | Creates connector definition |
| Edit Connector | Updates connector settings |
| Clone Connector | Copies connector (optionally without secrets) |
| Connect / Disconnect | Activates or deactivates connector |
| Type/Direction/Status filters | Filters connector list |
| Connector Usage | Shows where connector is referenced |
| Connector Impact | Shows impacted pipelines/risk |
| Create MQTT Broker User | Creates tenant-scoped MQTT credential |
| Rotate (broker user) | Rotates credential password |
| Delete Broker User | Removes broker credential (if not referenced) |

## Data Explorer Buttons and Controls

| Button/Control | Function |
|---|---|
| Refresh | Reloads tables |
| Create Table | Creates table and seed row |
| Delete Table | Drops selected table |
| Search | Queries selected table with filters |
| Metric field / Tag field | Sets summary chart fields |
| Top tags | Controls tag distribution count |
| Export CSV / Export JSON | Downloads current query window |

## Alerts Page Buttons and Controls

| Button/Control | Function |
|---|---|
| Add Channel / Edit Channel | Manage delivery channels |
| Add Rule / Edit Rule | Manage alert rules |
| Delete Channel / Delete Rule | Removes configuration |
| Any Of / All Of / Single Condition | Builds rule condition logic |
| Enabled / Disabled toggle | Activates/deactivates rule |
| Channels / Rules / History tabs | Switches alert management context |

## Users Page Buttons and Controls

| Button/Control | Function |
|---|---|
| Invite User | Creates user and membership |
| Role dropdown | Changes membership role |
| Activate / Deactivate | Toggles user active status |
| Remove membership | Revokes tenant access |
| Search/filter controls | Filters users by status/role/text |

## Tenants Page Buttons and Controls

| Button/Control | Function |
|---|---|
| Create Root Tenant | Creates first/top-level tenant |
| Create Sub-Tenant | Adds child tenant |
| Edit Tenant | Updates tenant metadata |
| Save lineage defaults | Persists default lineage policy |
| Restore from history | Restores previous defaults/policy |
| Copy diagnostics | Copies tenant diagnostics snapshot |
| Reset diagnostics | Clears diagnostics counters |
| Resolve conflict (`Use latest / Use mine / Merge and save`) | Handles concurrent edits |

## Common Button Meanings

| Label | Meaning |
|---|---|
| Save | Persist changes |
| Create | Add new resource |
| Delete | Remove resource permanently |
| Refresh | Reload latest data |
| Retry | Re-attempt previous operation |
| Cancel | Abort current dialog/action |

## Permission-Related UI Behavior

- Buttons may be hidden or disabled if your role lacks required permissions.
- "Access denied" and "permission" messages indicate role-scope mismatch, not necessarily a system failure.
- If expected controls are missing, verify both active tenant and assigned role.
