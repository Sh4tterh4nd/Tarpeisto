package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.service.MediaService;
import io.kellermann.tarpeisto.storage.MediaStorage;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Same-origin authorized image upload and streaming boundary. */
@RestController
@RequestMapping("/api/v1")
public class MediaController {
    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @GetMapping("/asset-models/{assetModelId}/media/reference")
    public MediaResponse getModelReference(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID assetModelId) {
        return MediaResponse.from(mediaService.getModelReference(principal, assetModelId));
    }

    @PostMapping(value = "/asset-models/{assetModelId}/media/reference", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaResponse> uploadModelReference(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetModelId,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(MediaResponse.from(mediaService.uploadModelReference(principal, assetModelId, file)));
    }

    @GetMapping("/assets/{assetId}/media/reference")
    public MediaResponse getAssetReference(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID assetId) {
        return MediaResponse.from(mediaService.getAssetReference(principal, assetId));
    }

    @PostMapping(value = "/assets/{assetId}/media/reference", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaResponse> uploadAssetReference(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetId,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(MediaResponse.from(mediaService.uploadAssetReference(principal, assetId, file)));
    }

    @GetMapping("/assets/{assetId}/media/layout")
    public List<MediaResponse> listLayouts(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID assetId) {
        return mediaService.listContainerLayouts(principal, assetId).stream()
                .map(MediaResponse::from)
                .toList();
    }

    @PostMapping(value = "/assets/{assetId}/media/layout", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaResponse> uploadLayout(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID assetId,
            @RequestParam(required = false) String caption,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(MediaResponse.from(mediaService.uploadContainerLayout(principal, assetId, caption, file)));
    }

    @PutMapping("/media/{mediaId}/layout")
    public List<MediaResponse> updateLayout(
            @AuthenticationPrincipal TarpeistoPrincipal principal,
            @PathVariable UUID mediaId,
            @Valid @RequestBody UpdateLayoutMediaRequest request) {
        return mediaService
                .updateLayout(
                        principal,
                        mediaId,
                        request.caption(),
                        request.displayOrder(),
                        request.primaryImage(),
                        request.version())
                .stream()
                .map(MediaResponse::from)
                .toList();
    }

    @DeleteMapping("/media/{mediaId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID mediaId) {
        mediaService.delete(principal, mediaId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/media/{mediaId}/cleanup")
    public ResponseEntity<Void> retryCleanup(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID mediaId) {
        mediaService.retryCleanup(principal, mediaId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/media/{mediaId}")
    public ResponseEntity<StreamingResponseBody> stream(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID mediaId) {
        return stream(mediaService.open(principal, mediaId, false));
    }

    @GetMapping("/media/{mediaId}/thumbnail")
    public ResponseEntity<StreamingResponseBody> thumbnail(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID mediaId) {
        return stream(mediaService.open(principal, mediaId, true));
    }

    private static ResponseEntity<StreamingResponseBody> stream(MediaStorage.StoredMedia media) {
        StreamingResponseBody body = output -> {
            try (var inputStream = media.inputStream()) {
                inputStream.transferTo(output);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not stream image.", exception);
            }
        };
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .cacheControl(CacheControl.noCache().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(
                        media.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : media.contentType()));
        if (media.contentLength() >= 0) response.contentLength(media.contentLength());
        return response.body(body);
    }
}
