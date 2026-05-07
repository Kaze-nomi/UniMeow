package uni.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uni.user.repository.IdempotencyKeyRepository;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyKeyCleanupService {

	private final IdempotencyKeyRepository idempotencyKeyRepository;

	@Value("${app.idempotency.retention-hours:24}")
	private long retentionHours;

	@Scheduled(cron = "${app.idempotency.cleanup-cron:0 30 3 * * *}")
	@Transactional
	public void cleanup() {
		LocalDateTime cutoff = LocalDateTime.now().minusHours(retentionHours);
		int deleted = idempotencyKeyRepository.deleteByCreatedAtBefore(cutoff);
		if (deleted > 0) {
			log.info("Deleted {} idempotency keys older than {}", deleted, cutoff);
		}
	}
}
