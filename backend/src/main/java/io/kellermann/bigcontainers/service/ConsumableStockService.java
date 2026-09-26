package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.exception.InsufficientStockException;
import io.kellermann.bigcontainers.exception.NotFoundException;
import io.kellermann.bigcontainers.exception.ValidationFailedException;
import io.kellermann.bigcontainers.model.Asset;
import io.kellermann.bigcontainers.model.AssetModel;
import io.kellermann.bigcontainers.model.ConsumableStock;
import io.kellermann.bigcontainers.model.OrganizationRole;
import io.kellermann.bigcontainers.model.StockMovement;
import io.kellermann.bigcontainers.model.StockMovementReason;
import io.kellermann.bigcontainers.repository.AssetModelRepository;
import io.kellermann.bigcontainers.repository.AssetRepository;
import io.kellermann.bigcontainers.repository.ConsumableStockLedgerRepository;
import io.kellermann.bigcontainers.repository.ConsumableStockLedgerRepository.BalanceState;
import io.kellermann.bigcontainers.repository.ConsumableStockLedgerRepository.TransferLock;
import io.kellermann.bigcontainers.repository.ConsumableStockRepository;
import io.kellermann.bigcontainers.repository.StockMovementRepository;
import io.kellermann.bigcontainers.security.BigContainersPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumable stock balances and their immutable movement ledger (specification section 6.4).
 *
 * <p>Every read is scoped to {@code principal.organizationId()}; every mutation additionally
 * requires Owner or Deputy, re-checked here rather than in a URL matcher or the controller, per
 * docs/DEVELOPMENT_POLICIES.md section 5.1 - the same pattern {@link AssetService}/{@link
 * AssetModelService} follow. Specification section 4.1 places "normal inventory administration"
 * under Deputy, and this task's scope note is explicit that "a volunteer or Operator must not be
 * able to make a balance-changing adjustment" - so every movement type (not only adjustments)
 * requires Owner or Deputy here, matching how {@code AssetService} already gates every physical
 * asset mutation the same way.
 *
 * <p><strong>Only a {@code QUANTITY_STOCK} model may have a balance</strong> (specification section
 * 6.2/6.4): enforced via {@link AssetModelService#requireQuantityStockAssetModel}, and a second time
 * by the {@code tr_stock_balance_reject_serialized_model} database trigger.
 *
 * <p><strong>Concurrency and atomicity</strong> are implemented entirely in {@link
 * ConsumableStockLedgerRepository} - see its Javadoc for the two locking strategies (a single
 * atomic conditional {@code UPDATE} for one balance, and ordered {@code SELECT ... FOR UPDATE} for
 * a transfer's two balances) and the transfer lock-ordering rule that avoids deadlocks. Every
 * mutation method below is one {@code @Transactional} method, so a failure partway through (for
 * example, the destination side of a transfer) rolls back everything already done in the same
 * method, including the source side - there is no way to observe a partially applied transfer.
 *
 * <p>Deliberately out of scope for this task (see the task's scope note): packing requirements
 * (Phase 5), and real events/bookings and audits (Phases 7/9) - {@code eventReferenceId}/{@code
 * auditReferenceId} are accepted as opaque, unvalidated {@link UUID}s today.
 */
@Service
public class ConsumableStockService {

    private static final int MAX_QUANTITY_SCALE = 3;

    private final ConsumableStockRepository consumableStockRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ConsumableStockLedgerRepository ledgerRepository;
    private final AssetRepository assetRepository;
    private final AssetModelRepository assetModelRepository;
    private final AssetModelService assetModelService;
    private final ActivityLogService activityLogService;
    private final Clock clock;

    public ConsumableStockService(
            ConsumableStockRepository consumableStockRepository,
            StockMovementRepository stockMovementRepository,
            ConsumableStockLedgerRepository ledgerRepository,
            AssetRepository assetRepository,
            AssetModelRepository assetModelRepository,
            AssetModelService assetModelService,
            ActivityLogService activityLogService,
            Clock clock) {
        this.consumableStockRepository = consumableStockRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.ledgerRepository = ledgerRepository;
        this.assetRepository = assetRepository;
        this.assetModelRepository = assetModelRepository;
        this.assetModelService = assetModelService;
        this.activityLogService = activityLogService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ConsumableStockView> listByModel(BigContainersPrincipal principal, UUID assetModelId) {
        requireAuthenticated(principal);
        AssetModel assetModel =
                assetModelService.requireQuantityStockAssetModel(principal.organizationId(), assetModelId);
        return consumableStockRepository
                .findAllByOrganizationIdAndAssetModelIdOrderByCreatedAtAsc(principal.organizationId(), assetModelId)
                .stream()
                .map(balance -> toView(balance, assetModel))
                .toList();
    }

    @Transactional(readOnly = true)
    public ConsumableStockView get(BigContainersPrincipal principal, UUID balanceId) {
        requireAuthenticated(principal);
        ConsumableStock balance = requireBalance(principal.organizationId(), balanceId);
        AssetModel assetModel =
                assetModelService.requireQuantityStockAssetModel(principal.organizationId(), balance.getAssetModelId());
        return toView(balance, assetModel);
    }

    @Transactional(readOnly = true)
    public List<StockMovementView> ledger(BigContainersPrincipal principal, UUID balanceId) {
        requireAuthenticated(principal);
        requireBalance(principal.organizationId(), balanceId);
        return stockMovementRepository
                .findAllByOrganizationIdAndConsumableStockBalanceIdOrderByOccurredAtDesc(
                        principal.organizationId(), balanceId)
                .stream()
                .map(StockMovementView::from)
                .toList();
    }

    /** Specification section 6.4/4.5: "Receive stock into a direct location or container." */
    @Transactional
    public ConsumableStockView receive(
            BigContainersPrincipal principal,
            UUID assetModelId,
            UUID containerAssetId,
            BigDecimal quantity,
            String note) {
        requireOwnerOrDeputy(principal);
        AssetModel assetModel =
                assetModelService.requireQuantityStockAssetModel(principal.organizationId(), assetModelId);
        requireContainerAsset(principal.organizationId(), containerAssetId);
        BigDecimal validQuantity = requirePositiveQuantity(quantity);

        return applySingleBalanceMovement(
                principal,
                assetModel,
                containerAssetId,
                validQuantity,
                StockMovementReason.RECEIPT,
                note,
                null,
                null,
                "STOCK_RECEIVED");
    }

    /** Specification section 6.4: {@code EVENT_ISSUE} - stock leaving a stock place. */
    @Transactional
    public ConsumableStockView issue(
            BigContainersPrincipal principal,
            UUID assetModelId,
            UUID containerAssetId,
            BigDecimal quantity,
            String note,
            UUID eventReferenceId) {
        requireOwnerOrDeputy(principal);
        AssetModel assetModel =
                assetModelService.requireQuantityStockAssetModel(principal.organizationId(), assetModelId);
        requireContainerAsset(principal.organizationId(), containerAssetId);
        BigDecimal validQuantity = requirePositiveQuantity(quantity);

        return applySingleBalanceMovement(
                principal,
                assetModel,
                containerAssetId,
                validQuantity.negate(),
                StockMovementReason.EVENT_ISSUE,
                note,
                eventReferenceId,
                null,
                "STOCK_ISSUED");
    }

    /** Specification section 6.4: {@code EVENT_RETURN} - stock coming back into a stock place. */
    @Transactional
    public ConsumableStockView returnStock(
            BigContainersPrincipal principal,
            UUID assetModelId,
            UUID containerAssetId,
            BigDecimal quantity,
            String note,
            UUID eventReferenceId) {
        requireOwnerOrDeputy(principal);
        AssetModel assetModel =
                assetModelService.requireQuantityStockAssetModel(principal.organizationId(), assetModelId);
        requireContainerAsset(principal.organizationId(), containerAssetId);
        BigDecimal validQuantity = requirePositiveQuantity(quantity);

        return applySingleBalanceMovement(
                principal,
                assetModel,
                containerAssetId,
                validQuantity,
                StockMovementReason.EVENT_RETURN,
                note,
                eventReferenceId,
                null,
                "STOCK_RETURNED");
    }

    /** Specification section 6.4: {@code CONSUMPTION} - stock permanently used up. */
    @Transactional
    public ConsumableStockView consume(
            BigContainersPrincipal principal,
            UUID assetModelId,
            UUID containerAssetId,
            BigDecimal quantity,
            String note) {
        requireOwnerOrDeputy(principal);
        AssetModel assetModel =
                assetModelService.requireQuantityStockAssetModel(principal.organizationId(), assetModelId);
        requireContainerAsset(principal.organizationId(), containerAssetId);
        BigDecimal validQuantity = requirePositiveQuantity(quantity);

        return applySingleBalanceMovement(
                principal,
                assetModel,
                containerAssetId,
                validQuantity.negate(),
                StockMovementReason.CONSUMPTION,
                note,
                null,
                null,
                "STOCK_CONSUMED");
    }

    /**
     * Specification section 6.4: "A transfer transactionally decrements the source and increments
     * the destination while preserving one linked movement operation." Both sides succeed or
     * neither does - see the class Javadoc for how atomicity and deadlock-free locking are
     * guaranteed.
     */
    @Transactional
    public StockTransferView transfer(
            BigContainersPrincipal principal,
            UUID assetModelId,
            UUID sourceContainerAssetId,
            UUID destinationContainerAssetId,
            BigDecimal quantity,
            String note) {
        requireOwnerOrDeputy(principal);
        AssetModel assetModel =
                assetModelService.requireQuantityStockAssetModel(principal.organizationId(), assetModelId);
        if (sourceContainerAssetId.equals(destinationContainerAssetId)) {
            throw new ValidationFailedException("Cannot transfer stock to the same stock place.");
        }
        Asset sourceAsset = requireContainerAsset(principal.organizationId(), sourceContainerAssetId);
        Asset destinationAsset = requireContainerAsset(principal.organizationId(), destinationContainerAssetId);
        BigDecimal validQuantity = requirePositiveQuantity(quantity);

        var now = clock.instant();
        TransferLock lock = ledgerRepository.lockContainerBalancesForTransfer(
                principal.organizationId(), assetModelId, sourceContainerAssetId, destinationContainerAssetId, now);

        BigDecimal newSourceQuantity = lock.source().quantity().subtract(validQuantity);
        if (newSourceQuantity.signum() < 0) {
            throw new InsufficientStockException(
                    "Insufficient stock at the source stock place to transfer " + validQuantity + ".");
        }
        BigDecimal newDestinationQuantity = lock.destination().quantity().add(validQuantity);

        ledgerRepository.setContainerBalanceQuantity(
                lock.source().balanceId(), principal.organizationId(), newSourceQuantity, now);
        ledgerRepository.setContainerBalanceQuantity(
                lock.destination().balanceId(), principal.organizationId(), newDestinationQuantity, now);

        UUID transferGroupId = UUID.randomUUID();
        recordMovement(
                principal,
                lock.source().balanceId(),
                validQuantity.negate(),
                newSourceQuantity,
                assetModel.getStockUnitLabel(),
                StockMovementReason.TRANSFER,
                note,
                transferGroupId,
                null,
                null,
                now);
        recordMovement(
                principal,
                lock.destination().balanceId(),
                validQuantity,
                newDestinationQuantity,
                assetModel.getStockUnitLabel(),
                StockMovementReason.TRANSFER,
                note,
                transferGroupId,
                null,
                null,
                now);

        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "STOCK_TRANSFERRED",
                "ASSET_MODEL",
                assetModelId,
                Map.of(
                        "sourceContainerAssetId", sourceContainerAssetId.toString(),
                        "destinationContainerAssetId", destinationContainerAssetId.toString(),
                        "quantity", validQuantity.toPlainString()));

        ConsumableStockView sourceView = new ConsumableStockView(
                lock.source().balanceId(),
                assetModelId,
                assetModel.getName(),
                assetModel.getStockUnitLabel(),
                sourceContainerAssetId,
                containerDisplayName(sourceAsset),
                newSourceQuantity,
                lock.source().createdAt(),
                now);
        ConsumableStockView destinationView = new ConsumableStockView(
                lock.destination().balanceId(),
                assetModelId,
                assetModel.getName(),
                assetModel.getStockUnitLabel(),
                destinationContainerAssetId,
                containerDisplayName(destinationAsset),
                newDestinationQuantity,
                lock.destination().createdAt(),
                now);
        return new StockTransferView(sourceView, destinationView);
    }

    /**
     * Specification section 6.4: "Owner/Deputy adjustments require an explicit reason and an
     * activity entry." {@code delta} may be positive or negative; {@code explicitReason} must be
     * non-blank - the enum {@code reason} alone ({@code MANUAL_ADJUSTMENT}/{@code AUDIT_ADJUSTMENT})
     * is not itself an explanation of why the correction was made.
     */
    @Transactional
    public ConsumableStockView adjust(
            BigContainersPrincipal principal,
            UUID assetModelId,
            UUID containerAssetId,
            BigDecimal delta,
            StockMovementReason reason,
            String explicitReason,
            UUID auditReferenceId) {
        requireOwnerOrDeputy(principal);
        if (reason != StockMovementReason.MANUAL_ADJUSTMENT && reason != StockMovementReason.AUDIT_ADJUSTMENT) {
            throw new ValidationFailedException("An adjustment must use MANUAL_ADJUSTMENT or AUDIT_ADJUSTMENT.");
        }
        if (explicitReason == null || explicitReason.isBlank()) {
            throw new ValidationFailedException("An adjustment requires an explicit reason.");
        }
        AssetModel assetModel =
                assetModelService.requireQuantityStockAssetModel(principal.organizationId(), assetModelId);
        requireContainerAsset(principal.organizationId(), containerAssetId);
        BigDecimal validDelta = requireNonZeroValidScale(delta);

        return applySingleBalanceMovement(
                principal,
                assetModel,
                containerAssetId,
                validDelta,
                reason,
                explicitReason,
                null,
                reason == StockMovementReason.AUDIT_ADJUSTMENT ? auditReferenceId : null,
                "STOCK_ADJUSTED");
    }

    @Transactional(readOnly = true)
    public AssetModelStockSummaryView stockSummary(BigContainersPrincipal principal, UUID assetModelId) {
        requireAuthenticated(principal);
        AssetModel assetModel =
                assetModelService.requireQuantityStockAssetModel(principal.organizationId(), assetModelId);
        return toSummary(assetModel);
    }

    @Transactional(readOnly = true)
    public List<AssetModelStockSummaryView> lowStockSummaries(BigContainersPrincipal principal) {
        requireAuthenticated(principal);
        return assetModelRepository.findAllByOrganizationIdOrderByNameAsc(principal.organizationId()).stream()
                .filter(model -> model.isQuantityTracked() && model.getLowStockThreshold() != null)
                .map(this::toSummary)
                .filter(AssetModelStockSummaryView::lowStock)
                .toList();
    }

    private AssetModelStockSummaryView toSummary(AssetModel assetModel) {
        BigDecimal total = consumableStockRepository
                .findAllByOrganizationIdAndAssetModelIdOrderByCreatedAtAsc(
                        assetModel.getOrganizationId(), assetModel.getId())
                .stream()
                .map(ConsumableStock::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean isLow =
                assetModel.getLowStockThreshold() != null && total.compareTo(assetModel.getLowStockThreshold()) <= 0;
        return new AssetModelStockSummaryView(
                assetModel.getId(),
                assetModel.getName(),
                assetModel.getStockUnitLabel(),
                total,
                assetModel.getLowStockThreshold(),
                isLow);
    }

    private ConsumableStockView applySingleBalanceMovement(
            BigContainersPrincipal principal,
            AssetModel assetModel,
            UUID containerAssetId,
            BigDecimal delta,
            StockMovementReason reason,
            String note,
            UUID eventReferenceId,
            UUID auditReferenceId,
            String activityAction) {
        var now = clock.instant();
        BalanceState result = ledgerRepository
                .applyDeltaToContainerBalance(
                        principal.organizationId(), assetModel.getId(), containerAssetId, delta, now)
                .orElseThrow(() -> new InsufficientStockException(
                        "Insufficient stock: this operation would take the balance below zero."));

        recordMovement(
                principal,
                result.balanceId(),
                delta,
                result.quantity(),
                assetModel.getStockUnitLabel(),
                reason,
                note,
                null,
                eventReferenceId,
                auditReferenceId,
                now);

        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                activityAction,
                "ASSET_MODEL",
                assetModel.getId(),
                Map.of("containerAssetId", containerAssetId.toString(), "delta", delta.toPlainString()));

        Asset containerAsset = assetRepository
                .findByIdAndOrganizationId(containerAssetId, principal.organizationId())
                .orElseThrow(() -> new IllegalStateException("Container asset disappeared mid-transaction."));
        return new ConsumableStockView(
                result.balanceId(),
                assetModel.getId(),
                assetModel.getName(),
                assetModel.getStockUnitLabel(),
                containerAssetId,
                containerDisplayName(containerAsset),
                result.quantity(),
                result.createdAt(),
                result.updatedAt());
    }

    private void recordMovement(
            BigContainersPrincipal principal,
            UUID balanceId,
            BigDecimal delta,
            BigDecimal resultingQuantity,
            String stockUnitLabel,
            StockMovementReason reason,
            String note,
            UUID transferGroupId,
            UUID eventReferenceId,
            UUID auditReferenceId,
            Instant now) {
        stockMovementRepository.save(new StockMovement(
                UUID.randomUUID(),
                principal.organizationId(),
                balanceId,
                delta,
                resultingQuantity,
                stockUnitLabel,
                reason,
                principal.userId(),
                note,
                transferGroupId,
                eventReferenceId,
                auditReferenceId,
                now));
    }

    private Asset requireContainerAsset(UUID organizationId, UUID containerAssetId) {
        Asset asset = assetRepository
                .findByIdAndOrganizationId(containerAssetId, organizationId)
                .orElseThrow(() -> new NotFoundException("Container asset not found."));
        AssetModel containerModel = assetModelRepository
                .findByIdAndOrganizationId(asset.getAssetModelId(), organizationId)
                .orElseThrow(() -> new NotFoundException("Container asset not found."));
        if (!containerModel.isCanContainAssets()) {
            throw new ValidationFailedException(
                    "This asset's model is not container-capable and cannot hold consumable stock.");
        }
        return asset;
    }

    private String containerDisplayName(Asset asset) {
        if (asset.getIndividualName() != null) {
            return asset.getIndividualName();
        }
        return assetModelRepository
                .findByIdAndOrganizationId(asset.getAssetModelId(), asset.getOrganizationId())
                .map(model -> model.getName() + " " + asset.getUnitNumber())
                .orElse("Unit " + asset.getUnitNumber());
    }

    private ConsumableStockView toView(ConsumableStock balance, AssetModel assetModel) {
        Asset containerAsset = assetRepository
                .findByIdAndOrganizationId(balance.getContainerAssetId(), balance.getOrganizationId())
                .orElseThrow(() -> new IllegalStateException("Balance references a missing container asset."));
        return new ConsumableStockView(
                balance.getId(),
                balance.getAssetModelId(),
                assetModel.getName(),
                assetModel.getStockUnitLabel(),
                balance.getContainerAssetId(),
                containerDisplayName(containerAsset),
                balance.getQuantity(),
                balance.getCreatedAt(),
                balance.getUpdatedAt());
    }

    private ConsumableStock requireBalance(UUID organizationId, UUID balanceId) {
        return consumableStockRepository
                .findByIdAndOrganizationId(balanceId, organizationId)
                .orElseThrow(() -> new NotFoundException("Consumable stock balance not found."));
    }

    private static BigDecimal requirePositiveQuantity(BigDecimal quantity) {
        BigDecimal validated = requireNonZeroValidScale(quantity);
        if (validated.signum() <= 0) {
            throw new ValidationFailedException("quantity must be positive.");
        }
        return validated;
    }

    private static BigDecimal requireNonZeroValidScale(BigDecimal value) {
        if (value == null) {
            throw new ValidationFailedException("A quantity is required.");
        }
        if (value.signum() == 0) {
            throw new ValidationFailedException("A quantity change must not be zero.");
        }
        if (value.stripTrailingZeros().scale() > MAX_QUANTITY_SCALE) {
            throw new ValidationFailedException("Quantity precision supports at most three decimal places.");
        }
        return value;
    }

    private void requireOwnerOrDeputy(BigContainersPrincipal principal) {
        if (principal == null
                || (principal.role() != OrganizationRole.OWNER && principal.role() != OrganizationRole.DEPUTY)) {
            throw new AccessDeniedException("Owner or Deputy role required.");
        }
    }

    private void requireAuthenticated(BigContainersPrincipal principal) {
        if (principal == null) {
            throw new AccessDeniedException("Authentication required.");
        }
    }
}
