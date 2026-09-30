# REST authorization matrix

This is the reviewed application REST inventory for Phase 14.1. The runtime MVC drift gate reads this table, independently of OpenAPI, and rejects missing, stale or duplicate method/path rows. Spring declares 166 method/path patterns; the two packing archive/restore regex patterns expand into 168 concrete reviewed routes below. Any additional regex alternative must gain its own reviewed row. Dynamic variable names remain part of the contract.

O = Owner, D = Deputy, OA = **OPERATOR_AUDITOR**, V = Viewer, T = temporary named volunteer, A = anonymous. Y permits entry subject to the scope/business checks; N denies; S permits only the current invitation assignment; C permits initial setup only while setup is required. A permanent session is revalidated against enabled account/current membership/current role before authorization. Temporary sessions are revalidated against their immutable deadline and revocation on every request; the temporary HTTP allowlist and service scope checks both apply.

Unless marked public, org means server-derived active organization and organization-scoped repository/service lookup. Foreign resources are indistinguishable from missing resources (404). Role denial is 403, absent/invalid authentication is 401. A successful role check does not bypass operational state, immutable completed-history, version, CSRF, actor-fence or idempotency rules. Controller delegation and the service that owns the role/scope guard are shown together; controllers do not access repositories. General catalog, inventory, packing, booking, history, review, dashboard, labels and reports reject temporary access in their service entry points.

CSRF is required for **every unsafe method**, including public login, setup, redemption and DELETE logout, and read-only POST labels/previews. Spring MVC dispatches HEAD through GET handlers, subject to the exact security method allowlists: public and temporary GET permissions do not automatically admit HEAD. The same role and tenant boundaries apply, and checked-code GET/HEAD share one admission budget. Automatic OPTIONS/preflight conveys mapping metadata, not resource bodies; application CORS does not grant cross-origin credential access. The matrix inventories declared methods, not implicit HEAD/OPTIONS.

Rate codes: `login` = normalized username/client 5 per 15 minutes and client-wide 60 per 15 minutes; `lookup` = org/user 120 per minute and client-wide 600 per minute; `redemption` = the existing invitation-digest/client admission. Login success never resets either budget. Login and lookup two-key admission atomically checks both counters and store capacity before incrementing either. Each has at most 10,000 retained digest keys by default, reclaims only expired state at `now >= deadline`, and fails closed when live capacity is full. Configurations require positive budgets/capacity/duration. Login usernames are trimmed and case-normalized with Locale.ROOT, matching local case-insensitive authentication. Length-framed SHA-256 keys avoid delimiter collisions and retaining raw usernames; no credential is logged or returned by the limiter. A 429 says only to retry later, independently of account/code existence.

These rate windows live in **one JVM**: restart resets state, and multiple instances each enforce their own budgets; a deployment needing a fleet-wide ceiling must add a shared admission store in a later reviewed task. Addresses come from `HttpServletRequest.getRemoteAddr()` after Boot's forwarded-header handling. The production proxy must overwrite untrusted forwarded headers and port 8080 must remain private (ADR-0004 and deployment instructions); application code does not parse caller-supplied forwarded headers. Quotas are configurable under `tarpeisto.security.login-rate-limit.*` and `tarpeisto.security.asset-code-rate-limit.*` (see application.yml). Test-only high client admission in the shared integration fixture is not a production default.

For global user operations, the service first resolves the target membership in the caller's organization, then rejects a target with any other-organization membership before changing account state, session rows, identities or activity. Identity unlink also verifies the identity belongs to the nested target. Reactivating an inactive identity also requires the previous user to belong only to the caller's organization before retaining or returning profile claims and persisting the new mapping. Enabled/local-Owner recovery guards remain authoritative; membership-role changes are local to the active organization. Restoring an archived user does not enable it.

## Application REST routes

