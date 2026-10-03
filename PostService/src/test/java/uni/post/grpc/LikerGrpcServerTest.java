package uni.post.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import uni.grpc.post.GetCommentLikersRequest;
import uni.grpc.post.GetPostLikersRequest;
import uni.grpc.post.LikerListResponse;
import uni.post.exception.CommentNotFoundException;
import uni.post.exception.PostNotFoundException;
import uni.post.service.CommentService;
import uni.post.service.PostService;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LikerGrpcServerTest {

	@Mock
	PostService postService;

	@Mock
	CommentService commentService;

	@InjectMocks
	PostGrpcServer server;

	private static final UUID TARGET_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
	private static final UUID FIRST_USER = UUID.fromString("550e8400-e29b-41d4-a716-446655440003");
	private static final UUID SECOND_USER = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");

	@Test
	void post_likers_preserve_order_and_total() {
		when(postService.getPostLikers(TARGET_ID, 1, 2))
				.thenReturn(new PageImpl<>(List.of(FIRST_USER, SECOND_USER), PageRequest.of(1, 2), 5));
		StreamObserver<LikerListResponse> observer = mock();

		server.getPostLikers(
				GetPostLikersRequest.newBuilder().setPostId(TARGET_ID.toString()).setPage(1).setSize(2).build(),
				observer);

		ArgumentCaptor<LikerListResponse> response = ArgumentCaptor.forClass(LikerListResponse.class);
		verify(observer).onNext(response.capture());
		assertThat(response.getValue().getUserIdsList()).containsExactly(FIRST_USER.toString(), SECOND_USER.toString());
		assertThat(response.getValue().getTotal()).isEqualTo(5);
		verify(observer).onCompleted();
	}

	@Test
	void comment_likers_preserve_order_and_total() {
		when(commentService.getCommentLikers(TARGET_ID, 1, 2))
				.thenReturn(new PageImpl<>(List.of(FIRST_USER, SECOND_USER), PageRequest.of(1, 2), 5));
		StreamObserver<LikerListResponse> observer = mock();

		server.getCommentLikers(
				GetCommentLikersRequest.newBuilder().setCommentId(TARGET_ID.toString()).setPage(1).setSize(2).build(),
				observer);

		ArgumentCaptor<LikerListResponse> response = ArgumentCaptor.forClass(LikerListResponse.class);
		verify(observer).onNext(response.capture());
		assertThat(response.getValue().getUserIdsList()).containsExactly(FIRST_USER.toString(), SECOND_USER.toString());
		assertThat(response.getValue().getTotal()).isEqualTo(5);
		verify(observer).onCompleted();
	}

	@Test
	void missing_targets_return_not_found() {
		when(postService.getPostLikers(TARGET_ID, 0, 20)).thenThrow(new PostNotFoundException("Missing post"));
		when(commentService.getCommentLikers(TARGET_ID, 0, 20))
				.thenThrow(new CommentNotFoundException("Missing comment"));
		StreamObserver<LikerListResponse> postObserver = mock();
		StreamObserver<LikerListResponse> commentObserver = mock();

		server.getPostLikers(GetPostLikersRequest.newBuilder().setPostId(TARGET_ID.toString()).setSize(20).build(),
				postObserver);
		server.getCommentLikers(
				GetCommentLikersRequest.newBuilder().setCommentId(TARGET_ID.toString()).setSize(20).build(),
				commentObserver);

		ArgumentCaptor<Throwable> errors = ArgumentCaptor.forClass(Throwable.class);
		verify(postObserver).onError(errors.capture());
		verify(commentObserver).onError(errors.capture());
		assertThat(errors.getAllValues()).allSatisfy(
				error -> assertThat(Status.fromThrowable(error).getCode()).isEqualTo(Status.Code.NOT_FOUND));
		verify(postObserver, never()).onNext(any());
		verify(commentObserver, never()).onNext(any());
	}

	@Test
	void malformed_target_ids_return_invalid_argument() {
		StreamObserver<LikerListResponse> postObserver = mock();
		StreamObserver<LikerListResponse> commentObserver = mock();

		server.getPostLikers(GetPostLikersRequest.newBuilder().setPostId("invalid").build(), postObserver);
		server.getCommentLikers(GetCommentLikersRequest.newBuilder().setCommentId("invalid").build(), commentObserver);

		ArgumentCaptor<Throwable> errors = ArgumentCaptor.forClass(Throwable.class);
		verify(postObserver).onError(errors.capture());
		verify(commentObserver).onError(errors.capture());
		assertThat(errors.getAllValues()).allSatisfy(
				error -> assertThat(Status.fromThrowable(error).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
		verifyNoInteractions(postService, commentService);
	}
}
