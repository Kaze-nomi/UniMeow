package uni.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import uni.user.entity.IdempotencyKey;

import java.time.LocalDateTime;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {

	@Modifying
	@Query("DELETE FROM IdempotencyKey k WHERE k.createdAt < :cutoff")
	int deleteByCreatedAtBefore(LocalDateTime cutoff);
}
