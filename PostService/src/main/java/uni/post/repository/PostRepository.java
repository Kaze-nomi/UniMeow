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
	@Query(value = "SELECT 1 FROM pg_advisory_xact_lock_shared(1300, 0)", nativeQuery = true)
	int lockContentMutation();

	@Query(value = "SELECT 1 FROM pg_advisory_xact_lock(1300, 0)", nativeQuery = true)
	int lockContentCleanup();

	@Query(value = "SELECT 1 FROM pg_advisory_xact_lock(1301, hashtext(CAST(:postId AS text)))", nativeQuery = true)
	int lockPost(UUID postId);

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
