package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.document.InvitationQrCode;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.TemporaryAccessInvitation;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.net.URI;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Generates creation-only invitation presentation after the grant transaction commits. */
@Service
public class TemporaryInvitationService {
    private final TemporaryAccessService access;
    private final InvitationQrCode qr;

    public TemporaryInvitationService(TemporaryAccessService access, InvitationQrCode qr) {
        this.access = access;
        this.qr = qr;
    }

    public ShareableInvitation create(TarpeistoPrincipal principal, UUID bookingId, UUID auditBatchId, String joinUrl) {
        URI base;
        try {
            base = URI.create(joinUrl);
        } catch (IllegalArgumentException malformed) {
            throw new ValidationFailedException("Invalid join URL.");
        }
        if (base.getHost() == null
                || base.getUserInfo() != null
                || base.getQuery() != null
                || base.getFragment() != null
                || !"/join".equals(base.getPath())
                || !("https".equals(base.getScheme())
                        || ("http".equals(base.getScheme())
                                && ("localhost".equals(base.getHost()) || "127.0.0.1".equals(base.getHost())))))
            throw new ValidationFailedException("Use the public HTTPS join URL.");
        var issued = access.create(principal, bookingId, auditBatchId);
        String url = base + "#token=" + issued.token();
        return new ShareableInvitation(issued.invitation(), issued.token(), url, qr.dataUrl(url));
    }

    public record ShareableInvitation(
            TemporaryAccessInvitation invitation, String token, String joinUrl, String qrCodeDataUrl) {}
}
