package uni.user.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uni.grpc.user.GetUsersByIdsRequest;
import uni.grpc.user.UserListResponse;
import uni.grpc.user.UserResponse;
import uni.user.entity.User;
import uni.user.repository.UserRepository;
import uni.user.service.UserService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserBatchLookupTest {

	@Mock
	UserRepository userRepository;

	@Mock
	StreamObserver<UserListResponse> observer;

	UserGrpcServer server;

	@BeforeEach
	void setUp() {
		UserService userService = new UserService(userRepository, null, null, null, null, null, null);
		server = new UserGrpcServer(userService, null, null, null, null);
	}

	@Test
	void batch_returns_existing_users_with_profile_fields_and_omits_missing_users() {
		UUID firstId = UUID.randomUUID();
		UUID missingId = UUID.randomUUID();
		UUID secondId = UUID.randomUUID();
		List<UUID> ids = List.of(firstId, missingId, secondId);
		User first = buildUser(firstId, "first_user", "First");
		User second = buildUser(secondId, "second_user", "Second");
		when(userRepository.findAllById(ids)).thenReturn(List.of(first, second));

		server.getUsersByIds(request(ids), observer);

		ArgumentCaptor<UserListResponse> response = ArgumentCaptor.forClass(UserListResponse.class);
		verify(observer).onNext(response.capture());
		verify(observer).onCompleted();
		verify(observer, never()).onError(any());
		assertThat(response.getValue().getUsersList()).extracting(UserResponse::getId)
				.containsExactly(firstId.toString(), secondId.toString());
		assertThat(response.getValue().getUsers(0).getUsername()).isEqualTo("first_user");
		assertThat(response.getValue().getUsers(0).getName()).isEqualTo("First");
		assertThat(response.getValue().getUsers(0).getAvatarUrl()).isEqualTo("/avatar.png");
		verify(userRepository).findAllById(ids);
		verifyNoMoreInteractions(userRepository);
	}

	@Test
	void empty_batch_completes_without_a_query() {
		server.getUsersByIds(GetUsersByIdsRequest.getDefaultInstance(), observer);

		verify(observer).onNext(UserListResponse.getDefaultInstance());
		verify(observer).onCompleted();
		verify(observer, never()).onError(any());
		verifyNoInteractions(userRepository);
	}

	@Test
	void fifty_ids_are_loaded_in_one_batch() {
		List<UUID> ids = IntStream.range(0, 50).mapToObj(i -> UUID.randomUUID()).toList();
		when(userRepository.findAllById(ids)).thenReturn(List.of());

		server.getUsersByIds(request(ids), observer);

		verify(observer).onNext(UserListResponse.getDefaultInstance());
		verify(observer).onCompleted();
		verify(userRepository).findAllById(ids);
		verifyNoMoreInteractions(userRepository);
	}

	@Test
	void more_than_fifty_ids_are_rejected_without_a_query() {
		List<UUID> ids = IntStream.range(0, 51).mapToObj(i -> UUID.randomUUID()).toList();

		server.getUsersByIds(request(ids), observer);

		assertError(Status.Code.INVALID_ARGUMENT);
		verifyNoInteractions(userRepository);
	}

	@Test
	void invalid_id_rejects_the_entire_batch_without_a_query() {
		server.getUsersByIds(
				GetUsersByIdsRequest.newBuilder().addIds(UUID.randomUUID().toString()).addIds("invalid-id").build(),
				observer);

		assertError(Status.Code.INVALID_ARGUMENT);
		verifyNoInteractions(userRepository);
	}

	@Test
	void repository_failure_returns_internal_status() {
		List<UUID> ids = List.of(UUID.randomUUID());
		when(userRepository.findAllById(ids)).thenThrow(new IllegalStateException("Database unavailable"));

		server.getUsersByIds(request(ids), observer);

		assertError(Status.Code.INTERNAL);
	}

	private void assertError(Status.Code code) {
		ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
		verify(observer).onError(error.capture());
		assertThat(Status.fromThrowable(error.getValue()).getCode()).isEqualTo(code);
		verify(observer, never()).onNext(any());
		verify(observer, never()).onCompleted();
	}

	private GetUsersByIdsRequest request(List<UUID> ids) {
		return GetUsersByIdsRequest.newBuilder().addAllIds(ids.stream().map(UUID::toString).toList()).build();
	}

	private User buildUser(UUID id, String username, String name) {
		return User.builder().id(id).emailGoogle(username + "@example.com").username(username).name(name)
				.avatarUrl("/avatar.png").createdAt(LocalDateTime.of(2026, 10, 3, 12, 0)).build();
	}
}
