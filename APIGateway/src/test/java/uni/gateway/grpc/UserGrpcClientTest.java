package uni.gateway.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.test.StepVerifier;
import uni.grpc.user.*;
import uni.grpc.user.FacultyListResponse;
import uni.grpc.user.ListFacultiesRequest;
import uni.grpc.user.UniversityListResponse;

import java.util.concurrent.TimeUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserGrpcClientTest {

	@Mock
	UserServiceGrpc.UserServiceBlockingStub stub;

	UserGrpcClient client;

	private static final String USER_ID = UUID.randomUUID().toString();

	@BeforeEach
	void setUp() {
		client = new UserGrpcClient();
		ReflectionTestUtils.setField(client, "stub", stub);
		ReflectionTestUtils.setField(client, "userReadDeadlineMs", 2000L);
		lenient().when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
	}

	@Test
	void create_or_get_user_returns_user_response() {
		UserResponse expected = buildUserResponse();
		when(stub.createOrGetUser(any())).thenReturn(expected);

		StepVerifier
				.create(client.createOrGetUser(CreateOrGetUserRequest.newBuilder().setEmailGoogle("ivan@gmail.com")
						.setAvatarUrl("https://img").build()))
				.assertNext(response -> assertThat(response.getId()).isEqualTo(USER_ID)).verifyComplete();
	}

	@Test
	void create_or_get_user_propagates_grpc_error() {
		when(stub.createOrGetUser(any())).thenThrow(Status.INTERNAL.withDescription("DB error").asRuntimeException());

		StepVerifier.create(client.createOrGetUser(CreateOrGetUserRequest.newBuilder().build()))
				.expectErrorSatisfies(e -> {
					assertThat(e).isInstanceOf(StatusRuntimeException.class);
					assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.INTERNAL.getCode());
				}).verify();
	}

	@Test
	void get_user_by_id_sends_correct_request() {
		when(stub.getUserById(any())).thenReturn(buildUserResponse());

		ArgumentCaptor<GetUserByIdRequest> captor = ArgumentCaptor.forClass(GetUserByIdRequest.class);

		StepVerifier.create(client.getUserById(USER_ID)).expectNextCount(1).verifyComplete();

		verify(stub).getUserById(captor.capture());
		assertThat(captor.getValue().getId()).isEqualTo(USER_ID);
	}

	@Test
	void get_user_by_id_propagates_not_found() {
		when(stub.getUserById(any()))
				.thenThrow(Status.NOT_FOUND.withDescription("User not found").asRuntimeException());

		StepVerifier.create(client.getUserById(USER_ID)).expectErrorSatisfies(e -> {
			assertThat(e).isInstanceOf(StatusRuntimeException.class);
			assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.NOT_FOUND.getCode());
		}).verify();
	}

	@Test
	void get_user_by_username_sends_correct_request() {
		when(stub.getUserByUsername(any())).thenReturn(buildUserResponse());

		ArgumentCaptor<GetUserByUsernameRequest> captor = ArgumentCaptor.forClass(GetUserByUsernameRequest.class);

		StepVerifier.create(client.getUserByUsername("iivanov")).expectNextCount(1).verifyComplete();

		verify(stub).getUserByUsername(captor.capture());
		assertThat(captor.getValue().getUsername()).isEqualTo("iivanov");
	}

	@Test
	void get_user_by_username_propagates_not_found() {
		when(stub.getUserByUsername(any())).thenThrow(Status.NOT_FOUND.asRuntimeException());

		StepVerifier.create(client.getUserByUsername("ghost"))
				.expectErrorSatisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
						.isEqualTo(Status.NOT_FOUND.getCode()))
				.verify();
	}

	@Test
	void update_user_returns_updated_response() {
		UserResponse updated = buildUserResponse().toBuilder().setName("Petr").build();
		when(stub.updateUser(any())).thenReturn(updated);

		UpdateUserRequest request = UpdateUserRequest.newBuilder().setId(USER_ID).setName("Petr").build();

		StepVerifier.create(client.updateUser(request)).assertNext(r -> assertThat(r.getName()).isEqualTo("Petr"))
				.verifyComplete();
	}

	@Test
	void update_user_propagates_already_exists_when_username_taken() {
		when(stub.updateUser(any())).thenThrow(Status.ALREADY_EXISTS.withDescription("taken").asRuntimeException());

		StepVerifier
				.create(client.updateUser(UpdateUserRequest.newBuilder().setId(USER_ID).setUsername("taken").build()))
				.expectErrorSatisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
						.isEqualTo(Status.ALREADY_EXISTS.getCode()))
				.verify();
	}

	@Test
	void create_session_sends_correct_request_and_completes() {
		when(stub.createSession(any())).thenReturn(CreateSessionResponse.newBuilder().setSuccess(true).build());

		ArgumentCaptor<CreateSessionRequest> captor = ArgumentCaptor.forClass(CreateSessionRequest.class);

		StepVerifier.create(client.createSession(USER_ID, "refresh-token-xyz")).verifyComplete();

		verify(stub).createSession(captor.capture());
		assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
		assertThat(captor.getValue().getRefreshToken()).isEqualTo("refresh-token-xyz");
		assertThat(captor.getValue().getExpiresInDays()).isEqualTo(30);
	}

	@Test
	void create_session_propagates_grpc_error() {
		when(stub.createSession(any())).thenThrow(Status.INTERNAL.asRuntimeException());

		StepVerifier.create(client.createSession(USER_ID, "token")).expectError(StatusRuntimeException.class).verify();
	}

	@Test
	void refresh_session_sends_correct_request() {
		RefreshSessionResponse grpcResponse = RefreshSessionResponse.newBuilder().setUserId(USER_ID)
				.setNewRefreshToken("new-rt").build();
		when(stub.refreshSession(any())).thenReturn(grpcResponse);

		ArgumentCaptor<RefreshSessionRequest> captor = ArgumentCaptor.forClass(RefreshSessionRequest.class);

		StepVerifier.create(client.refreshSession("old-rt")).assertNext(r -> {
			assertThat(r.getUserId()).isEqualTo(USER_ID);
			assertThat(r.getNewRefreshToken()).isEqualTo("new-rt");
		}).verifyComplete();

		verify(stub).refreshSession(captor.capture());
		assertThat(captor.getValue().getOldRefreshToken()).isEqualTo("old-rt");
	}

	@Test
	void refresh_session_propagates_unauthenticated_when_expired() {
		when(stub.refreshSession(any()))
				.thenThrow(Status.UNAUTHENTICATED.withDescription("expired").asRuntimeException());

		StepVerifier.create(client.refreshSession("expired-rt"))
				.expectErrorSatisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
						.isEqualTo(Status.UNAUTHENTICATED.getCode()))
				.verify();
	}

	@Test
	void revoke_refresh_token_returns_true_on_success() {
		when(stub.revokeRefreshToken(any()))
				.thenReturn(RevokeRefreshTokenResponse.newBuilder().setSuccess(true).build());

		StepVerifier.create(client.revokeRefreshToken("rt")).assertNext(result -> assertThat(result).isTrue())
				.verifyComplete();
	}

	@Test
	void revoke_refresh_token_sends_correct_request() {
		when(stub.revokeRefreshToken(any()))
				.thenReturn(RevokeRefreshTokenResponse.newBuilder().setSuccess(true).build());

		ArgumentCaptor<RevokeRefreshTokenRequest> captor = ArgumentCaptor.forClass(RevokeRefreshTokenRequest.class);

		StepVerifier.create(client.revokeRefreshToken("my-token")).expectNextCount(1).verifyComplete();

		verify(stub).revokeRefreshToken(captor.capture());
		assertThat(captor.getValue().getRefreshToken()).isEqualTo("my-token");
	}

	@Test
	void revoke_refresh_token_propagates_grpc_error() {
		when(stub.revokeRefreshToken(any())).thenThrow(Status.INTERNAL.asRuntimeException());

		StepVerifier.create(client.revokeRefreshToken("rt")).expectError(StatusRuntimeException.class).verify();
	}

	@Test
	void list_universities_returns_list() {
		UniversityListResponse response = UniversityListResponse.newBuilder().addUniversities(uni.grpc.user.University
				.newBuilder().setId(1L).setName("РњР“РЈ").setShortName("РњР“РЈ").setIconUrl("").build()).build();
		when(stub.listUniversities(any())).thenReturn(response);

		StepVerifier.create(client.listUniversities()).assertNext(r -> {
			assertThat(r.getUniversitiesList()).hasSize(1);
			assertThat(r.getUniversities(0).getName()).isEqualTo("РњР“РЈ");
		}).verifyComplete();
	}

	@Test
	void list_universities_propagates_grpc_error() {
		when(stub.listUniversities(any())).thenThrow(Status.INTERNAL.asRuntimeException());

		StepVerifier.create(client.listUniversities()).expectError(StatusRuntimeException.class).verify();
	}

	@Test
	void list_faculties_sends_correct_university_id() {
		FacultyListResponse response = FacultyListResponse.newBuilder()
				.addFaculties(
						uni.grpc.user.Faculty.newBuilder().setId(10L).setName("Р’РњРљ").setShortName("Р’РњРљ").build())
				.build();
		when(stub.listFaculties(any())).thenReturn(response);

		ArgumentCaptor<ListFacultiesRequest> captor = ArgumentCaptor.forClass(ListFacultiesRequest.class);

		StepVerifier.create(client.listFaculties(1L)).assertNext(r -> assertThat(r.getFacultiesList()).hasSize(1))
				.verifyComplete();

		verify(stub).listFaculties(captor.capture());
		assertThat(captor.getValue().getUniversityId()).isEqualTo(1L);
	}

	@Test
	void list_faculties_propagates_grpc_error() {
		when(stub.listFaculties(any())).thenThrow(Status.NOT_FOUND.asRuntimeException());

		StepVerifier.create(client.listFaculties(99L))
				.expectErrorSatisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
						.isEqualTo(Status.NOT_FOUND.getCode()))
				.verify();
	}

	private UserResponse buildUserResponse() {
		return UserResponse.newBuilder().setId(USER_ID).setEmailGoogle("ivan@gmail.com").setName("Ivan")
				.setSurname("Ivanov").setUsername("iivanov").setIsStudentVerified(false).setIsEmployeeVerified(false)
				.setCreatedAt("2024-01-01T00:00:00").build();
	}
}
