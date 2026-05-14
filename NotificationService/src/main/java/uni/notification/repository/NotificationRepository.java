package uni.notification.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uni.notification.entity.Notification;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

	Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

	long countByUserIdAndIsReadFalse(UUID userId);

	long countByIsReadFalse();

	@Modifying
	@Query("UPDATE Notification n SET n.isRead = true WHERE n.userId = :userId AND n.isRead = false")
	int markAllReadByUserId(UUID userId);

	@Modifying
	@Query("DELETE FROM Notification n WHERE n.createdAt < :cutoff")
	int deleteOlderThan(LocalDateTime cutoff);

	@Modifying
	@Query("""
			DELETE FROM Notification n
			WHERE n.userId = :userId
			   OR n.actorId = :userId
			   OR (n.entityType = 'USER' AND n.entityId = :userIdText)
			""")
	int deleteAllForUser(@Param("userId") UUID userId, @Param("userIdText") String userIdText);

	Optional<Notification> findFirstByUserIdAndActorIdAndTypeAndEntityIdAndCreatedAtAfterOrderByCreatedAtDesc(
			UUID userId, UUID actorId, String type, String entityId, LocalDateTime after);
}
