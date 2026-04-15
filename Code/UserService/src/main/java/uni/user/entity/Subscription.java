package uni.user.entity;

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
@Table(name = "subscriptions")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter 
@Setter
@IdClass(Subscription.SubscriptionId.class)
public class Subscription {

    @Id
    private UUID subscriberId;

    @Id
    private UUID targetUserId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Getter 
    @Setter
    public static class SubscriptionId implements Serializable {
        private UUID subscriberId;
        private UUID targetUserId;
    }
}