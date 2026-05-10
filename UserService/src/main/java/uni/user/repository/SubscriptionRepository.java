package uni.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import uni.user.entity.Subscription;

import java.util.List;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, Subscription.SubscriptionId> {

	boolean existsBySubscriberIdAndTargetUserId(UUID subscriberId, UUID targetUserId);

	List<Subscription> findBySubscriberIdOrderByCreatedAtDesc(UUID subscriberId);

	List<Subscription> findByTargetUserIdOrderByCreatedAtDesc(UUID targetUserId);

	@Modifying
	void deleteBySubscriberIdAndTargetUserId(UUID subscriberId, UUID targetUserId);

	@Modifying
	int deleteBySubscriberIdOrTargetUserId(UUID subscriberId, UUID targetUserId);
}
