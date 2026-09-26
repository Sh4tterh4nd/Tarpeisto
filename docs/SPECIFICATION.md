# BigContainers Functional Specification

Status: Initial approved product specification

Audience: Product owner, designers, implementers, and testers

## 1. Product summary

BigContainers manages individually identified event-technology equipment, quantity-tracked consumable supplies, the physical containers in which they are stored, event reservations, check-out and return, packing verification, damage and loss review, repairs, labels, and inventory history.

The primary users are a small internal team and occasional volunteers. The product must therefore favor guided scanning workflows, photographs, plain language, and clear recovery from mistakes over dense warehouse-management interfaces.

BigContainers is a responsive web application and installable Progressive Web App (PWA). A separate native Android or iOS application is not required.

### 1.1 Goals

- Replace spreadsheets and printed packing lists as the inventory source of truth.
- Track every reusable physical item with its own immutable public asset code.
- Track consumables as quantities without inventing one asset or QR code per unit.
- Represent boxes, flightcases, pallets, and similar objects as ordinary assets that can contain other assets.
- Allow containers to be nested to any practical depth.
- Reserve and check out either complete containers or individual assets.
- Support both exact-asset packing requirements and interchangeable model-quantity requirements.
- Make post-event verification usable by non-technical volunteers.
- Preserve loss, destruction, repair, booking, movement, and audit history without destructive deletion.
- Be straightforward to self-host with PostgreSQL and optional S3-compatible storage.
- Place a low-cost organization boundary in the schema without exposing multi-organization complexity in the initial product.

### 1.2 Initial non-goals

- Billing, invoicing, quotes, contracts, or public rental storefronts.
- Native mobile applications.
- Complete offline operation.
- Customer relationship management.
- Multi-organization switching or commercial tenant administration.
- Merging two inventories into one organization.
- Anonymous public access to inventory records.
- Purchasing, supplier orders, valuation, or full warehouse-management accounting.
- Consumable lot, batch, expiry-date, or per-unit cost tracking.

## 2. Terminology

| Term | Meaning |
|---|---|
| Organization | Owner of all inventory data. The initial installation contains one invisible default organization. |
| Category | Colored classification assigned to a model, such as Networking or Cables. |
| Model | Shared catalog definition for either serialized equipment or quantity stock, such as `UniFi AP-HD`, `RAKO 400 x 300`, or `Gaffer tape 50 mm`. |
| Physical asset | One individually tracked real-world unit with an internal UUID and public asset code. |
| Quantity stock | An amount of one consumable model held at a location or in a container, without individual asset identities. |
| Stock movement | Immutable receipt, issue, return, transfer, consumption, or adjustment that changes a quantity-stock balance. |
| Container | A physical asset whose model allows it to contain assets. |
| Location | Hierarchical physical storage place, such as `HQ / Room 13 / Shelf A`. |
| Packing requirement | Definition of what a container must directly contain. |
| Exact requirement | Requirement that can only be fulfilled by one specified physical asset. |
| Model-quantity requirement | Requirement that can be fulfilled by any eligible serialized physical units of a specified model. |
| Consumable-quantity requirement | Requirement for an amount of a quantity-tracked model; no individual QR scan is expected. |
| Current contents | Exact assets most recently verified as physically present in a container. |
| Packing template | Reusable starting set of packing requirements copied onto a container. |
| Event | Time-bounded reservation and movement of containers and/or individual assets. |
| Checkout manifest | Immutable list of exact physical assets that left inventory for an event. |
| Audit | Verification of the direct contents of one container. |
| Audit batch | Related set of container audits, normally created for one event return. |
| Finding | Missing, damaged, unexpected, misplaced, or unreadable-label observation from an audit. |

## 3. Tenancy and organization boundary

### 3.1 Initial behavior

- Installation creates one default organization.
- The first user becomes the Owner of that organization.
- The organization concept is not shown in normal initial-product navigation.
- Every organization-owned record contains `organization_id`.
- All reads and writes are scoped by the organization from the authenticated server-side context.

### 3.2 Database requirements

- Organizations and principal domain records use UUID primary keys.
- Human-readable codes are unique within an organization, not globally.
- Cross-record references must be constrained to the same organization, preferably with composite foreign keys such as `(organization_id, referenced_id)`.
- Object-storage keys must include the organization UUID.
- A future commercial installation may add organization memberships, switching, limits, and billing without rewriting the inventory schema.

## 4. Users, roles, and access

### 4.1 Permanent roles

#### Owner

- Full organization configuration.
- User and role administration.
- Model, category, custom-field, location, template, and packing-requirement administration.
- Booking, checkout, check-in, audit, review, repair, archive, and restoration actions.

#### Deputy

- Normal inventory administration.
- Create and modify packing requirements.
- Manage events and perform checkout/check-in.
- Perform and review audits.
- Resolve findings.
- Mark assets lost, destroyed, retired, repaired, or restored.
- Cannot transfer ownership or perform owner-only installation administration.

#### Operator/Auditor

- View inventory necessary for assigned operations.
- Perform permitted event checkout and check-in.
- Perform assigned audits.
- Report damage, missing items, unexpected items, and unreadable labels.
- Cannot redefine packing requirements or make permanent loss/destruction decisions.

