package uni.notification.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import uni.notification.entity.ProcessedEvent;

import java.time.LocalDateTime;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

	@Modifying
	@Query(value = "INSERT INTO processed_events (event_id, processed_at) VALUES (:eventId, :processedAt) ON CONFLICT (event_id) DO NOTHING", nativeQuery = true)
	int claim(String eventId, LocalDateTime processedAt);

	@Modifying
	@Query("DELETE FROM ProcessedEvent p WHERE p.processedAt < :cutoff")
	int deleteOlderThan(LocalDateTime cutoff);
}
