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

	/**
	 * Also serializes the first insert, when there is no notification row to lock
	 * yet.
	 */
	@Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:groupKey, 7319))", nativeQuery = true)
	int lockBusinessGroup(@Param("groupKey") String groupKey);

	@Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CAST(:userId AS text), 7321))", nativeQuery = true)
	int lockUser(@Param("userId") UUID userId);

	@Modifying
	@Query(value = "INSERT INTO notification_deleted_users(user_id) VALUES (:userId) ON CONFLICT DO NOTHING", nativeQuery = true)
	int markUserDeleted(@Param("userId") UUID userId);

	@Query(value = "SELECT EXISTS (SELECT 1 FROM notification_deleted_users WHERE user_id = :userId)", nativeQuery = true)
	boolean isUserDeleted(@Param("userId") UUID userId);

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