#### Viewer

- Optional read-only role.
- No mutating actions.

### 4.2 Temporary volunteer access

- An Owner or Deputy can generate a temporary-access QR invitation for one event or audit batch.
- The invitation token is random, unguessable, revocable, and separate from all asset/container QR codes.
- The invitation and resulting access expire 24 hours after issuance.
- The volunteer supplies a display name so activity remains attributable.
- Temporary access grants only scoped Operator/Auditor permissions.
- Expiry or revocation ends new API access immediately.
- Temporary users cannot browse unrelated inventory or organization administration.

### 4.3 Permanent-user authentication

- Local username/password login is available by default and remains the recovery path for a self-hosted installation.
- An installation may optionally enable one OpenID Connect (OIDC) provider through Spring Security's OAuth 2.0 client support. OIDC, rather than bare OAuth 2.0, supplies the authenticated user identity.
- Supported initial modes are `LOCAL_ONLY` and `LOCAL_AND_OIDC`. An installation may require OIDC for ordinary permanent users only while at least one enabled local Owner recovery account remains usable.
- Provider configuration includes a display name, issuer URI, client ID, client secret, and scopes. Discovery through the issuer URI is preferred; initial scopes are `openid profile email`.
- The browser always receives the same BigContainers server-side session after either login method. Provider access/refresh tokens remain server-side and no bearer token is stored in browser storage.
- An external identity is keyed by the immutable pair `(issuer, subject)` and linked to an internal user. Email addresses are profile and matching attributes, never the durable external identity key.
- Automatic linking by email is disabled by default. If enabled by an Owner, it requires an OIDC `email_verified` claim and exactly one matching internal user. Otherwise an Owner must approve or create the link.
- Just-in-time user creation and provider group/role mapping are not part of the initial release. Organization membership, role, disabled state, and authorization remain authoritative inside BigContainers.
- A permanent user may have local credentials, an external identity, or both. The system prevents removal of the last usable Owner authentication method.
- Local logout always invalidates the BigContainers session. Provider-wide single logout is best effort and not required for correctness.
- Temporary volunteer QR access remains a separate authentication flow and is unaffected by OIDC configuration.
- Provider secrets are supplied through environment variables or mounted secret files. Reverse-proxy deployments must preserve the public HTTPS scheme and host so redirect URIs are generated correctly.

## 5. Categories

A category contains:

- UUID
- Organization ID
- Name
- Color as normalized `#RRGGBB`
- Optional archive timestamp

Rules:

- Category names are unique within an organization.
- The UI automatically chooses readable foreground text for the selected color.
- Archived categories remain visible on historical records but cannot be selected for new models.
- Category color is used in badges and container packing sheets.

## 6. Asset models

An asset model contains shared catalog information. Its tracking mode determines whether it creates individually identified reusable assets or quantity-tracked consumable stock.

### 6.1 Standard model fields

- UUID
- Organization ID
- Name, required
- Description, optional multiline plain text
- Category, required
- Replacement URL, optional HTTP/HTTPS URL pointing to a manufacturer or preferred supplier
- Primary reference photograph, optional
- Tracking mode, required: `SERIALIZED_ASSET` by default or `QUANTITY_STOCK`
- Stock unit label for quantity-tracked models, such as `roll`, `ream`, `pack`, `tube`, or `piece`
- Optional low-stock threshold for quantity-tracked models
- `can_contain_assets`, default `false`
- Optional archive timestamp
- Created and updated timestamps

`Condition`, purchase date, serial number, MAC address, and other unit-specific values do not belong to the model. They apply only to serialized assets.

### 6.2 Tracking modes

#### Serialized asset

- Each real-world unit is a physical asset with its own UUID, public code, lifecycle, condition, and optional label.
- Model-defined unit fields are available.
- The model may be container-capable.

#### Quantity stock

- The system stores a decimal quantity, not a physical-asset row per roll, pack, sheet, or cable tie.
- The user selects a practical stock unit. For example, cable ties may be counted as `bag` rather than attempting to count each tie.
- Quantity precision supports up to three decimal places, although whole-number practical units are preferred.
- Quantity-tracked models cannot be container-capable, cannot define per-unit custom fields, and do not receive public asset codes, individual labels, condition, or lifecycle state.
- Changing tracking mode is prohibited after assets, stock balances, packing requirements, bookings, or history exist.

### 6.3 Container-capable models

When `can_contain_assets` is enabled on a serialized model, every physical asset of that model can act as a container. There is no separate container-model table.

Examples:

| Model | Can contain assets |
|---|---:|
| UniFi AP-HD | No |
| LAN cable 10m | No |
| RAKO 400 x 300 | Yes |
| Pelican 1510 | Yes |
| 19-inch flightcase | Yes |
| Euro pallet | Yes |

Disabling containment is prohibited while any unit of the model currently contains assets or has active packing requirements.

### 6.4 Consumable stock and movement ledger

