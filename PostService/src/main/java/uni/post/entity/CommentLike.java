package uni.post.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "comment_likes")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@IdClass(CommentLike.CommentLikeId.class)
public class CommentLike {

	@Id
	private UUID commentId;

	@Id
	private UUID userId;

	@Column(nullable = false)
	private LocalDateTime createdAt;

	@Getter
	@Setter
	public static class CommentLikeId implements Serializable {
		private UUID commentId;
		private UUID userId;
	}
}
