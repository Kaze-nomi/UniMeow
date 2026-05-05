package uni.notification.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "processed_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEvent {

	@Id
	@Column(length = 100)
	private String eventId;

	@Column(nullable = false)
	private LocalDateTime processedAt;
}