- A quantity-stock balance belongs to one organization, one quantity-tracked model, and exactly one stock place: either a direct location or a container asset.
- There is at most one active balance for a model at a given stock place.
- Every balance change creates an immutable stock movement containing the signed quantity delta, unit, reason, actor, timestamp, optional note, and optional event/audit reference.
- Initial movement reasons are `RECEIPT`, `TRANSFER`, `EVENT_ISSUE`, `EVENT_RETURN`, `CONSUMPTION`, `AUDIT_ADJUSTMENT`, and `MANUAL_ADJUSTMENT`.
- A transfer transactionally decrements the source and increments the destination while preserving one linked movement operation.
- No operation may make a balance negative. Owner/Deputy adjustments require an explicit reason and activity entry.
- Consumables inside a container appear when that container is opened or scanned; no QR label is required on the consumable itself. Optional bin/shelf labels may be added later without changing the stock identity model.
- Low-stock status is calculated from the model threshold across the organization's active on-hand balances. A future extension may add per-location thresholds.

## 7. Model-defined unit fields

Custom-field definitions are created on a serialized model, but their values exist only on physical assets of that model. Quantity-tracked models cannot define these fields.

The model page lists and manages definitions; it never displays a MAC address, serial number, or other unit value.

### 7.1 Supported initial types

- String
- Dropdown
- Date

### 7.2 Definition fields

- UUID
- Organization ID
- Model ID
- Name
- Data type
- Display order
- Active/archive state
- Dropdown options and their display order, when applicable

Rules:

- Field names are unique within a model.
- All active custom fields are required for each physical asset of the model.
- New asset creation cannot complete while a required value is missing.
- Adding a field to a model with existing units marks those units `metadata incomplete` until populated.
- Metadata incompleteness is prominently shown but does not by itself mark an asset missing or destroyed.
- Changing a field's datatype after values exist is prohibited.
- Removing a field archives its definition and historical values.
- Dropdown values must reference an active option belonging to the correct definition.

Example:

```text
Model: UniFi AP-HD

Unit fields:
- Serial number — String
- MAC address — String
- Commissioned on — Date
- Radio mode — Dropdown
```

## 8. Physical assets

Every reusable physical unit is represented individually.

### 8.1 Standard fields

- UUID primary key
- Organization ID
- Model ID
- Public asset code
- Model-local unit number
- Optional individual name
- Condition, default `Good`
- Lifecycle state, default `Active`
- Optional purchase date
- Optional direct location
- Optional current parent container
- Last verification timestamp and audit reference
- Sealability and seal state when the asset is a container
- Optional archive timestamp
- Created and updated timestamps

### 8.2 Naming

- Units created in bulk receive sequential model-local unit numbers.
- Default display can be generated as `<Model Name> <unit number>`, for example `UniFi AP-HD 3`.
- An individual name may override or supplement the generated unit label.
- Container assets require an individual name such as `Mobile Network Box` or `Cable Box Large 2`.
- The asset label prints the model name and the individual asset name/number separately.

### 8.3 Condition

Initial conditions:

- `Good`
- `Damaged`

Condition describes physical condition and is separate from lifecycle and operational availability.

### 8.4 Lifecycle

Initial lifecycle states:

- `Active`
- `Lost`
- `Destroyed`
- `Retired`

Rules:

- Lost, destroyed, retired, and archived assets are excluded from normal inventory and booking results.
- They remain searchable through explicit filters.
- Public asset codes are never reused.
- Scanning a lost asset produces a prominent alert and allows an Owner or Deputy to restore it.
- Destroyed or retired records remain available for history and reporting.

### 8.5 Operational availability

Operational availability is derived from active events, audits, findings, and repairs rather than stored as lifecycle:

- Available
- Reserved
- Checked out
- Returned, audit pending
- Audit in progress
- Review required
- In repair

## 9. Public asset codes and QR payloads

### 9.1 Format

- The public code is displayed as one uninterrupted uppercase string.
- Initial codes contain six characters.
- Characters 1-5 are random Crockford Base32 data symbols.
- Character 6 is an order-sensitive checksum encoded with the same Crockford Base32 alphabet.
- Example: `7K3MXP`.
- Users treat all six characters as the asset code; the checksum is not visually separated.

The data alphabet excludes ambiguous letters and uses:

```text
0123456789ABCDEFGHJKMNPQRSTVWXYZ
```

### 9.2 Normalization

Before validation:

- Lowercase is converted to uppercase.
- Whitespace and hyphens are removed.
- `O` is interpreted as `0`.
- `I` and `L` are interpreted as `1`.

The checksum is validated before a database lookup. An invalid checksum returns a transcription-error message rather than `not found`.

### 9.3 Generation and uniqueness

- Codes are generated using a cryptographically secure random source.
- A database unique constraint covers `(organization_id, public_code)`.
- Collision causes generation to retry.
- The schema accepts longer future codes, with the final character remaining the checksum.
- Codes are immutable and never recycled.

### 9.4 QR contents

- The QR contains only the canonical public code, not a URL, UUID, model, location, or action.
- QR scanning is performed inside the PWA.
- Manual code entry is always available.
- The short payload keeps printed codes compact and independent of domain or hosting changes.

## 10. Locations

Locations form an organization-scoped hierarchy using an optional parent location.

Examples:

```text
HQ
└── Room 13
    └── Shelf A
```

and:

```text
Basement
```

Location fields:

- UUID
- Organization ID
- Name
- Optional parent location ID
- Optional description
- Optional archive timestamp

