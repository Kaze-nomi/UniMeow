package uni.user.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter 
@Setter
public class User {

    @Id
    private UUID id;

    @Column(unique = true, nullable = false)
    private String emailGoogle;

    @Column(unique = true)
    private String username;

    @Column(nullable = false)
    private String name;

    private String surname;
    private String patronymic;

    @Column(unique = true)
    private String emailUniversity;

    private String avatarUrl;

    @Column(length = 300)
    private String status;

    @Column(nullable = false)
    private boolean isStudentVerified;

    @Column(nullable = false)
    private boolean isEmployeeVerified;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}