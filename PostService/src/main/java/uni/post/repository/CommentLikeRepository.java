package uni.post.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import uni.post.entity.CommentLike;

import java.util.Collection;
import java.util.UUID;

public interface CommentLikeRepository extends JpaRepository<CommentLike, CommentLike.CommentLikeId> {

	boolean existsByCommentIdAndUserId(UUID commentId, UUID userId);

	@Modifying
	void deleteByCommentIdAndUserId(UUID commentId, UUID userId);

	@Query("SELECT l.commentId FROM CommentLike l WHERE l.userId = :userId")
	java.util.List<UUID> findCommentIdsByUserId(UUID userId);

	@Modifying
	@Query("DELETE FROM CommentLike l WHERE l.userId = :userId")
	int deleteByUserId(UUID userId);

	@Modifying
	@Query("DELETE FROM CommentLike l WHERE l.commentId IN :commentIds")
	int deleteByCommentIdIn(Collection<UUID> commentIds);
}