Rules:

- A location cannot be its own parent or descendant.
- Parent and child must belong to the same organization.
- A top-level physical asset may have a direct location.
- An asset inside a container inherits the effective location of its outermost container.
- An asset cannot simultaneously have a direct location and a current parent container.
- Moving an outer container changes the effective location of all descendants without rewriting every descendant row.

## 11. Physical containment

Containers and ordinary assets share the physical-assets table. A nullable self-reference records current direct containment.

Rules:

- Only assets whose model has `can_contain_assets = true` may be parents.
- Parent and child must belong to the same organization.
- An asset cannot directly or indirectly contain itself.
- Moving a container into one of its descendants is prohibited.
- A physical asset can have at most one current parent container.
- A container containing assets cannot be archived without first moving or resolving those assets.
- Current containment means the most recently verified physical placement, not an immutable ownership assignment.
- Changes are recorded in activity history.

The complete containment path is shown when useful:

```text
HQ / Room 13 / Pallet 2 / Network Flightcase 1 / UniFi AP-HD 3
```

## 12. Packing requirements

A container's packing specification states what must be present directly inside that container. It is separate from the exact assets last verified inside it.

### 12.1 Requirement modes

#### Exact physical asset

- References one physical asset.
- Quantity is always one.
- Only that physical asset satisfies the requirement.
- A physical asset can be the active exact requirement of at most one container.

Use cases include configured routers, controllers, and access points whose identity matters.

#### Model quantity

- References one model and required quantity.
- Any eligible active physical assets of that model may satisfy the requirement.
- Every unit retains its own asset code and history.

Use cases include individually labeled but interchangeable cables.

#### Consumable quantity

- References one quantity-tracked model and a positive required amount in that model's stock unit.
- Represents the minimum amount that should be directly available in the container.
- Is verified by quantity confirmation or entry, not by scanning individual units.
- Never creates placeholder assets or consumes public asset codes.

Use cases include tape rolls, printer-paper reams, glue tubes, and packs of cable ties.

### 12.2 Eligibility and matching

An asset explicitly required by another container cannot satisfy a model-quantity requirement elsewhere.

Audit matching order:

1. If the scanned asset is an exact requirement of another container, report it as misplaced and do not count it here.
2. If it is an exact requirement of the current container, satisfy that exact requirement.
3. Otherwise, satisfy an open model-quantity requirement for its model.
4. If no compatible requirement remains, report it as unexpected/extra.
5. One asset scan can satisfy only one serialized requirement.
6. Consumable-quantity requirements are evaluated separately from asset scans against the stock balance or an auditor-entered observed amount.

Example:

```text
Network Box 1
- Exact: AP 1

Mobile Net Large
- Any UniFi AP-HD x 5
```

AP 1 cannot satisfy one of `Mobile Net Large`'s interchangeable AP slots.

### 12.3 Packing templates

- Owners and Deputies can create reusable packing templates.
- A container can also be configured independently without a template.
- Applying a template copies its requirements onto the container.
- Later template changes do not silently modify existing containers.
- A future explicit `Apply template changes` operation may reconcile changes with warnings.

### 12.4 Editing requirements

- Only Owners and Deputies can add, edit, archive, or remove requirements.
- Requirements are normally locked while the container is checked out.
- Adding a requirement automatically updates future reserved events containing the container.
- Additions do not require destructive-change confirmation.
- If an addition creates an availability conflict, affected reservations are flagged for attention.
- Removing a requirement warns the Deputy and lists affected future reservations before proceeding.
- Checked-out manifests and historical event records are never rewritten.
- Any packing change invalidates the container's current sealed/verified assertion until reconciled.

## 13. Events and bookings

### 13.1 Event fields

- UUID
- Organization ID
- Name, required
- Start date/time, required
- End date/time, required
- Optional client/organization text
- Optional venue text
- Optional notes
- Lifecycle state
- Created by and timestamps

### 13.2 Reservable targets

An event can reserve:

- A complete container
- An individual physical asset
- A planned quantity of a consumable model from a selected stock place

Booking a container always means the whole container. A partial selection from a container is represented as individual-asset booking lines, not as a partial container booking.

### 13.3 Reservation expansion and conflicts

- Booking a container reserves the container and its descendants recursively.
- Exact packing requirements reserve their exact assets.
- Model-quantity requirements reserve sufficient eligible capacity of their model.
- Consumable booking lines reserve sufficient available quantity at their selected source.
- Consumable available-to-promise is on-hand stock minus all active planned issues, regardless of whether event dates overlap. The system does not assume consumed stock will return or be replenished.
- Stock in a source container that is unavailable for the event period cannot satisfy a separate consumable booking line.
- Consumable quantities already carried in a booked container are included through that container and are not reserved again as separately issued stock.
- Nested containers recursively contribute their requirements.
- An individual asset cannot be reserved for overlapping dates if an ancestor container is reserved.
- A container cannot be reserved for overlapping dates if a descendant asset or container is independently reserved.
- An exact-required asset may be booked individually when its container is not booked, but the UI warns that this makes the container incomplete/unavailable for that period.
- Booking an interchangeable unit individually reduces the eligible pool; another eligible unit may satisfy its container's model requirement.
- Conflict checks rerun whenever event dates, booking lines, or relevant future packing requirements change.

