package uni.post.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import uni.post.entity.CommentLike;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CommentLikeRepository extends JpaRepository<CommentLike, CommentLike.CommentLikeId> {

	boolean existsByCommentIdAndUserId(UUID commentId, UUID userId);

	@Modifying
	void deleteByCommentIdAndUserId(UUID commentId, UUID userId);

	List<CommentLike> findByUserId(UUID userId);

	@Modifying
	int deleteByUserId(UUID userId);

	@Modifying
	int deleteByCommentIdIn(Collection<UUID> commentIds);
}
