package uni.post.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import uni.post.entity.Comment;

import java.util.UUID;

public interface CommentRepository extends JpaRepository<Comment, UUID> {
	Page<Comment> findByPostIdOrderByCreatedAtAsc(UUID postId, Pageable pageable);
}