### 13.4 Event lifecycle

#### Draft

- Event is being assembled.
- Equipment selection and dates are freely editable.
- Equipment is not yet held and does not block other events.

#### Reserved

- Dates and equipment are confirmed.
- Overlapping reservations are blocked.
- Additions to booked-container packing specifications flow into the reservation.
- Removals produce an affected-booking warning.

#### Checked out

- At least some equipment has left inventory.
- The exact checkout manifest is frozen.
- Checkout PDF becomes available.
- Partial-return progress is shown without rewriting the manifest.

#### Returned - audits pending

- All checked-out equipment has physically returned.
- One or more container audits remain pending.
- Affected containers remain unavailable.

#### Review required

- Return audits produced unresolved findings.
- Owners or Deputies must resolve them.

#### Completed

- Every checked-out item is returned or formally accounted for.
- All audit findings are resolved.
- A repair may remain open after the event if the finding has been resolved into a repair record.

#### Cancelled

- A non-checked-out event was abandoned.
- Reservations are released.
- An event with checked-out equipment cannot be directly cancelled.

## 14. Checkout

### 14.1 Normal checkout

- User opens the event in the PWA.
- User scans or selects containers and individual assets.
- User confirms any separately issued consumable quantities and their source stock places.
- System validates reservation, availability, lifecycle, repair, descendant, and packing conflicts.
- The exact physical contents recorded for model-quantity requirements are used for the manifest unless the user chooses to verify them.
- The system freezes an immutable checkout manifest of exact physical assets and consumable quantity lines.
- A separately issued consumable line creates an `EVENT_ISSUE` stock movement at checkout. Consumables remaining inside a booked container are snapshotted as expected container stock and are not decremented merely because the container changes custody.
- A checkout PDF is generated from the frozen manifest.
- Checked-out custody and timestamps are recorded.

### 14.2 Optional outbound verification

A full outbound audit is never mandatory by default.

For a container, offer:

- Confirm seal intact and continue
- Run full audit
- Continue without audit, subject to role permissions

If recorded contents cannot fulfill the packing requirements, the application warns and offers scanning/selection to resolve the manifest. Deputy overrides require a recorded reason.

## 15. Check-in and return

- Containers and individual assets can be checked in from the event.
- Unused separately issued consumables can be returned to a selected stock place, creating an `EVENT_RETURN` movement. The difference remains recorded as consumed for the event.
- Checking in a container offers `Audit now` or `Mark for later`.
- `Mark for later` immediately creates a pending task; it is not a passive flag.
- Checking in a parent container creates audit tasks for the parent and all descendant containers.
- All event return audits belong to one audit batch.
- Returned containers remain unavailable until clean or reviewed.
- Partial return progress remains visible on the event.
- Once all physical returns are recorded, the event moves to `Returned - audits pending` or `Review required` as appropriate.

## 16. Audit batches and dependencies

### 16.1 Batch responsibilities

An event return batch provides:

- Overall progress across returned containers
- Bottom-up audit ordering
- Exact event-manifest reconciliation
- Cross-container placement suggestions
- Duplicate-scan prevention
- Final unresolved-finding summary
- Consumable quantity confirmation and adjustment history

### 16.2 Bottom-up ordering

Audits verify one physical boundary at a time.

- A leaf container can be audited immediately.
- A parent audit is locked until every direct child container has a clean or reviewed audit.
- The parent audit scans direct assets and child-container codes only.
- It does not rescan assets inside a child container.
- The UI shows blocking child tasks and progress.

Example:

```text
Power-supply boxes
    -> Network flightcases
        -> Pallet
```

If an audited child is reopened or its seal is broken before the parent is completed, its verification becomes stale and dependent parent readiness is recalculated.

## 17. Container audit workflow

### 17.1 Starting

1. Open an assigned audit or choose Audit in the PWA.
2. Scan the target container code.
3. Confirm the container identity, photographs, and packing layout.
4. Display direct expected contents and current progress.
5. Start continuous scanning.

### 17.2 Scanning behavior

- Expected exact asset: mark found.
- Eligible model unit: fill one open quantity slot.
- Asset pinned to another container: show wrong-container message and correct destination.
- Compatible model beyond required quantity: show extra.
- Asset already scanned in this audit: show duplicate feedback without counting again.
- Asset already scanned in another audit in the batch: show its audit and offer `Move scan here`.
- Unknown code: show clear unknown-item result and allow a finding/photo.
- Lost asset: show prominent lost status and require Deputy action to restore.

Each outcome uses distinct visual, audible, and vibration feedback where supported.

Consumable rows are presented separately from scannable assets. For each consumable requirement the auditor can:

- Choose `Required amount present` as a quick confirmation. This satisfies the packing requirement without claiming an exact count or changing the stored balance.
- Choose `Enter observed quantity` to record a count. If it differs from the current balance, an authorized adjustment is written through the stock-movement ledger with the audit reference.
- Choose `Missing or low` to create a finding and enter an observed quantity when known.

Volunteers may confirm or report a discrepancy, but only an Owner or Deputy may approve a balance-changing adjustment.

### 17.3 Last-scanned card and corrections

