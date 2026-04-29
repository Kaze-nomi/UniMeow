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
import uni.grpc.post.*;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostGrpcClientTest {

	@Mock
	PostServiceGrpc.PostServiceBlockingStub stub;

	PostGrpcClient client;

	private static final String POST_ID = UUID.randomUUID().toString();
	private static final String AUTHOR_ID = UUID.randomUUID().toString();
	private static final String COMMENT_ID = UUID.randomUUID().toString();

	@BeforeEach
	void setUp() {
		client = new PostGrpcClient();
		ReflectionTestUtils.setField(client, "stub", stub);
	}

	private PostResponse buildPostResponse() {
		return PostResponse.newBuilder().setId(POST_ID).setAuthorId(AUTHOR_ID).setContent("Hello!").setLikesCount(0)
				.setCommentsCount(0).setLikedByMe(false).setCreatedAt("2024-01-01T00:00:00")
				.setUpdatedAt("2024-01-01T00:00:00").build();
	}

	private CommentResponse buildCommentResponse() {
		return CommentResponse.newBuilder().setId(COMMENT_ID).setPostId(POST_ID).setAuthorId(AUTHOR_ID)
				.setContent("Nice!").setCreatedAt("2024-01-01T00:00:00").setUpdatedAt("2024-01-01T00:00:00").build();
	}

	@Test
	void create_post_returns_post_response_on_success() {
		when(stub.createPost(any())).thenReturn(buildPostResponse());

		StepVerifier.create(client.createPost(AUTHOR_ID, "Hello!", List.of(), null))
				.assertNext(r -> assertThat(r.getId()).isEqualTo(POST_ID)).verifyComplete();
	}

	@Test
	void create_post_sends_correct_request() {
		when(stub.createPost(any())).thenReturn(buildPostResponse());

		ArgumentCaptor<CreatePostRequest> captor = ArgumentCaptor.forClass(CreatePostRequest.class);

		StepVerifier.create(client.createPost(AUTHOR_ID, "Hello!", List.of("url1"), null)).expectNextCount(1)
				.verifyComplete();

		verify(stub).createPost(captor.capture());
		assertThat(captor.getValue().getAuthorId()).isEqualTo(AUTHOR_ID);
		assertThat(captor.getValue().getContent()).isEqualTo("Hello!");
		assertThat(captor.getValue().getMediaUrlsList()).containsExactly("url1");
	}

	@Test
	void create_post_propagates_invalid_argument_on_blank_content() {
		when(stub.createPost(any()))
				.thenThrow(Status.INVALID_ARGUMENT.withDescription("content cannot be empty").asRuntimeException());

		StepVerifier.create(client.createPost(AUTHOR_ID, "", List.of(), null)).expectErrorSatisfies(e -> {
			assertThat(e).isInstanceOf(StatusRuntimeException.class);
			assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.INVALID_ARGUMENT.getCode());
		}).verify();
	}

	@Test
	void get_post_by_id_sends_correct_request_without_viewer() {
		when(stub.getPostById(any())).thenReturn(buildPostResponse());

		ArgumentCaptor<GetPostByIdRequest> captor = ArgumentCaptor.forClass(GetPostByIdRequest.class);

		StepVerifier.create(client.getPostById(POST_ID, null)).expectNextCount(1).verifyComplete();

		verify(stub).getPostById(captor.capture());
		assertThat(captor.getValue().getPostId()).isEqualTo(POST_ID);
		assertThat(captor.getValue().hasViewerId()).isFalse();
	}

	@Test
	void get_post_by_id_sends_viewer_id_when_provided() {
		when(stub.getPostById(any())).thenReturn(buildPostResponse());

		ArgumentCaptor<GetPostByIdRequest> captor = ArgumentCaptor.forClass(GetPostByIdRequest.class);

		StepVerifier.create(client.getPostById(POST_ID, AUTHOR_ID)).expectNextCount(1).verifyComplete();

		verify(stub).getPostById(captor.capture());
		assertThat(captor.getValue().getViewerId()).isEqualTo(AUTHOR_ID);
	}

	@Test
	void get_post_by_id_propagates_not_found() {
		when(stub.getPostById(any()))
				.thenThrow(Status.NOT_FOUND.withDescription("Post not found").asRuntimeException());

		StepVerifier.create(client.getPostById(POST_ID, null))
				.expectErrorSatisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
						.isEqualTo(Status.NOT_FOUND.getCode()))
				.verify();
	}

	@Test
	void get_posts_by_user_sends_correct_request() {
		PostListResponse response = PostListResponse.newBuilder().addPosts(buildPostResponse()).setTotal(1).build();
		when(stub.getPostsByUser(any())).thenReturn(response);

		ArgumentCaptor<GetPostsByUserRequest> captor = ArgumentCaptor.forClass(GetPostsByUserRequest.class);

		StepVerifier.create(client.getPostsByUser(AUTHOR_ID, null, 0, 20)).expectNextCount(1).verifyComplete();

		verify(stub).getPostsByUser(captor.capture());
		assertThat(captor.getValue().getAuthorId()).isEqualTo(AUTHOR_ID);
		assertThat(captor.getValue().getPage()).isZero();
		assertThat(captor.getValue().getSize()).isEqualTo(20);
	}

	@Test
	void get_posts_by_user_returns_correct_total() {
		PostListResponse response = PostListResponse.newBuilder().addPosts(buildPostResponse()).setTotal(1).build();
		when(stub.getPostsByUser(any())).thenReturn(response);

		StepVerifier.create(client.getPostsByUser(AUTHOR_ID, null, 0, 20))
				.assertNext(r -> assertThat(r.getTotal()).isEqualTo(1)).verifyComplete();
	}

	@Test
	void get_posts_by_user_propagates_grpc_error() {
		when(stub.getPostsByUser(any())).thenThrow(Status.INTERNAL.asRuntimeException());

		StepVerifier.create(client.getPostsByUser(AUTHOR_ID, null, 0, 20)).expectError(StatusRuntimeException.class)
				.verify();
	}

	@Test
	void edit_post_sends_correct_request_with_content() {
		when(stub.editPost(any())).thenReturn(buildPostResponse());

		ArgumentCaptor<EditPostRequest> captor = ArgumentCaptor.forClass(EditPostRequest.class);

		StepVerifier.create(client.editPost(POST_ID, AUTHOR_ID, "new content", false, null)).expectNextCount(1)
				.verifyComplete();

		verify(stub).editPost(captor.capture());
		assertThat(captor.getValue().getPostId()).isEqualTo(POST_ID);
		assertThat(captor.getValue().getRequesterId()).isEqualTo(AUTHOR_ID);
		assertThat(captor.getValue().getContent()).isEqualTo("new content");
		assertThat(captor.getValue().getUpdateMediaUrls()).isFalse();
	}

	@Test
	void edit_post_sends_media_urls_when_update_flag_is_true() {
		when(stub.editPost(any())).thenReturn(buildPostResponse());

		ArgumentCaptor<EditPostRequest> captor = ArgumentCaptor.forClass(EditPostRequest.class);

		StepVerifier.create(client.editPost(POST_ID, AUTHOR_ID, null, true, List.of("u1", "u2"))).expectNextCount(1)
				.verifyComplete();

		verify(stub).editPost(captor.capture());
		assertThat(captor.getValue().getUpdateMediaUrls()).isTrue();
		assertThat(captor.getValue().getMediaUrlsList()).containsExactly("u1", "u2");
	}

	@Test
	void edit_post_propagates_invalid_argument_when_requester_is_not_author() {
		when(stub.editPost(any())).thenThrow(
				Status.INVALID_ARGUMENT.withDescription("Cannot edit someone else's post").asRuntimeException());

		StepVerifier.create(client.editPost(POST_ID, "other-id", "x", false, null))
				.expectErrorSatisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
						.isEqualTo(Status.INVALID_ARGUMENT.getCode()))
				.verify();
	}

	@Test
	void delete_post_returns_true_on_success() {
		when(stub.deletePost(any())).thenReturn(DeletePostResponse.newBuilder().setSuccess(true).build());

		StepVerifier.create(client.deletePost(POST_ID, AUTHOR_ID)).assertNext(result -> assertThat(result).isTrue())
				.verifyComplete();
	}

	@Test
	void delete_post_sends_correct_request() {
		when(stub.deletePost(any())).thenReturn(DeletePostResponse.newBuilder().setSuccess(true).build());

		ArgumentCaptor<DeletePostRequest> captor = ArgumentCaptor.forClass(DeletePostRequest.class);

		StepVerifier.create(client.deletePost(POST_ID, AUTHOR_ID)).expectNextCount(1).verifyComplete();

		verify(stub).deletePost(captor.capture());
		assertThat(captor.getValue().getPostId()).isEqualTo(POST_ID);
		assertThat(captor.getValue().getRequesterId()).isEqualTo(AUTHOR_ID);
	}

	@Test
	void delete_post_propagates_not_found() {
		when(stub.deletePost(any())).thenThrow(Status.NOT_FOUND.asRuntimeException());

		StepVerifier.create(client.deletePost(POST_ID, AUTHOR_ID)).expectError(StatusRuntimeException.class).verify();
	}

	@Test
	void like_post_returns_true_on_success() {
		when(stub.likePost(any())).thenReturn(LikeResponse.newBuilder().setSuccess(true).build());

		StepVerifier.create(client.likePost(POST_ID, AUTHOR_ID)).assertNext(result -> assertThat(result).isTrue())
				.verifyComplete();
	}

	@Test
	void like_post_sends_correct_request() {
		when(stub.likePost(any())).thenReturn(LikeResponse.newBuilder().setSuccess(true).build());

		ArgumentCaptor<LikePostRequest> captor = ArgumentCaptor.forClass(LikePostRequest.class);

		StepVerifier.create(client.likePost(POST_ID, AUTHOR_ID)).expectNextCount(1).verifyComplete();

		verify(stub).likePost(captor.capture());
		assertThat(captor.getValue().getPostId()).isEqualTo(POST_ID);
		assertThat(captor.getValue().getUserId()).isEqualTo(AUTHOR_ID);
	}

	@Test
	void like_post_propagates_not_found() {
		when(stub.likePost(any())).thenThrow(Status.NOT_FOUND.asRuntimeException());

		StepVerifier.create(client.likePost(POST_ID, AUTHOR_ID)).expectError(StatusRuntimeException.class).verify();
	}

	@Test
	void unlike_post_returns_true_on_success() {
		when(stub.unlikePost(any())).thenReturn(LikeResponse.newBuilder().setSuccess(true).build());

		StepVerifier.create(client.unlikePost(POST_ID, AUTHOR_ID)).assertNext(result -> assertThat(result).isTrue())
				.verifyComplete();
	}

	@Test
	void unlike_post_propagates_grpc_error() {
		when(stub.unlikePost(any())).thenThrow(Status.INTERNAL.asRuntimeException());

		StepVerifier.create(client.unlikePost(POST_ID, AUTHOR_ID)).expectError(StatusRuntimeException.class).verify();
	}

	@Test
	void add_comment_returns_comment_response_on_success() {
		when(stub.addComment(any())).thenReturn(buildCommentResponse());

		StepVerifier.create(client.addComment(POST_ID, AUTHOR_ID, "Nice!", null)).assertNext(r -> {
			assertThat(r.getId()).isEqualTo(COMMENT_ID);
			assertThat(r.getUpdatedAt()).isNotBlank();
		}).verifyComplete();
	}

	@Test
	void add_comment_sends_correct_request() {
		when(stub.addComment(any())).thenReturn(buildCommentResponse());

		ArgumentCaptor<AddCommentRequest> captor = ArgumentCaptor.forClass(AddCommentRequest.class);

		StepVerifier.create(client.addComment(POST_ID, AUTHOR_ID, "Nice!", null)).expectNextCount(1).verifyComplete();

		verify(stub).addComment(captor.capture());
		assertThat(captor.getValue().getPostId()).isEqualTo(POST_ID);
		assertThat(captor.getValue().getAuthorId()).isEqualTo(AUTHOR_ID);
		assertThat(captor.getValue().getContent()).isEqualTo("Nice!");
		assertThat(captor.getValue().hasParentCommentId()).isFalse();
	}

	@Test
	void add_comment_sends_parent_comment_id_when_present() {
		when(stub.addComment(any())).thenReturn(buildCommentResponse());

		ArgumentCaptor<AddCommentRequest> captor = ArgumentCaptor.forClass(AddCommentRequest.class);

		StepVerifier.create(client.addComment(POST_ID, AUTHOR_ID, "Nice!", COMMENT_ID)).expectNextCount(1)
				.verifyComplete();

		verify(stub).addComment(captor.capture());
		assertThat(captor.getValue().getParentCommentId()).isEqualTo(COMMENT_ID);
	}

	@Test
	void add_comment_propagates_not_found_when_post_missing() {
		when(stub.addComment(any())).thenThrow(Status.NOT_FOUND.withDescription("Post not found").asRuntimeException());

		StepVerifier.create(client.addComment(POST_ID, AUTHOR_ID, "text", null))
				.expectErrorSatisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
						.isEqualTo(Status.NOT_FOUND.getCode()))
				.verify();
	}

	@Test
	void edit_comment_returns_updated_comment_response() {
		CommentResponse edited = buildCommentResponse().toBuilder().setContent("edited").build();
		when(stub.editComment(any())).thenReturn(edited);

		StepVerifier.create(client.editComment(COMMENT_ID, AUTHOR_ID, "edited"))
				.assertNext(r -> assertThat(r.getContent()).isEqualTo("edited")).verifyComplete();
	}

	@Test
	void edit_comment_sends_correct_request() {
		when(stub.editComment(any())).thenReturn(buildCommentResponse());

		ArgumentCaptor<EditCommentRequest> captor = ArgumentCaptor.forClass(EditCommentRequest.class);

		StepVerifier.create(client.editComment(COMMENT_ID, AUTHOR_ID, "new text")).expectNextCount(1).verifyComplete();

		verify(stub).editComment(captor.capture());
		assertThat(captor.getValue().getCommentId()).isEqualTo(COMMENT_ID);
		assertThat(captor.getValue().getRequesterId()).isEqualTo(AUTHOR_ID);
		assertThat(captor.getValue().getContent()).isEqualTo("new text");
	}

	@Test
	void edit_comment_propagates_invalid_argument_when_requester_is_not_author() {
		when(stub.editComment(any())).thenThrow(
				Status.INVALID_ARGUMENT.withDescription("Cannot edit someone else's comment").asRuntimeException());

		StepVerifier.create(client.editComment(COMMENT_ID, "other", "x"))
				.expectErrorSatisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
						.isEqualTo(Status.INVALID_ARGUMENT.getCode()))
				.verify();
	}

	@Test
	void delete_comment_returns_true_on_success() {
		when(stub.deleteComment(any())).thenReturn(DeleteCommentResponse.newBuilder().setSuccess(true).build());

		StepVerifier.create(client.deleteComment(COMMENT_ID, AUTHOR_ID))
				.assertNext(result -> assertThat(result).isTrue()).verifyComplete();
	}

	@Test
	void delete_comment_sends_correct_request() {
		when(stub.deleteComment(any())).thenReturn(DeleteCommentResponse.newBuilder().setSuccess(true).build());

		ArgumentCaptor<DeleteCommentRequest> captor = ArgumentCaptor.forClass(DeleteCommentRequest.class);

		StepVerifier.create(client.deleteComment(COMMENT_ID, AUTHOR_ID)).expectNextCount(1).verifyComplete();

		verify(stub).deleteComment(captor.capture());
		assertThat(captor.getValue().getCommentId()).isEqualTo(COMMENT_ID);
		assertThat(captor.getValue().getRequesterId()).isEqualTo(AUTHOR_ID);
	}

	@Test
	void delete_comment_propagates_not_found() {
		when(stub.deleteComment(any())).thenThrow(Status.NOT_FOUND.asRuntimeException());

		StepVerifier.create(client.deleteComment(COMMENT_ID, AUTHOR_ID)).expectError(StatusRuntimeException.class)
				.verify();
	}
}
