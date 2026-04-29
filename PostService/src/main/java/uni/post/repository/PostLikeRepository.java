package uni.post.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import uni.post.entity.PostLike;

import java.util.UUID;

public interface PostLikeRepository extends JpaRepository<PostLike, PostLike.PostLikeId> {
	boolean existsByPostIdAndUserId(UUID postId, UUID userId);

	@Modifying
	void deleteByPostIdAndUserId(UUID postId, UUID userId);
}
