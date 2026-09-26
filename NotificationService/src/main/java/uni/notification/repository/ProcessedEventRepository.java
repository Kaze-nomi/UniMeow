package uni.notification.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import uni.notification.entity.ProcessedEvent;

import java.time.LocalDateTime;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

	boolean existsByEventId(String eventId);

	@Modifying
	@Query("DELETE FROM ProcessedEvent p WHERE p.processedAt < :cutoff")
	int deleteOlderThan(LocalDateTime cutoff);
}
