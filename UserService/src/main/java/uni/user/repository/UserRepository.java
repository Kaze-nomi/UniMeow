package uni.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uni.user.entity.User;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
	Optional<User> findByEmailGoogle(String emailGoogle);
	Optional<User> findByUsername(String username);
	boolean existsByUsername(String username);
	boolean existsByEmailUniversity(String emailUniversity);
}