Below the camera, show the last scanned item's:

- Reference photo
- Model and individual name
- Asset code
- Matching outcome
- `Mark damaged`
- `Undo scan`

Auditors can also open any earlier scanned row and mark it damaged or undo it.

Duplicate scans are idempotent.

### 17.4 Damaged items

- Selecting `Damaged` pauses scanning.
- Auditor sees the exact asset identity and may add a note.
- A damage photograph is strongly requested but not mandatory.
- Multiple evidence photographs are permitted.
- Submitting creates a damage finding; it does not let a volunteer permanently destroy or retire the asset.

### 17.5 Unreadable labels

- Manual public-code entry is always available.
- An expected row can be selected and marked `Present - label unreadable`.
- This creates a label-replacement finding, optionally with a photograph.

### 17.6 Missing and extra items

- Remaining unsatisfied exact requirements, serialized model quantities, and consumable quantities are shown before completion.
- Auditor must explicitly confirm that remaining items are missing.
- Extra assets show their required or last verified destination when known.
- Within an event batch, the system suggests another container that still needs that model.
- Unexpected items never silently redefine packing requirements.

### 17.7 Finishing

1. All scans and photos must be synchronized.
2. Auditor reviews missing, damaged, unexpected, misplaced, and unreadable-label findings.
3. Auditor is instructed to close the case.
4. Auditor scans the same container code again.
5. The second code must match the starting container.
6. For a sealable container, auditor confirms that a seal was applied.
7. Audit completes as clean or with findings.

Manual code entry is available if the container QR is damaged. Completion is not allowed while local scan operations remain unsynchronized.

### 17.8 Updating current contents

On completion, successfully scanned assets become the container's last verified direct contents. Interchangeable units may therefore trade containers without manual reassignment. Confirmed consumable requirements record their audit result; only an explicitly entered and authorized observed quantity changes the stock balance.

Example:

- `LAN 10m S-03` originally verified in Small Box 1 is found in Large Box 2.
- Large Box 2 needs ten units of `LAN 10m` and accepts S-03 normally.
- Another eligible cable fills Small Box 1's requirement.
- Both boxes can complete cleanly while exact asset history records their new verified containers.

Assets absent from their prior verified container are not automatically marked lost. Event reconciliation and findings determine the appropriate review.

## 18. Event-manifest reconciliation

Container completeness and event completeness are separate checks.

A container may have correct model quantities while the event still contains an exact discrepancy, for example:

```text
Missing from checkout manifest:
- LAN 10m A72KQF

Unexpected return:
- LAN 10m M39TXC
```

Rules:

- Every exact manifest asset scanned anywhere in the batch counts as returned for the event.
- An asset not on the manifest is reported as an unexpected return even if it satisfies a container quantity requirement.
- A manifest asset not scanned anywhere remains missing from the event.
- A single physical asset cannot count in two completed container audits.
- Event findings require Deputy/Owner review even if every container's model counts are complete.
- Consumable event reconciliation compares quantity issued, quantity returned, and quantity consumed. It does not invent exact identities for the consumed units.

## 19. Findings and review

Initial finding types:

- Missing
- Damaged
- Unexpected
- Misplaced/exactly required elsewhere
- Unreadable or missing label
- Unknown code

Auditors record observations. Owners and Deputies make permanent decisions.

Possible resolutions include:

- Found and returned
- Move to correct container
- Reassign current container
- Mark lost
- Mark damaged
- Create repair
- Mark destroyed
- Replace label
- Dismiss with reason

Resolution stores actor, timestamp, optional note, and resulting state changes. Completed audit observations remain immutable; resolutions are appended separately.

## 20. Repairs and replacements

### 20.1 Repair record

The visible repair workflow requires:

- `In repair`
- Reference number or description

Internally each repair is a history record with:

- UUID and organization ID
- Asset ID
- Reference/description
- Opened timestamp and actor
- Optional closed timestamp and actor

Rules:

- An asset with an open repair is unavailable.
- Closing a repair prompts for resulting condition.
- Repair history is retained.

### 20.2 Replacement

- A destroyed or lost asset record is retained.
- A replacement is a new physical asset with a new public code.
- The new asset may reference the asset it replaces.
- Public codes and history are never transferred to the replacement.

## 21. Security seals

Sealability is configured on the individual physical container.

Fields/behavior:

- `sealable` boolean
- Current `sealed` boolean/state
- Applied/broken/verified timestamps and actors through history
- Optional nullable seal reference reserved for future numbered seals

Rules:

- Non-sealable containers do not receive seal prompts.
- Finishing an audit for a sealable container asks for confirmation that a seal was applied.
- Opening/breaking a seal marks it unsealed and invalidates the sealed assertion.
- Packing or verified-content changes invalidate the applicable sealed/verified state.
- Checkout displays expected and reported seal state but does not mandate an outbound audit.
- Returning a container still creates an audit task; an intact seal does not initially bypass return auditing.

## 22. Photographs and media

Media may be stored on local persistent storage or through an S3-compatible provider.

Supported associations:

- Model primary reference photograph
- Optional individual asset photograph
- Multiple ordered container packing-layout photographs
- Audit evidence photographs
- Optional unknown-item or unreadable-label photographs

