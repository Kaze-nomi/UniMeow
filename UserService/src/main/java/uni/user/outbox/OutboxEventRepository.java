package uni.user.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
	@Query(value = "select pg_try_advisory_xact_lock(hashtext('user-outbox'))", nativeQuery = true)
	boolean tryLockPublisher();

	boolean existsByPublishedAtIsNull();

	List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByCreatedAtAscIdAsc();
}
