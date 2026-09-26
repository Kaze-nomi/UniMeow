package uni.post.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import uni.post.entity.Post;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PostRepository extends JpaRepository<Post, UUID> {
	@Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:authorId, 7320))", nativeQuery = true)
	int lockAuthorDeletion(String authorId);

	@Query(value = """
			SELECT id FROM posts WHERE author_id = :authorId
			UNION SELECT post_id FROM post_likes WHERE user_id = :authorId
			UNION SELECT post_id FROM comments WHERE author_id = :authorId
			UNION SELECT c.post_id FROM comments c JOIN comment_likes l ON c.id = l.comment_id
			WHERE l.user_id = :authorId
			""", nativeQuery = true)
	List<UUID> findContentPostIdsForRemoval(UUID authorId);

	Page<Post> findByAuthorIdOrderByCreatedAtDesc(UUID authorId, Pageable pageable);

	List<Post> findByAuthorId(UUID authorId);

	List<Post> findByIdIn(Collection<UUID> ids);

	@Modifying
	@Query("DELETE FROM Post p WHERE p.authorId = :authorId")
	int deleteAllByAuthorId(UUID authorId);

	long countByCreatedAtAfter(LocalDateTime after);

	long countByUniversityIdIsNotNull();

	@Query("SELECT COALESCE(SUM(p.likesCount), 0) FROM Post p")
	long sumLikesCount();
}
