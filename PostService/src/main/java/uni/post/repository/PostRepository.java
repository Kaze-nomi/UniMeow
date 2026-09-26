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
