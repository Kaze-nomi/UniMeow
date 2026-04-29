package uni.user.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uni.grpc.user.*;
import uni.user.entity.User;
import uni.user.exception.*;
import uni.user.service.SessionService;
import uni.user.service.UserService;
import uni.user.service.VerificationService;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserGrpcServerTest {

	@Mock
	UserService userService;
	@Mock
	SessionService sessionService;
	@Mock
	VerificationService verificationService;

	@InjectMocks
	UserGrpcServer server;

	private static final UUID USER_ID = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479");

	@Test
	void get_user_by_id_returns_user_response_on_success() {
		User user = buildUser();
		when(userService.getById(USER_ID)).thenReturn(user);

		StreamObserver<UserResponse> obs = mock();
		server.getUserById(GetUserByIdRequest.newBuilder().setId(USER_ID.toString()).build(), obs);

		ArgumentCaptor<UserResponse> captor = ArgumentCaptor.forClass(UserResponse.class);
		verify(obs).onNext(captor.capture());
		verify(obs).onCompleted();
		verify(obs, never()).onError(any());

		assertThat(captor.getValue().getId()).isEqualTo(USER_ID.toString());
		assertThat(captor.getValue().getName()).isEqualTo("Ivan");
	}

	@Test
	void get_user_by_id_returns_not_found_status_when_user_missing() {
		when(userService.getById(USER_ID)).thenThrow(new UserNotFoundException("User not found"));

		StreamObserver<UserResponse> obs = mock();
		server.getUserById(GetUserByIdRequest.newBuilder().setId(USER_ID.toString()).build(), obs);

		ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
		verify(obs).onError(captor.capture());
		verify(obs, never()).onCompleted();

		assertThat(((StatusRuntimeException) captor.getValue()).getStatus().getCode())
				.isEqualTo(Status.NOT_FOUND.getCode());
	}

	@Test
	void get_user_by_id_returns_invalid_argument_on_bad_uuid() {
		StreamObserver<UserResponse> obs = mock();
		server.getUserById(GetUserByIdRequest.newBuilder().setId("not-a-uuid").build(), obs);

		ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
		verify(obs).onError(captor.capture());

		assertThat(((StatusRuntimeException) captor.getValue()).getStatus().getCode())
				.isEqualTo(Status.INVALID_ARGUMENT.getCode());
	}

	@Test
	void update_user_returns_already_exists_when_username_taken() {
		when(userService.update(any(), anyBoolean(), anyString(), anyBoolean(), anyString(), anyBoolean(), anyString(),
				anyBoolean(), anyString(), anyBoolean(), anyString(), anyBoolean(), anyString(), anyBoolean(), any(),
				anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), anyString(), anyBoolean(),
				nullable(String.class), anyBoolean(), nullable(Long.class)))
				.thenThrow(new UsernameAlreadyTakenException("taken"));

		StreamObserver<UserResponse> obs = mock();
		server.updateUser(UpdateUserRequest.newBuilder().setId(USER_ID.toString()).setUsername("taken_name").build(),
				obs);

		ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
		verify(obs).onError(captor.capture());

		assertThat(((StatusRuntimeException) captor.getValue()).getStatus().getCode())
				.isEqualTo(Status.ALREADY_EXISTS.getCode());
	}

	@Test
	void update_user_returns_invalid_argument_on_blank_username() {
		when(userService.update(any(), anyBoolean(), anyString(), anyBoolean(), anyString(), anyBoolean(), anyString(),
				anyBoolean(), anyString(), anyBoolean(), anyString(), anyBoolean(), anyString(), anyBoolean(), any(),
				anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), anyString(), anyBoolean(),
				nullable(String.class), anyBoolean(), nullable(Long.class)))
				.thenThrow(new IllegalArgumentException("Username cannot be empty"));

		StreamObserver<UserResponse> obs = mock();
		server.updateUser(UpdateUserRequest.newBuilder().setId(USER_ID.toString()).setUsername("").build(), obs);

		ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
		verify(obs).onError(captor.capture());

		assertThat(((StatusRuntimeException) captor.getValue()).getStatus().getCode())
				.isEqualTo(Status.INVALID_ARGUMENT.getCode());
	}

	@Test
	void create_session_returns_success_true() {
		doNothing().when(sessionService).createSession(any(UUID.class), anyString(), anyLong());

		StreamObserver<CreateSessionResponse> obs = mock();
		server.createSession(CreateSessionRequest.newBuilder().setUserId(USER_ID.toString()).setRefreshToken("rt")
				.setExpiresInDays(30).build(), obs);

		ArgumentCaptor<CreateSessionResponse> captor = ArgumentCaptor.forClass(CreateSessionResponse.class);
		verify(obs).onNext(captor.capture());
		verify(obs).onCompleted();
		assertThat(captor.getValue().getSuccess()).isTrue();
	}

	@Test
	void refresh_session_returns_new_token_and_user_id() {
		when(sessionService.rotateRefreshToken("old-rt")).thenReturn(new String[]{USER_ID.toString(), "new-rt"});

		StreamObserver<RefreshSessionResponse> obs = mock();
		server.refreshSession(RefreshSessionRequest.newBuilder().setOldRefreshToken("old-rt").build(), obs);

		ArgumentCaptor<RefreshSessionResponse> captor = ArgumentCaptor.forClass(RefreshSessionResponse.class);
		verify(obs).onNext(captor.capture());
		verify(obs).onCompleted();

		assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID.toString());
		assertThat(captor.getValue().getNewRefreshToken()).isEqualTo("new-rt");
	}

	@Test
	void refresh_session_returns_unauthenticated_when_expired() {
		when(sessionService.rotateRefreshToken("expired-rt")).thenThrow(new SessionExpiredException("expired"));

		StreamObserver<RefreshSessionResponse> obs = mock();
		server.refreshSession(RefreshSessionRequest.newBuilder().setOldRefreshToken("expired-rt").build(), obs);

		ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
		verify(obs).onError(captor.capture());

		assertThat(((StatusRuntimeException) captor.getValue()).getStatus().getCode())
				.isEqualTo(Status.UNAUTHENTICATED.getCode());
	}

	@Test
	void revoke_refresh_token_returns_success_true() {
		doNothing().when(sessionService).revokeToken("rt");

		StreamObserver<RevokeRefreshTokenResponse> obs = mock();
		server.revokeRefreshToken(RevokeRefreshTokenRequest.newBuilder().setRefreshToken("rt").build(), obs);

		ArgumentCaptor<RevokeRefreshTokenResponse> captor = ArgumentCaptor.forClass(RevokeRefreshTokenResponse.class);
		verify(obs).onNext(captor.capture());
		verify(obs).onCompleted();
		assertThat(captor.getValue().getSuccess()).isTrue();
	}

	@Test
	void to_proto_replaces_null_optional_fields_with_empty_strings() {
		User user = User.builder().id(USER_ID).emailGoogle("u@gmail.com").name("Ivan").username(null).surname(null)
				.patronymic(null).emailUniversity(null).avatarUrl(null).status(null).isStudentVerified(false)
				.isEmployeeVerified(false).createdAt(LocalDateTime.now()).build();

		when(userService.getById(USER_ID)).thenReturn(user);

		StreamObserver<UserResponse> obs = mock();
		server.getUserById(GetUserByIdRequest.newBuilder().setId(USER_ID.toString()).build(), obs);

		ArgumentCaptor<UserResponse> captor = ArgumentCaptor.forClass(UserResponse.class);
		verify(obs).onNext(captor.capture());

		UserResponse proto = captor.getValue();
		assertThat(proto.getUsername()).isEmpty();
		assertThat(proto.getSurname()).isEmpty();
		assertThat(proto.getPatronymic()).isEmpty();
		assertThat(proto.getEmailUniversity()).isEmpty();
		assertThat(proto.getAvatarUrl()).isEmpty();
		assertThat(proto.getStatus()).isEmpty();
	}

	private User buildUser() {
		return User.builder().id(USER_ID).emailGoogle("ivan@gmail.com").name("Ivan").surname("Ivanov")
				.username("iivanov").isStudentVerified(false).isEmployeeVerified(false).createdAt(LocalDateTime.now())
				.build();
	}
}