| Method | Path | Controller -> service guard | O | D | OA | V | T | A | Scope / additional authorization | CSRF | Rate |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| GET | /api/v1/application | ApplicationInfoController#get -> SecurityConfiguration.securityFilterChain (public rule) | Y | Y | Y | Y | Y | Y | public build/provider metadata | safe | none |
| POST | /api/v1/asset-labels/calibration | AssetLabelController#calibration -> AssetLabelService.calibration | Y | Y | Y | Y | N | N | org assets or authenticated calibration | required | none |
| POST | /api/v1/asset-labels/pdf | AssetLabelController#pdf -> AssetLabelService.labels | Y | Y | Y | Y | N | N | org assets or authenticated calibration | required | none |
| POST | /api/v1/asset-labels/ptouch-csv | AssetLabelController#ptouchCsv -> AssetLabelService.ptouchCsv | Y | Y | Y | Y | N | N | org assets or authenticated calibration | required | none |
| GET | /api/v1/asset-models | AssetModelController#list -> AssetModelService.list | Y | Y | Y | Y | N | N | org | safe | none |
| POST | /api/v1/asset-models | AssetModelController#create -> AssetModelService.create | Y | Y | N | N | N | N | org | required | none |
| GET | /api/v1/asset-models/search | CatalogSearchController#searchModels -> CatalogSearchService.models | Y | Y | Y | Y | N | N | org | safe | none |
| GET | /api/v1/asset-models/{assetModelId} | AssetModelController#get -> AssetModelService.get | Y | Y | Y | Y | N | N | org | safe | none |
| PUT | /api/v1/asset-models/{assetModelId} | AssetModelController#rename -> AssetModelService.rename | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/asset-models/{assetModelId}/archive | AssetModelController#archive -> AssetModelService.archive | Y | Y | N | N | N | N | org | required | none |
| GET | /api/v1/asset-models/{assetModelId}/assets | AssetController#list -> AssetService.list | Y | Y | Y | Y | N | N | org | safe | none |
| POST | /api/v1/asset-models/{assetModelId}/assets | AssetController#create -> AssetService.create | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/asset-models/{assetModelId}/assets/bulk | AssetController#createBulk -> AssetService.createBulk | Y | Y | N | N | N | N | org | required | none |
| PUT | /api/v1/asset-models/{assetModelId}/can-contain-assets | AssetModelController#setCanContainAssets -> AssetModelService.setCanContainAssets | Y | Y | N | N | N | N | org | required | none |
| PUT | /api/v1/asset-models/{assetModelId}/category | AssetModelController#changeCategory -> AssetModelService.changeCategory | Y | Y | N | N | N | N | org | required | none |
| GET | /api/v1/asset-models/{assetModelId}/consumable-stock | ConsumableStockController#listByModel -> ConsumableStockService.listByModel | Y | Y | Y | Y | N | N | org | safe | none |
| POST | /api/v1/asset-models/{assetModelId}/consumable-stock/adjust | ConsumableStockController#adjust -> ConsumableStockService.adjustAtPlace | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/asset-models/{assetModelId}/consumable-stock/consume | ConsumableStockController#consume -> ConsumableStockService.changeAtPlace | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/asset-models/{assetModelId}/consumable-stock/issue | ConsumableStockController#issue -> ConsumableStockService.changeAtPlace | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/asset-models/{assetModelId}/consumable-stock/receive | ConsumableStockController#receive -> ConsumableStockService.changeAtPlace | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/asset-models/{assetModelId}/consumable-stock/return | ConsumableStockController#returnStock -> ConsumableStockService.changeAtPlace | Y | Y | N | N | N | N | org | required | none |
| GET | /api/v1/asset-models/{assetModelId}/consumable-stock/summary | ConsumableStockController#summary -> ConsumableStockService.stockSummary | Y | Y | Y | Y | N | N | org | safe | none |
| POST | /api/v1/asset-models/{assetModelId}/consumable-stock/transfer | ConsumableStockController#transfer -> ConsumableStockService.transferBetweenPlaces | Y | Y | N | N | N | N | org | required | none |
| GET | /api/v1/asset-models/{assetModelId}/custom-fields | ModelCustomFieldController#list -> ModelCustomFieldService.list | Y | Y | Y | Y | N | N | org | safe | none |
| POST | /api/v1/asset-models/{assetModelId}/custom-fields | ModelCustomFieldController#create -> ModelCustomFieldService.create | Y | Y | N | N | N | N | org | required | none |
| PUT | /api/v1/asset-models/{assetModelId}/custom-fields/{fieldId} | ModelCustomFieldController#rename -> ModelCustomFieldService.rename | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/asset-models/{assetModelId}/custom-fields/{fieldId}/archive | ModelCustomFieldController#archive -> ModelCustomFieldService.archive | Y | Y | N | N | N | N | org | required | none |
| PUT | /api/v1/asset-models/{assetModelId}/custom-fields/{fieldId}/data-type | ModelCustomFieldController#changeDataType -> ModelCustomFieldService.changeDataType | Y | Y | N | N | N | N | org | required | none |
| PUT | /api/v1/asset-models/{assetModelId}/custom-fields/{fieldId}/display-order | ModelCustomFieldController#reorder -> ModelCustomFieldService.reorder | Y | Y | N | N | N | N | org | required | none |
| GET | /api/v1/asset-models/{assetModelId}/custom-fields/{fieldId}/options | ModelCustomFieldOptionController#list -> ModelCustomFieldOptionService.list | Y | Y | Y | Y | N | N | org | safe | none |
| POST | /api/v1/asset-models/{assetModelId}/custom-fields/{fieldId}/options | ModelCustomFieldOptionController#create -> ModelCustomFieldOptionService.create | Y | Y | N | N | N | N | org | required | none |
| PUT | /api/v1/asset-models/{assetModelId}/custom-fields/{fieldId}/options/{optionId} | ModelCustomFieldOptionController#rename -> ModelCustomFieldOptionService.rename | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/asset-models/{assetModelId}/custom-fields/{fieldId}/options/{optionId}/archive | ModelCustomFieldOptionController#archive -> ModelCustomFieldOptionService.archive | Y | Y | N | N | N | N | org | required | none |
| PUT | /api/v1/asset-models/{assetModelId}/custom-fields/{fieldId}/options/{optionId}/display-order | ModelCustomFieldOptionController#reorder -> ModelCustomFieldOptionService.reorder | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/asset-models/{assetModelId}/custom-fields/{fieldId}/options/{optionId}/restore | ModelCustomFieldOptionController#restore -> ModelCustomFieldOptionService.restore | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/asset-models/{assetModelId}/custom-fields/{fieldId}/restore | ModelCustomFieldController#restore -> ModelCustomFieldService.restore | Y | Y | N | N | N | N | org | required | none |
| GET | /api/v1/asset-models/{assetModelId}/media/reference | MediaController#getModelReference -> MediaService.getModelReference | Y | Y | Y | Y | S | N | org media owner/purpose; T references/evidence inside assignment | safe | none |
| POST | /api/v1/asset-models/{assetModelId}/media/reference | MediaController#uploadModelReference -> MediaService.uploadModelReference | Y | Y | N | N | N | N | org media owner/purpose; T references/evidence inside assignment | required | none |
| PUT | /api/v1/asset-models/{assetModelId}/replacement-url | AssetModelController#changeReplacementUrl -> AssetModelService.changeReplacementUrl | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/asset-models/{assetModelId}/restore | AssetModelController#restore -> AssetModelService.restore | Y | Y | N | N | N | N | org | required | none |
| PUT | /api/v1/asset-models/{assetModelId}/tracking-mode | AssetModelController#changeTrackingMode -> AssetModelService.changeTrackingMode | Y | Y | N | N | N | N | org | required | none |
| GET | /api/v1/assets | AssetController#search -> AssetService.search | Y | Y | Y | Y | N | N | org | safe | none |
| GET | /api/v1/assets/by-code/{rawCode} | AssetController#getByCode -> AssetService.getByCode | Y | Y | Y | Y | N | N | org | safe | lookup |
| GET | /api/v1/assets/{assetId} | AssetController#get -> AssetService.get | Y | Y | Y | Y | N | N | org | safe | none |
| POST | /api/v1/assets/{assetId}/archive | AssetController#archive -> AssetService.archive | Y | Y | N | N | N | N | org | required | none |
| PUT | /api/v1/assets/{assetId}/condition | AssetController#changeCondition -> AssetService.changeCondition | Y | Y | N | N | N | N | org | required | none |
| GET | /api/v1/assets/{assetId}/consumable-stock | ConsumableStockController#listAtContainer -> ConsumableStockService.listAtContainer | Y | Y | Y | Y | N | N | org | safe | none |
| GET | /api/v1/assets/{assetId}/contents | AssetController#contents -> AssetPlacementService.contents | Y | Y | Y | Y | N | N | org | safe | none |
| GET | /api/v1/assets/{assetId}/history | AssetController#history -> AssetService.history | Y | Y | Y | Y | N | N | org | safe | none |
| PUT | /api/v1/assets/{assetId}/lifecycle | AssetController#changeLifecycleState -> AssetService.changeLifecycleState | Y | Y | N | N | N | N | org | required | none |
| GET | /api/v1/assets/{assetId}/media/layout | MediaController#listLayouts -> MediaService.listContainerLayouts | Y | Y | Y | Y | S | N | org media owner/purpose; T references/evidence inside assignment | safe | none |
| POST | /api/v1/assets/{assetId}/media/layout | MediaController#uploadLayout -> MediaService.uploadContainerLayout | Y | Y | N | N | N | N | org media owner/purpose; T references/evidence inside assignment | required | none |
| GET | /api/v1/assets/{assetId}/media/reference | MediaController#getAssetReference -> MediaService.getAssetReference | Y | Y | Y | Y | S | N | org media owner/purpose; T references/evidence inside assignment | safe | none |
| POST | /api/v1/assets/{assetId}/media/reference | MediaController#uploadAssetReference -> MediaService.uploadAssetReference | Y | Y | N | N | N | N | org media owner/purpose; T references/evidence inside assignment | required | none |
| PUT | /api/v1/assets/{assetId}/name | AssetController#rename -> AssetService.rename | Y | Y | N | N | N | N | org | required | none |
| GET | /api/v1/assets/{assetId}/packing-sheet.pdf | PackingSheetController#pdf -> PackingSheetService.pdf | Y | Y | Y | Y | N | N | org | safe | none |
| GET | /api/v1/assets/{assetId}/placement | AssetController#placement -> AssetPlacementService.get | Y | Y | Y | Y | N | N | org | safe | none |
| PUT | /api/v1/assets/{assetId}/placement | AssetController#move -> AssetPlacementService.move | Y | Y | N | N | N | N | org | required | none |
| PUT | /api/v1/assets/{assetId}/purchase-date | AssetController#changePurchaseDate -> AssetService.changePurchaseDate | Y | Y | N | N | N | N | org | required | none |
| GET | /api/v1/assets/{assetId}/repairs | ReviewController#repairs -> ReviewService.repairs | Y | Y | Y | Y | N | N | org completed finding/reviewer or asset repair history | safe | none |
| POST | /api/v1/assets/{assetId}/repairs | ReviewController#openRepair -> ReviewService.openRepair | Y | Y | N | N | N | N | org completed finding/reviewer or asset repair history | required | none |
| POST | /api/v1/assets/{assetId}/replacement | AssetController#createReplacement -> AssetService.createReplacement | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/assets/{assetId}/restore | AssetController#restore -> AssetService.restore | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/assets/{assetId}/seal/break | AssetController#breakSeal -> AssetSealService.breakSeal | Y | Y | N | N | N | N | org | required | none |
| GET | /api/v1/assets/{assetId}/seal/history | AssetController#sealHistory -> AssetSealService.history | Y | Y | Y | Y | N | N | org | safe | none |
| PUT | /api/v1/assets/{assetId}/sealable | AssetController#setSealable -> AssetSealService.setSealable | Y | Y | N | N | N | N | org | required | none |
| PUT | /api/v1/assets/{assetId}/values | AssetController#setValues -> AssetService.setValues | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/assets/{containerAssetId}/packing-preview | PackingRequirementController#preview -> PackingRequirementService.preview | Y | Y | Y | Y | N | N | org | required | none |
| GET | /api/v1/assets/{containerAssetId}/packing-requirements | PackingRequirementController#list -> PackingRequirementService.list | Y | Y | Y | Y | N | N | org | safe | none |
| POST | /api/v1/assets/{containerAssetId}/packing-requirements | PackingRequirementController#add -> PackingRequirementService.add | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/assets/{containerAssetId}/packing-templates/{templateId}/apply | PackingRequirementController#apply -> PackingRequirementService.applyTemplate | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/audits/containers/{containerId}/launch | AuditController#launch -> AuditService.launchContainerAudit | Y | Y | Y | N | N | N | org audit/task; T exact assignment; moves validate both audits | required | none |
| GET | /api/v1/audits/tasks/{taskId} | AuditController#get -> AuditService.get | Y | Y | Y | Y | S | N | org audit/task; T exact assignment; moves validate both audits | safe | none |
| GET | /api/v1/audits/tasks/{taskId}/container | AuditController#container -> AuditService.container | Y | Y | Y | Y | S | N | org audit/task; T exact assignment; moves validate both audits | safe | none |
| POST | /api/v1/audits/tasks/{taskId}/start | AuditController#start -> AuditService.start | Y | Y | Y | N | S | N | org audit/task; T exact assignment; moves validate both audits | required | none |
| POST | /api/v1/audits/{auditId}/complete | AuditController#complete -> AuditService.complete | Y | Y | Y | N | S | N | org audit/task; T exact assignment; moves validate both audits | required | none |
| POST | /api/v1/audits/{auditId}/consumables/{expectedId} | AuditController#consumable -> AuditService.observeConsumable | Y | Y | Y | N | S | N | org audit/task; T exact assignment; moves validate both audits; adjustments/corrections only O/D, OA/T observations only | required | none |
| GET | /api/v1/audits/{auditId}/evidence | MediaController#auditEvidence -> MediaService.listAuditEvidence | Y | Y | Y | Y | S | N | org media owner/purpose; T references/evidence inside assignment | safe | none |
| POST | /api/v1/audits/{auditId}/findings | AuditController#finding -> AuditService.recordFinding | Y | Y | Y | N | S | N | org audit/task; T exact assignment; moves validate both audits | required | none |
| POST | /api/v1/audits/{auditId}/findings/{findingId}/evidence | MediaController#uploadEvidence -> MediaService.uploadAuditEvidence | Y | Y | Y | N | S | N | org media owner/purpose; T references/evidence inside assignment; writable audit, actor and idempotent operation | required | none |
| POST | /api/v1/audits/{auditId}/move-code-here | AuditController#moveCode -> AuditService.moveCodeHere | Y | Y | Y | N | S | N | org audit/task; T exact assignment; moves validate both audits | required | none |
| POST | /api/v1/audits/{auditId}/move-scan-here | AuditController#move -> AuditService.moveScanHere | Y | Y | Y | N | S | N | org audit/task; T exact assignment; moves validate both audits | required | none |
| POST | /api/v1/audits/{auditId}/scans | AuditController#scan -> AuditService.scan | Y | Y | Y | N | S | N | org audit/task; T exact assignment; moves validate both audits | required | none |
| POST | /api/v1/audits/{auditId}/scans/{scanId}/undo | AuditController#undo -> AuditService.undo | Y | Y | Y | N | S | N | org audit/task; T exact assignment; moves validate both audits | required | none |
| PUT | /api/v1/audits/{id}/archive | ArchiveController#changeAudit -> ArchiveService.change | Y | Y | N | N | N | N | org, expected version and archive eligibility; shared USER forbidden | required | none |
| GET | /api/v1/bookings | BookingController#list -> BookingService.list | Y | Y | Y | Y | N | N | org | safe | none |
| POST | /api/v1/bookings | BookingController#create -> BookingService.create | Y | Y | N | N | N | N | org | required | none |
| GET | /api/v1/bookings/{bookingId} | BookingController#get -> BookingService.get | Y | Y | Y | Y | N | N | org | safe | none |
| PUT | /api/v1/bookings/{bookingId} | BookingController#update -> BookingService.update | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/bookings/{bookingId}/cancel | BookingController#cancel -> BookingReservationService.cancel | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/bookings/{bookingId}/check-in/assets/{assetId} | BookingController#checkInAsset -> CheckoutService.checkInAsset | Y | Y | Y | N | N | N | org event/manifest; checkout override O/D only | required | none |
| POST | /api/v1/bookings/{bookingId}/check-in/complete | BookingController#completeReturn -> CheckoutService.completeReturn | Y | Y | Y | N | N | N | org event/manifest; checkout override O/D only | required | none |
| POST | /api/v1/bookings/{bookingId}/check-in/consumables/{manifestConsumableId} | BookingController#returnConsumable -> CheckoutService.returnConsumable | Y | Y | Y | N | N | N | org event/manifest; checkout override O/D only | required | none |
| POST | /api/v1/bookings/{bookingId}/checkout | BookingController#checkout -> CheckoutService.checkout | Y | Y | Y | N | N | N | org event/manifest; checkout override O/D only | required | none |
| GET | /api/v1/bookings/{bookingId}/checkout-manifest | BookingController#checkoutManifest -> CheckoutService.get | Y | Y | Y | Y | N | N | org event/manifest; checkout override O/D only | safe | none |
| GET | /api/v1/bookings/{bookingId}/checkout-manifest.pdf | BookingController#checkoutPdf -> CheckoutService.pdf | Y | Y | Y | Y | N | N | org event/manifest; checkout override O/D only | safe | none |
| GET | /api/v1/bookings/{bookingId}/history | BookingController#history -> BookingHistoryService.list | Y | Y | Y | Y | N | N | org | safe | none |
| POST | /api/v1/bookings/{bookingId}/lines | BookingController#addLine -> BookingService.addLine | Y | Y | N | N | N | N | org | required | none |
| DELETE | /api/v1/bookings/{bookingId}/lines/{lineId} | BookingController#removeLine -> BookingService.removeLine | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/bookings/{bookingId}/reservation-preview | BookingController#preview -> BookingReservationService.preview | Y | Y | Y | Y | N | N | org | required | none |
| POST | /api/v1/bookings/{bookingId}/reserve | BookingController#reserve -> BookingReservationService.reserve | Y | Y | N | N | N | N | org | required | none |
| PUT | /api/v1/bookings/{id}/archive | ArchiveController#changeBooking -> ArchiveService.change | Y | Y | N | N | N | N | org, expected version and archive eligibility; shared USER forbidden | required | none |
| GET | /api/v1/categories | CategoryController#list -> CategoryService.list | Y | Y | Y | Y | N | N | org | safe | none |
| POST | /api/v1/categories | CategoryController#create -> CategoryService.create | Y | Y | N | N | N | N | org | required | none |
| DELETE | /api/v1/categories/{categoryId} | CategoryController#delete -> CategoryService.delete | Y | N | N | N | N | N | org | required | none |
| PUT | /api/v1/categories/{categoryId} | CategoryController#rename -> CategoryService.rename | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/categories/{categoryId}/archive | CategoryController#archive -> CategoryService.archive | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/categories/{categoryId}/restore | CategoryController#restore -> CategoryService.restore | Y | Y | N | N | N | N | org | required | none |
| GET | /api/v1/consumable-stock/low-stock | ConsumableStockController#lowStock -> ConsumableStockService.lowStockSummaries | Y | Y | Y | Y | N | N | org | safe | none |
| GET | /api/v1/consumable-stock/search | CatalogSearchController#searchStock -> CatalogSearchService.stocks | Y | Y | Y | Y | N | N | org | safe | none |
| GET | /api/v1/consumable-stock/{balanceId} | ConsumableStockController#get -> ConsumableStockService.get | Y | Y | Y | Y | N | N | org | safe | none |
| GET | /api/v1/consumable-stock/{balanceId}/movements | ConsumableStockController#movements -> ConsumableStockService.ledger | Y | Y | Y | Y | N | N | org | safe | none |
| PUT | /api/v1/consumable-stock/{id}/archive | ArchiveController#changeStock -> ArchiveService.change | Y | Y | N | N | N | N | org, expected version and archive eligibility; shared USER forbidden | required | none |
| GET | /api/v1/dashboard | DashboardController#get -> DashboardService.get | Y | Y | Y | Y | N | N | org | safe | none |
| GET | /api/v1/dashboard/{queue} | DashboardController#page -> DashboardService.page | Y | Y | Y | Y | N | N | org | safe | none |
| GET | /api/v1/findings | ReviewController#list -> ReviewService.list | Y | Y | N | N | N | N | org completed finding/reviewer or asset repair history | safe | none |
| GET | /api/v1/findings/{findingId} | ReviewController#get -> ReviewService.get | Y | Y | N | N | N | N | org completed finding/reviewer or asset repair history | safe | none |
| GET | /api/v1/findings/{findingId}/evidence | MediaController#findingEvidence -> MediaService.listFindingEvidence | Y | Y | Y | Y | S | N | org media owner/purpose; T references/evidence inside assignment | safe | none |
| POST | /api/v1/findings/{findingId}/resolutions | ReviewController#resolve -> ReviewService.resolve | Y | Y | N | N | N | N | org completed finding/reviewer or asset repair history | required | none |
| GET | /api/v1/locations | LocationController#list -> LocationService.list | Y | Y | Y | Y | N | N | org | safe | none |
| POST | /api/v1/locations | LocationController#create -> LocationService.create | Y | Y | N | N | N | N | org | required | none |
| GET | /api/v1/locations/{locationId} | LocationController#get -> LocationService.get | Y | Y | Y | Y | N | N | org | safe | none |
| PUT | /api/v1/locations/{locationId} | LocationController#update -> LocationService.update | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/locations/{locationId}/archive | LocationController#archive -> LocationService.archive | Y | Y | N | N | N | N | org | required | none |
| GET | /api/v1/locations/{locationId}/consumable-stock | ConsumableStockController#listAtLocation -> ConsumableStockService.listAtLocation | Y | Y | Y | Y | N | N | org | safe | none |
| POST | /api/v1/locations/{locationId}/restore | LocationController#restore -> LocationService.restore | Y | Y | N | N | N | N | org | required | none |
| DELETE | /api/v1/media/{mediaId} | MediaController#delete -> MediaService.delete | Y | Y | N | N | N | N | org media owner/purpose; T references/evidence inside assignment | required | none |
| GET | /api/v1/media/{mediaId} | MediaController#stream -> MediaService.open | Y | Y | Y | Y | S | N | org media owner/purpose; T references/evidence inside assignment | safe | none |
| POST | /api/v1/media/{mediaId}/cleanup | MediaController#retryCleanup -> MediaService.retryCleanup | Y | Y | N | N | N | N | org media owner/purpose; T references/evidence inside assignment | required | none |
| PUT | /api/v1/media/{mediaId}/layout | MediaController#updateLayout -> MediaService.updateLayout | Y | Y | N | N | N | N | org media owner/purpose; T references/evidence inside assignment | required | none |
| GET | /api/v1/media/{mediaId}/thumbnail | MediaController#thumbnail -> MediaService.open | Y | Y | Y | Y | S | N | org media owner/purpose; T references/evidence inside assignment | safe | none |
| PUT | /api/v1/packing-requirements/{id} | PackingRequirementController#update -> PackingRequirementService.update | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/packing-requirements/{id}/archive | PackingRequirementController#archive -> PackingRequirementService.archive | Y | Y | N | N | N | N | org | required | none |
| GET | /api/v1/packing-requirements/{id}/reservation-impact | PackingRequirementController#reservationImpact -> PackingRequirementService.reservationImpact | Y | Y | Y | Y | N | N | org | safe | none |
| POST | /api/v1/packing-requirements/{id}/restore | PackingRequirementController#restore -> PackingRequirementService.restore | Y | Y | N | N | N | N | org | required | none |
| PUT | /api/v1/packing-template-requirements/{id} | PackingRequirementController#updateTemplateRequirement -> PackingRequirementService.updateTemplateRequirement | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/packing-template-requirements/{id}/archive | PackingRequirementController#setTemplateRequirementArchived -> PackingRequirementService.setTemplateRequirementArchived | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/packing-template-requirements/{id}/restore | PackingRequirementController#setTemplateRequirementArchived -> PackingRequirementService.setTemplateRequirementArchived | Y | Y | N | N | N | N | org | required | none |
| GET | /api/v1/packing-templates | PackingRequirementController#templates -> PackingRequirementService.listTemplates | Y | Y | Y | Y | N | N | org | safe | none |
| POST | /api/v1/packing-templates | PackingRequirementController#createTemplate -> PackingRequirementService.createTemplate | Y | Y | N | N | N | N | org | required | none |
| PUT | /api/v1/packing-templates/{templateId} | PackingRequirementController#updateTemplate -> PackingRequirementService.updateTemplate | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/packing-templates/{templateId}/archive | PackingRequirementController#setTemplateArchived -> PackingRequirementService.setTemplateArchived | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/packing-templates/{templateId}/requirements | PackingRequirementController#addTemplateRequirement -> PackingRequirementService.addTemplateRequirement | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/packing-templates/{templateId}/restore | PackingRequirementController#setTemplateArchived -> PackingRequirementService.setTemplateArchived | Y | Y | N | N | N | N | org | required | none |
| POST | /api/v1/repairs/{repairId}/close | ReviewController#closeRepair -> ReviewService.closeRepair | Y | Y | N | N | N | N | org completed finding/reviewer or asset repair history | required | none |
| GET | /api/v1/reports/audits.csv | OperationalReportController#audits -> OperationalReportService.create | Y | Y | Y | Y | N | N | org | safe | none |
| GET | /api/v1/reports/audits/{auditId}.csv | OperationalReportController#audit -> OperationalReportService.create | Y | Y | Y | Y | N | N | org | safe | none |
| GET | /api/v1/reports/consumable-balances.csv | OperationalReportController#balances -> OperationalReportService.create | Y | Y | Y | Y | N | N | org | safe | none |
| GET | /api/v1/reports/inventory.csv | OperationalReportController#inventory -> OperationalReportService.create | Y | Y | Y | Y | N | N | org | safe | none |
| GET | /api/v1/reports/stock-movements.csv | OperationalReportController#movements -> OperationalReportService.create | Y | Y | Y | Y | N | N | org | safe | none |
| DELETE | /api/v1/session | SessionController#logout -> SessionAuthenticationService.logout | Y | Y | Y | Y | Y | Y | idempotent logout | required | none |
| GET | /api/v1/session | SessionController#current -> SecurityConfiguration.securityFilterChain + PrincipalRefreshFilter (authenticated rule) | Y | Y | Y | Y | Y | N | current browser identity | safe | none |
| POST | /api/v1/session | SessionController#login -> SessionAuthenticationService.login | Y | Y | Y | Y | Y | Y | credential exchange | required | login |
| GET | /api/v1/setup | InitialSetupController#status -> InitialSetupService.isSetupRequired | Y | Y | Y | Y | Y | Y | public setup status | safe | none |
| POST | /api/v1/setup | InitialSetupController#completeInitialSetup -> InitialSetupService.complete | C | C | C | C | C | C | initial setup only | required | none |
| GET | /api/v1/temporary-access/invitations | TemporaryAccessController#list -> TemporaryAccessService.list | Y | Y | N | N | N | N | org invitation exact event/batch | safe | none |
| POST | /api/v1/temporary-access/invitations | TemporaryAccessController#create -> TemporaryInvitationService.create | Y | Y | N | N | N | N | org invitation exact event/batch | required | none |
| POST | /api/v1/temporary-access/invitations/{invitationId}/revoke | TemporaryAccessController#revoke -> TemporaryAccessService.revoke | Y | Y | N | N | N | N | org invitation exact event/batch | required | none |
| POST | /api/v1/temporary-access/redemptions | TemporaryAccessController#redeem -> TemporaryAccessService.redeem | Y | Y | Y | Y | Y | Y | valid fixed-deadline invitation, named operation | required | redemption |
| GET | /api/v1/temporary-access/tasks | TemporaryAccessController#tasks -> AuditService.assignedTasks | N | N | N | N | S | N | exact assigned event/batch tasks | safe | none |
| GET | /api/v1/users | UserController#list -> UserService.listUsers | Y | N | N | N | N | N | owned membership; role writes affect this org only | safe | none |
| POST | /api/v1/users | UserController#create -> UserService.createUser | Y | N | N | N | N | N | owned membership; role writes affect this org only | required | none |
| PUT | /api/v1/users/{id}/archive | ArchiveController#changeUser -> ArchiveService.change | Y | N | N | N | N | N | org, expected version and archive eligibility; shared USER forbidden | required | none |
| PUT | /api/v1/users/{userId}/enabled | UserController#setEnabled -> UserService.setEnabled | Y | N | N | N | N | N | owned membership; global access/identity writes and identity reads reject shared accounts | required | none |
| GET | /api/v1/users/{userId}/external-identities | ExternalIdentityController#list -> ExternalIdentityService.listIdentities | Y | N | N | N | N | N | owned membership; global access/identity writes and identity reads reject shared accounts | safe | none |
| POST | /api/v1/users/{userId}/external-identities | ExternalIdentityController#create -> ExternalIdentityService.createLink | Y | N | N | N | N | N | owned membership; global access/identity writes and identity reads reject shared accounts | required | none |
| DELETE | /api/v1/users/{userId}/external-identities/{externalIdentityId} | ExternalIdentityController#unlink -> ExternalIdentityService.unlink | Y | N | N | N | N | N | owned membership; global access/identity writes and identity reads reject shared accounts | required | none |
| PUT | /api/v1/users/{userId}/role | UserController#changeRole -> UserService.changeRole | Y | N | N | N | N | N | owned membership; role writes affect this org only | required | none |

