package uni.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import uni.user.entity.User;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
	Optional<User> findByEmailGoogle(String emailGoogle);
	Optional<User> findByUsername(String username);
	boolean existsByUsername(String username);
	boolean existsByEmailUniversity(String emailUniversity);

	@Query("SELECT COUNT(u) FROM User u WHERE u.isStudentVerified = true")
	long countStudentVerified();

	@Query("SELECT COUNT(u) FROM User u WHERE u.isEmployeeVerified = true")
	long countEmployeeVerified();

	@Query("SELECT COUNT(u) FROM User u WHERE u.isAdmin = true")
	long countAdmins();

	@Query("SELECT COUNT(u) FROM User u WHERE u.university IS NOT NULL")
	long countWithUniversity();

	@Query("SELECT COUNT(u) FROM User u WHERE u.bannedUntil IS NOT NULL AND u.bannedUntil > :now")
	long countActiveBans(LocalDateTime now);
}
