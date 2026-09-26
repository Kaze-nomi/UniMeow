package uni.user.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
	List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();

	@Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CAST(:key AS text), 0))", nativeQuery = true)
	int lockMutation(String key);
}
