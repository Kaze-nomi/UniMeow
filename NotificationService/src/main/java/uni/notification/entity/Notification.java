package uni.notification.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "notifications")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notification {

	@Id
	private UUID id;

	@Column(nullable = false)
	private UUID userId;

	@Column(nullable = false)
	private UUID actorId;

	@Column(nullable = false, length = 50)
	private String type;

	@Column(nullable = false, length = 36)
	private String entityId;

	@Column(nullable = false, length = 20)
	private String entityType;

	@Column(length = 36)
	private String parentEntityId;

	@Column(nullable = false)
	private boolean isRead;

	@Column(nullable = false)
	private LocalDateTime createdAt;
}