Container packing photographs support:

- Caption, such as `Bottom layer` or `Top tray`
- Display order
- Primary image designation

Files store metadata including organization, owner entity, media type, content type, byte size, checksum, uploader, and timestamp.

## 23. Labels, packing sheets, and exports

All generated documents use a bundled monospaced font family with regular and bold faces so output is consistent across servers.

### 23.1 Asset-label content

Asset labels are generated only for serialized physical assets. Quantity-tracked consumables do not receive individual codes or labels.

Left side:

- Model name in bold monospace
- Individual asset name or number in regular monospace
- Category on A4 sheet labels

Right side:

- QR containing only the six-character public code
- The same six-character code printed beneath the QR

Long names must wrap or truncate according to a documented template rule without overlapping the QR.

### 23.2 Built-in A4 asset-label PDFs

Initial formats:

- 70 x 36 mm, 24 labels per A4 page
- 97 x 42.3 mm, 12 labels per A4 page

Each template supports configurable page margins, horizontal/vertical pitch, and gutters to accommodate manufacturer differences.

Features:

- Bulk selection and printing
- Reprinting an existing code
- Skipping a chosen number of starting positions on a partially used sheet
- Print-preview calibration

### 23.3 P-touch CSV

Export UTF-8 CSV with at least:

```text
model_name
asset_name
asset_code
category
category_color
qr_value
```

`qr_value` equals the canonical public code. The user's P-touch template creates the QR and controls printer-specific layout.

### 23.4 Container packing sheets

- A4 page split into two identical A5-sized halves.
- Each half contains the same content.
- Thick top bar uses the container category color.
- Container individual name is bold monospace and at least 22 pt.
- Include container model, asset code, and QR.
- Include packing requirements grouped by model and quantity.
- Consumable requirements show their amount and stock unit, for example `2 rolls Gaffer tape 50 mm`.
- Exact requirements include the required asset code.
- Nested containers include their names/codes where applicable.

Example:

```text
10 x LAN 20m
10 x LAN 10m
 5 x UniFi AP-HD

Specific:
7K3MXP  Configured Gateway
91TRQW  Controller AP
```

Packing-list layout starts at 12 pt:

1. Use one column when it fits.
2. Add columns while retaining 12 pt.
3. Reduce font size incrementally only when necessary.
4. Never reduce below 8 pt.
5. Generate additional duplicated pages if the list still does not fit.

## 24. PWA and connectivity resilience

### 24.1 PWA

- Installable on current Android and iOS home screens.
- Responsive layouts for phone, tablet, and desktop.
- Camera-based QR scanning.
- Manual public-code entry fallback.
- No native-app dependency.

### 24.2 Short-outage behavior

Full offline inventory operation is not required. During an active audit:

- Scans are immediately recorded in local persistent browser storage.
- Unsent operations are queued with stable client-generated operation IDs.
- The UI clearly shows online, offline, synchronizing, and failed states.
- Duplicate submissions are idempotent on the server.
- Photos are queued with progress and retry behavior.
- Audit completion is disabled until queued scans, corrections, and photographs are synchronized.
- A user may continue scanning during a short connection interruption.

## 25. Archiving and history

- Models, assets, quantity-stock balances, categories, locations, templates, requirements, users, completed events, and audits are archived rather than destructively deleted once referenced by history.
- Archived records are excluded from normal pickers and searches unless the archive filter is enabled.
- Historical records continue displaying archived names and relationships.
- Activity history records actor, timestamp, action, target, and relevant before/after information.
- Important state changes and their audit log entry must be committed atomically.

History includes at least:

- Asset creation and metadata changes
- Consumable receipts, transfers, issues, returns, consumption, and adjustments
- Container moves
- Packing-requirement changes
- Event reservation changes
- Checkout and return
- Audit scans, corrections, and completion
- Finding creation and resolution
- Lifecycle and condition changes
- Repair open/close
- Seal changes
- Archiving/restoration

## 26. Search and operational views

Initial search should locate records by:

- Public code
- Model name
- Individual name
- Model-local unit number
- Category
- Location/container path
- Active string custom-field values, including serial number and MAC address
- Consumable model name and stock place

Operational views should include:

- Available, reserved, and checked-out equipment
- Events requiring checkout or return
- Pending and blocked audits
- Findings requiring review
- Assets in repair
- Lost/destroyed/retired archive filters
- Containers with incomplete packing requirements
- Assets with incomplete custom-field metadata
- Consumables below their low-stock threshold

## 27. Deployment and storage

### 27.1 Technology baseline

- Java 25 LTS
- Spring Boot 4.1.x on Spring MVC
- Gradle with the checked-in Gradle wrapper and Kotlin build scripts
- Google Jib Gradle plugin for the application OCI image
- PostgreSQL 18
- S3-compatible object storage for images and other media
- A client-side React and TypeScript PWA built with Vite

Exact dependency versions are pinned in build files and lockfiles. "Latest stable" never means resolving an unpinned moving version during a build.

### 27.2 Runtime services

- One application service
- PostgreSQL
- An S3-compatible object store when media features are enabled

The object store may be externally managed AWS S3 or a self-hosted S3-compatible service. The first-party self-hosting configuration uses Garage. Application code talks only to the S3-compatible storage contract; it does not maintain a separate production filesystem-storage implementation.

