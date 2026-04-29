package uni.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import uni.user.entity.RefreshSession;

import java.util.Optional;
import java.util.UUID;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, Long> {
	Optional<RefreshSession> findByRefreshToken(String token);

	@Modifying
	void deleteByRefreshToken(String token);

	@Modifying
	void deleteAllByUserId(UUID userId);
}
