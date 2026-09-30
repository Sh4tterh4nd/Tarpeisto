package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.TemporaryAccessInvitation;

public record TemporaryInvitationResponse(
        TemporaryAccessInvitation invitation, String token, String joinUrl, String qrCodeDataUrl) {}