Optional supporting services are:

- Reverse proxy for TLS termination
- SMTP for invitations and notifications

### 27.3 Application packaging

The frontend is developed as a separate workspace but compiled into static assets and served by Spring Boot in production. This provides:

- One application image and one public origin
- No production Node.js server
- No cross-origin authentication or camera/PWA configuration
- Independent frontend and backend development servers locally, with the Vite development server proxying API requests to Spring Boot

The application image is built from Gradle with Google Jib and contains the Java runtime, Spring Boot application, and compiled frontend. It does not contain a Node.js runtime and does not require a production Dockerfile. PostgreSQL and S3-compatible storage remain separate services; "one container" refers to the single application container, not an all-in-one stateful appliance.

GitHub Actions validates the frontend, backend, and complete image build. Accepted default-branch and version-tag workflows publish the application image to GitHub Container Registry. Pull-request workflows, including forked pull requests, never publish an image. Release image tags and digests are retained so operators can deploy an exact immutable artifact.

### 27.4 Self-hosting

- Provide a maintained OCI application image in GitHub Container Registry.
- Provide a first-party `docker-compose.yml` for application, PostgreSQL, and Garage.
- Allow the Compose application image to be pinned by semantic version or digest.
- Allow Garage to be disabled when an external S3-compatible endpoint is configured.
- Database migrations run through a documented, explicit deployment step.
- Provide backup and restore documentation for PostgreSQL and media.
- Secrets are injected through environment variables or mounted secret files and are not embedded in images.

No Redis, Elasticsearch, Supabase, or message queue is required for the initial product.

## 28. Data integrity and security requirements

- All authorization is enforced server-side.
- Local and OIDC login both terminate in the same server-side session and authorization model.
- External identities are unique by `(issuer, subject)`; verified email alone never becomes the persistent login key.
- At least one enabled local Owner recovery method remains available when OIDC is configured.
- Organization context comes from authenticated membership, never a client-supplied organization ID alone.
- Temporary access is narrowly scoped and expires after 24 hours.
- Public asset codes are identifiers, not authentication secrets.
- Mutation APIs use validation schemas and reject cross-organization references.
- Uploads validate content type, file signature, and size.
- Object keys do not trust user filenames.
- Checkout manifests and completed audit observations are immutable.
- Scanner mutations are idempotent.
- Database constraints enforce uniqueness and same-organization relationships where possible.
- Recursive containment/location operations detect cycles transactionally.
- Permanent destructive deletion is limited to genuinely unreferenced setup mistakes and Owner-only maintenance paths, if supported at all.

## 29. Minimum acceptance scenarios

The initial production release must demonstrate all of the following:

1. Create a category with a color, a model with description and replacement URL, and multiple numbered physical assets.
2. Configure model-defined String, Dropdown, and Date unit fields and populate different values for each unit.
3. Generate and validate six-character checked public codes, print labels, scan them, and manually enter a damaged code.
4. Create hierarchical locations and show inherited effective locations through nested containers.
5. Create container-capable models, named container assets, nested containers, and cycle-safe moves.
6. Create independent packing requirements and apply a copied packing template.
7. Prevent an exact-required AP from satisfying another container's model-quantity AP requirement.
8. Allow interchangeable numbered cables to swap between two boxes and complete both audits without manual reassignment.
9. Reserve a whole container recursively and prevent conflicting descendant bookings.
10. Reserve an individual asset and correctly affect its container's availability.
11. Check out an event, freeze exact asset identities, and generate the checkout PDF.
12. Check in a parent container, create all descendant audit tasks, and enforce bottom-up execution.
13. Complete a clean audit by rescanning the closed container and applying a seal where expected.
14. Record damage with an optional note/photo and route the container to review.
15. Detect event-manifest loss even when container model quantities are complete.
16. Resolve a finding into lost, destroyed, moved, repaired, dismissed, or label-replacement outcomes.
17. Restore a previously lost asset when it is later scanned.
18. Queue audit scans during a short connection outage and synchronize without duplicate counts.
19. Grant a named volunteer scoped QR access that expires after 24 hours.
20. Archive historical records without losing their event, audit, and activity history.
21. Create a quantity-tracked tape model, receive and transfer stock, and prevent normal operations from producing a negative balance.
22. Add consumables to a container and event, verify them without individual QR scans, and reconcile issued, returned, and consumed amounts.
23. Complete an OIDC login into an existing linked user while preserving local roles and the same server-side session behavior.
24. Reject an ambiguous or unverified email auto-link and retain usable local Owner recovery when the provider is unavailable.

## 30. Explicitly deferred capabilities

- Commercial multi-organization administration and billing
- Importing and merging organizations
- Consumable purchasing, supplier orders, valuation, lot/batch tracking, expiry dates, and per-unit cost accounting
- OIDC group/claim-to-role mapping and just-in-time user provisioning
- Customer storefront and online rental requests
- Contracts, pricing, invoicing, and payments
- Native Android/iOS applications
- Full offline inventory browsing and administration
- RFID/NFC support
- Numbered-seal UI until a seal vendor is selected
- Live synchronization of existing containers to edited packing templates
