package uni.post.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import uni.post.entity.PostLike;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PostLikeRepository extends JpaRepository<PostLike, PostLike.PostLikeId> {
	boolean existsByPostIdAndUserId(UUID postId, UUID userId);

	@Modifying
	void deleteByPostIdAndUserId(UUID postId, UUID userId);

	List<PostLike> findByUserId(UUID userId);

	@Modifying
	int deleteByUserId(UUID userId);

	@Modifying
	int deleteByPostIdIn(Collection<UUID> postIds);
}
