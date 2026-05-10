package uni.post.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import uni.post.entity.Comment;

import java.util.List;
import java.util.UUID;

public interface CommentRepository extends JpaRepository<Comment, UUID> {
	Page<Comment> findByPostIdOrderByCreatedAtAsc(UUID postId, Pageable pageable);

	List<Comment> findByAuthorId(UUID authorId);

	List<Comment> findByPostIdIn(java.util.Collection<UUID> postIds);

	List<Comment> findByParentCommentIdIn(java.util.Collection<UUID> parentCommentIds);

	@Modifying
	@Query("DELETE FROM Comment c WHERE c.authorId = :authorId")
	int deleteAllByAuthorId(UUID authorId);

	@Modifying
	@Query("DELETE FROM Comment c WHERE c.id IN :ids")
	int deleteAllByIds(java.util.Collection<UUID> ids);
}
