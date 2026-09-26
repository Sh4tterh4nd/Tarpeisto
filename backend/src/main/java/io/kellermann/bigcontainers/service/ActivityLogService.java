package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.model.ActivityLog;
import io.kellermann.bigcontainers.repository.ActivityLogRepository;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Records immutable, append-only {@link ActivityLog} entries (specification section 25).
 *
 * <p>Callers invoke {@link #record} from inside their own {@code @Transactional} method (default
 * propagation joins the caller's transaction), so the state change being recorded and its activity
 * entry commit atomically, per specification section 25: "Important state changes and their audit
 * log entry must be committed atomically."
 */
@Service
public class ActivityLogService {

    private final ActivityLogRepository activityLogRepository;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public ActivityLogService(ActivityLogRepository activityLogRepository, Clock clock, ObjectMapper objectMapper) {
        this.activityLogRepository = activityLogRepository;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void record(
            UUID organizationId,
            UUID actorUserId,
            String action,
            String targetType,
            UUID targetId,
            Map<String, Object> detail) {
        String serializedDetail = detail == null || detail.isEmpty() ? null : serialize(detail);
        activityLogRepository.save(new ActivityLog(
                UUID.randomUUID(),
                organizationId,
                actorUserId,
                action,
                targetType,
                targetId,
                serializedDetail,
                clock.instant()));
    }

    private String serialize(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Failed to serialize activity log detail", exception);
        }
    }
}