## Non-application surfaces

- Spring Security OIDC authorization `/oauth2/authorization/oidc` and callback `/login/oauth2/code/oidc` are filter routes, not MVC application controllers. They exist only in LOCAL_AND_OIDC mode; provider registration, state/nonce, issuer/subject validation, session rotation, enabled-user resolution and explicit linking policy govern the flow. Callback query parameters and tokens must not enter logs. Local password recovery remains available. These are not permission to administer external identities.
- Actuator GET health (including health subpaths) and info are public; other actuator endpoints remain authenticated/limited by exposure configuration. Public health has no tenant data or credentials. These Boot-owned mappings are excluded from the application MVC drift gate.
- SPA HTML, static resources and client-side routes (including `/status`, `/join`, `/sign-in`) are public shell routes. API authorization remains authoritative; frontend visibility is not permission. The invitation fragment is removed before requests and referrers are suppressed.
- `/v3/api-docs/**`, `/swagger-ui/**` and `/swagger-ui.html` are public only in the dev profile and denied in production, independently of SPA fallback. Springdoc-owned mappings are excluded from this gate. Production documentation flags do not override the security deny.

Review gate: `SecurityAuthorizationMatrixIntegrationTests` compares all application-owned handlers from `RequestMappingHandlerMapping` against every table row, preserving ordinary path variables and expanding literal regex alternatives; constraints cannot silently widen. The existing permission/integration suites exercise role/tenant/CSRF/scanner/media boundaries. The matrix is a review inventory, not a replacement for those tests or server authorization.
