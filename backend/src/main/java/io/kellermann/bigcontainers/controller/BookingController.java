package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.security.BigContainersPrincipal;
import io.kellermann.bigcontainers.service.BookingHistoryService;
import io.kellermann.bigcontainers.service.BookingReservationService;
import io.kellermann.bigcontainers.service.BookingService;
import io.kellermann.bigcontainers.service.CheckoutService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {
    private final BookingService bookings;
    private final BookingHistoryService history;
    private final BookingReservationService reservations;
    private final CheckoutService checkout;

    public BookingController(
            BookingService bookings,
            BookingReservationService reservations,
            BookingHistoryService history,
            CheckoutService checkout) {
        this.bookings = bookings;
        this.history = history;
        this.reservations = reservations;
        this.checkout = checkout;
    }

    @GetMapping
    public List<BookingResponse> list(
            @AuthenticationPrincipal BigContainersPrincipal principal,
            @RequestParam(defaultValue = "2000-01-01T00:00:00Z") Instant from,
            @RequestParam(defaultValue = "2100-01-01T00:00:00Z") Instant until,
            @RequestParam(required = false) UUID cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return bookings.list(principal, from, until, cursor, limit).stream()
                .map(BookingResponse::from)
                .toList();
    }

    @GetMapping("/{bookingId}")
    public BookingResponse get(
            @AuthenticationPrincipal BigContainersPrincipal principal, @PathVariable UUID bookingId) {
        return BookingResponse.from(bookings.get(principal, bookingId));
    }

    @GetMapping("/{bookingId}/history")
    public List<BookingHistoryResponse> history(
            @AuthenticationPrincipal BigContainersPrincipal p,
            @PathVariable UUID bookingId,
            @RequestParam(required = false) UUID cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return history.list(p, bookingId, cursor, limit).stream()
                .map(BookingHistoryResponse::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<BookingResponse> create(
            @AuthenticationPrincipal BigContainersPrincipal principal,
            @Valid @RequestBody CreateBookingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BookingResponse.from(bookings.create(
                        principal,
                        request.mutationId(),
                        request.name(),
                        request.clientText(),
                        request.venueText(),
                        request.notes(),
                        request.startsAt(),
                        request.endsAt())));
    }

    @PutMapping("/{bookingId}")
    public BookingResponse update(
            @AuthenticationPrincipal BigContainersPrincipal principal,
            @PathVariable UUID bookingId,
            @Valid @RequestBody UpdateBookingRequest request) {
        return BookingResponse.from(bookings.update(
                principal,
                bookingId,
                request.expectedVersion(),
                request.booking().name(),
                request.booking().clientText(),
                request.booking().venueText(),
                request.booking().notes(),
                request.booking().startsAt(),
                request.booking().endsAt()));
    }

    @PostMapping("/{bookingId}/lines")
    public BookingResponse addLine(
            @AuthenticationPrincipal BigContainersPrincipal principal,
            @PathVariable UUID bookingId,
            @Valid @RequestBody CreateBookingLineRequest request) {
        return BookingResponse.from(bookings.addLine(
                principal,
                bookingId,
                request.expectedBookingVersion(),
                request.type(),
                request.assetId(),
                request.consumableStockId(),
                request.quantity()));
    }

    @DeleteMapping("/{bookingId}/lines/{lineId}")
    public BookingResponse removeLine(
            @AuthenticationPrincipal BigContainersPrincipal principal,
            @PathVariable UUID bookingId,
            @PathVariable UUID lineId,
            @Valid @RequestBody RemoveBookingLineRequest request) {
        return BookingResponse.from(bookings.removeLine(principal, bookingId, lineId, request.expectedVersion()));
    }

    @PostMapping("/{bookingId}/reservation-preview")
    public BookingReservationPreviewResponse preview(
            @AuthenticationPrincipal BigContainersPrincipal principal, @PathVariable UUID bookingId) {
        return BookingReservationPreviewResponse.from(reservations.preview(principal, bookingId));
    }

    @PostMapping("/{bookingId}/reserve")
    public BookingReservationPreviewResponse reserve(
            @AuthenticationPrincipal BigContainersPrincipal principal,
            @PathVariable UUID bookingId,
            @Valid @RequestBody ReserveBookingRequest request) {
        return BookingReservationPreviewResponse.from(
                reservations.reserve(principal, bookingId, request.expectedVersion()));
    }

    @PostMapping("/{bookingId}/cancel")
    public BookingResponse cancel(
            @AuthenticationPrincipal BigContainersPrincipal principal,
            @PathVariable UUID bookingId,
            @Valid @RequestBody CancelBookingRequest request) {
        reservations.cancel(principal, bookingId, request.expectedVersion());
        return BookingResponse.from(bookings.get(principal, bookingId));
    }

    @GetMapping("/{bookingId}/checkout-manifest")
    public CheckoutManifestResponse checkoutManifest(
            @AuthenticationPrincipal BigContainersPrincipal principal, @PathVariable UUID bookingId) {
        return CheckoutManifestResponse.from(checkout.get(principal, bookingId));
    }

    @PostMapping("/{bookingId}/checkout")
    public CheckoutManifestResponse checkout(
            @AuthenticationPrincipal BigContainersPrincipal principal,
            @PathVariable UUID bookingId,
            @Valid @RequestBody CheckoutBookingRequest request) {
        return CheckoutManifestResponse.from(checkout.checkout(
                principal,
                bookingId,
                request.expectedVersion(),
                request.mutationId(),
                request.overrideReason(),
                request.selectedAssetIds()));
    }

    @GetMapping(value = "/{bookingId}/checkout-manifest.pdf", produces = "application/pdf")
    public ResponseEntity<byte[]> checkoutPdf(
            @AuthenticationPrincipal BigContainersPrincipal principal, @PathVariable UUID bookingId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=checkout-manifest-" + bookingId + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(checkout.pdf(principal, bookingId));
    }

    @PostMapping("/{bookingId}/check-in/assets/{assetId}")
    public CheckoutManifestResponse checkInAsset(
            @AuthenticationPrincipal BigContainersPrincipal principal,
            @PathVariable UUID bookingId,
            @PathVariable UUID assetId,
            @Valid @RequestBody CheckInBookingAssetRequest request) {
        return CheckoutManifestResponse.from(
                checkout.checkInAsset(principal, bookingId, assetId, request.mutationId()));
    }

    @PostMapping("/{bookingId}/check-in/consumables/{manifestConsumableId}")
    public CheckoutManifestResponse returnConsumable(
            @AuthenticationPrincipal BigContainersPrincipal principal,
            @PathVariable UUID bookingId,
            @PathVariable UUID manifestConsumableId,
            @Valid @RequestBody ReturnBookingConsumableRequest request) {
        return CheckoutManifestResponse.from(checkout.returnConsumable(
                principal,
                bookingId,
                manifestConsumableId,
                request.quantity(),
                request.mutationId(),
                request.destinationContainerAssetId(),
                request.destinationLocationId()));
    }

    @PostMapping("/{bookingId}/check-in/complete")
    public CheckoutManifestResponse completeReturn(
            @AuthenticationPrincipal BigContainersPrincipal principal,
            @PathVariable UUID bookingId,
            @Valid @RequestBody CompleteBookingReturnRequest request) {
        return CheckoutManifestResponse.from(checkout.completeReturn(principal, bookingId, request.mutationId()));
    }
}
