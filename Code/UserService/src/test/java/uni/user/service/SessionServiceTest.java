package uni.user.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uni.user.entity.RefreshSession;
import uni.user.exception.SessionExpiredException;
import uni.user.repository.RefreshSessionRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock
    RefreshSessionRepository sessionRepository;

    @InjectMocks
    SessionService sessionService;

    private static final UUID USER_ID = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479");

    @Test
    void create_session_saves_to_repository() {
        sessionService.createSession(USER_ID, "my-refresh-token", 30);

        ArgumentCaptor<RefreshSession> captor = ArgumentCaptor.forClass(RefreshSession.class);
        verify(sessionRepository).save(captor.capture());

        RefreshSession saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getRefreshToken()).isEqualTo("my-refresh-token");
        assertThat(saved.getExpiresAt()).isAfter(LocalDateTime.now().plusDays(29));
    }

    @Test
    void create_session_expiry_respects_given_days() {
        sessionService.createSession(USER_ID, "token", 7);

        ArgumentCaptor<RefreshSession> captor = ArgumentCaptor.forClass(RefreshSession.class);
        verify(sessionRepository).save(captor.capture());

        LocalDateTime expiry = captor.getValue().getExpiresAt();
        assertThat(expiry).isAfter(LocalDateTime.now().plusDays(6));
        assertThat(expiry).isBefore(LocalDateTime.now().plusDays(8));
    }

    @Test
    void rotate_returns_new_user_id_and_new_token() {
        RefreshSession session = RefreshSession.builder()
                .userId(USER_ID)
                .refreshToken("old-token")
                .expiresAt(LocalDateTime.now().plusDays(30))
                .build();

        when(sessionRepository.findByRefreshToken("old-token"))
                .thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String[] result = sessionService.rotateRefreshToken("old-token");

        assertThat(result[0]).isEqualTo(USER_ID.toString());
        assertThat(result[1]).isNotBlank().isNotEqualTo("old-token");
    }

    @Test
    void rotate_deletes_old_session() {
        RefreshSession session = RefreshSession.builder()
                .userId(USER_ID)
                .refreshToken("old-token")
                .expiresAt(LocalDateTime.now().plusDays(30))
                .build();

        when(sessionRepository.findByRefreshToken("old-token"))
                .thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sessionService.rotateRefreshToken("old-token");

        verify(sessionRepository).delete(session);
}

    @Test
    void rotate_saves_new_session_with_30_days_expiry() {
        RefreshSession session = RefreshSession.builder()
                .userId(USER_ID)
                .refreshToken("old-token")
                .expiresAt(LocalDateTime.now().plusDays(30))
                .build();

        when(sessionRepository.findByRefreshToken("old-token"))
                .thenReturn(Optional.of(session));

        ArgumentCaptor<RefreshSession> captor = ArgumentCaptor.forClass(RefreshSession.class);
        when(sessionRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        sessionService.rotateRefreshToken("old-token");

        RefreshSession newSession = captor.getValue();
        assertThat(newSession.getExpiresAt()).isAfter(LocalDateTime.now().plusDays(29));
    }

    @Test
    void rotate_throws_when_token_not_found() {
        when(sessionRepository.findByRefreshToken("unknown-token"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionService.rotateRefreshToken("unknown-token"))
                .isInstanceOf(SessionExpiredException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void rotate_throws_when_token_is_expired() {
        RefreshSession expiredSession = RefreshSession.builder()
                .userId(USER_ID)
                .refreshToken("expired-token")
                .expiresAt(LocalDateTime.now().minusDays(1))
                .build();

        when(sessionRepository.findByRefreshToken("expired-token"))
                .thenReturn(Optional.of(expiredSession));

        assertThatThrownBy(() -> sessionService.rotateRefreshToken("expired-token"))
                .isInstanceOf(SessionExpiredException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void rotate_deletes_expired_session_before_throwing() {
        RefreshSession expiredSession = RefreshSession.builder()
                .userId(USER_ID)
                .refreshToken("expired-token")
                .expiresAt(LocalDateTime.now().minusHours(1))
                .build();

        when(sessionRepository.findByRefreshToken("expired-token"))
                .thenReturn(Optional.of(expiredSession));

        assertThatThrownBy(() -> sessionService.rotateRefreshToken("expired-token"))
                .isInstanceOf(SessionExpiredException.class);

        verify(sessionRepository).delete(expiredSession);
    }

    @Test
    void revoke_token_calls_repository_delete() {
        sessionService.revokeToken("my-token");

        verify(sessionRepository).deleteByRefreshToken("my-token");
    }

    @Test
    void revoke_token_does_not_throw_when_token_not_found() {
        doNothing().when(sessionRepository).deleteByRefreshToken("ghost-token");

        assertThatCode(() -> sessionService.revokeToken("ghost-token"))
                .doesNotThrowAnyException();
    }
}