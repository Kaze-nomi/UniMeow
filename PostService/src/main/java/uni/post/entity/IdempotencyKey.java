package uni.post.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "idempotency_keys")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdempotencyKey {

	@Id
	@Column(name = "key", length = 64, nullable = false)
	private String key;

	@Column(name = "entity_id", length = 36, nullable = false)
	private String entityId;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;
}
