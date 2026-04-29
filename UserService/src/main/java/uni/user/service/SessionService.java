package uni.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uni.user.entity.RefreshSession;
import uni.user.exception.SessionExpiredException;
import uni.user.repository.RefreshSessionRepository;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SessionService {

	private final RefreshSessionRepository sessionRepository;

	@Transactional
	public void createSession(UUID userId, String refreshToken, long expiresInDays) {
		RefreshSession session = RefreshSession.builder().userId(userId).refreshToken(refreshToken)
				.expiresAt(LocalDateTime.now().plusDays(expiresInDays)).build();
		sessionRepository.save(session);
	}

	@Transactional
	public String[] rotateRefreshToken(String oldRefreshToken) {
		RefreshSession session = sessionRepository.findByRefreshToken(oldRefreshToken)
				.orElseThrow(() -> new SessionExpiredException("Refresh token not found"));

		if (session.getExpiresAt().isBefore(LocalDateTime.now())) {
			sessionRepository.delete(session);
			throw new SessionExpiredException("Refresh token expired");
		}

		UUID userId = session.getUserId();

		sessionRepository.delete(session);

		String newToken = UUID.randomUUID().toString();
		sessionRepository.save(RefreshSession.builder().userId(userId).refreshToken(newToken)
				.expiresAt(LocalDateTime.now().plusDays(30)).build());

		return new String[]{userId.toString(), newToken};
	}

	@Transactional
	public void revokeToken(String refreshToken) {
		sessionRepository.deleteByRefreshToken(refreshToken);
	}

}
