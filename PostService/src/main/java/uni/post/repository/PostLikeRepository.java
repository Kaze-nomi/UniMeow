package uni.post.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import uni.post.entity.PostLike;

import java.util.Collection;
import java.util.UUID;

public interface PostLikeRepository extends JpaRepository<PostLike, PostLike.PostLikeId> {
	boolean existsByPostIdAndUserId(UUID postId, UUID userId);

	@Query(value = "SELECT l.userId FROM PostLike l WHERE l.postId = :postId ORDER BY l.createdAt DESC, l.userId", countQuery = "SELECT COUNT(l) FROM PostLike l WHERE l.postId = :postId")
	Page<UUID> findLikerIdsByPostId(UUID postId, Pageable pageable);

	@Modifying
	void deleteByPostIdAndUserId(UUID postId, UUID userId);

	@Query("SELECT l.postId FROM PostLike l WHERE l.userId = :userId")
	java.util.List<UUID> findPostIdsByUserId(UUID userId);

	@Modifying
	@Query("DELETE FROM PostLike l WHERE l.userId = :userId")
	int deleteByUserId(UUID userId);

	@Modifying
	@Query("DELETE FROM PostLike l WHERE l.postId IN :postIds")
	int deleteByPostIdIn(Collection<UUID> postIds);
}
