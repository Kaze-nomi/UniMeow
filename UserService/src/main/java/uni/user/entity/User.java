package uni.user.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

	@Column(unique = true)
	private String emailUniversity;

	private String avatarUrl;
	private String coverUrl;

	@Column(length = 300)
	private String status;

	@Column(nullable = false)
	private boolean isStudentVerified;

	@Column(nullable = false)
	private boolean isEmployeeVerified;

	@ManyToOne
	@JoinColumn(name = "university_id")
	private University university;

	@ManyToOne
	@JoinColumn(name = "faculty_id")
	private UniversityFaculty faculty;

	@ManyToOne
	@JoinColumn(name = "program_id")
	private UniversityProgram program;

	private Short course;

	@Enumerated(EnumType.STRING)
	private EducationLevel educationLevel;

	private Short graduationYear;

	@Column(length = 500)
	private String bio;

	@Column(nullable = false)
	private boolean isAdmin;

	private LocalDateTime bannedUntil;

	@Column(length = 500)
	private String banReason;

	@Column(nullable = false)
	private LocalDateTime createdAt;

	public enum EducationLevel {
		BACHELOR, MASTER, PHD, SPECIALIST
	}
}
