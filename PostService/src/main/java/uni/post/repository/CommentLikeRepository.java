package uni.post.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import uni.post.entity.CommentLike;

import java.util.UUID;

public interface CommentLikeRepository extends JpaRepository<CommentLike, CommentLike.CommentLikeId> {

	boolean existsByCommentIdAndUserId(UUID commentId, UUID userId);

	@Modifying
	void deleteByCommentIdAndUserId(UUID commentId, UUID userId);
}
