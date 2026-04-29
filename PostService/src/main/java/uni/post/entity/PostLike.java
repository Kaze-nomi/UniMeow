package uni.post.entity;

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

import java.io.Serializable;

@Entity
@Table(name = "post_likes")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@IdClass(PostLike.PostLikeId.class)
public class PostLike {

	@Id
	private UUID postId;

	@Id
	private UUID userId;

	@Column(nullable = false)
	private LocalDateTime createdAt;

	@Getter
	@Setter
	public static class PostLikeId implements Serializable {
		private UUID postId;
		private UUID userId;
	}
}
