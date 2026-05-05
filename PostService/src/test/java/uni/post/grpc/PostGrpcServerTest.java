package uni.post.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uni.grpc.post.*;
import uni.post.entity.Comment;
import uni.post.entity.Post;
import uni.post.exception.CommentNotFoundException;
import uni.post.exception.PostNotFoundException;
import uni.post.service.CommentService;
import uni.post.service.PostService;
import uni.post.record.CommentPageResult;
import uni.post.record.CommentResult;
import uni.post.record.PostPageResult;
import uni.post.record.PostResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostGrpcServerTest {

	@Mock
	PostService postService;

	@Mock
	CommentService commentService;

	@InjectMocks
	PostGrpcServer server;

	private static final UUID POST_ID = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479");
	private static final UUID AUTHOR_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
	private static final UUID COMMENT_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");

	private Post buildPost() {
		LocalDateTime now = LocalDateTime.now();
		return Post.builder().id(POST_ID).authorId(AUTHOR_ID).content("content").mediaUrls(List.of()).likesCount(0)
				.commentsCount(0).createdAt(now).updatedAt(now).build();
	}

	private Comment buildComment() {
		LocalDateTime now = LocalDateTime.now();
		return Comment.builder().id(COMMENT_ID).postId(POST_ID).authorId(AUTHOR_ID).content("comment").createdAt(now)
				.updatedAt(now).build();
	}

	@Test
	void create_post_calls_on_next_and_on_completed_on_success() {
		when(postService.createPost(any(), any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(new PostResult(buildPost(), false));

		StreamObserver<PostResponse> obs = mock();
		server.createPost(
				CreatePostRequest.newBuilder().setAuthorId(AUTHOR_ID.toString()).setContent("content").build(), obs);

		verify(obs).onNext(any());
		verify(obs).onCompleted();
		verify(obs, never()).onError(any());
	}

	@Test
	void create_post_maps_updated_at_to_proto() {
		when(postService.createPost(any(), any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(new PostResult(buildPost(), false));

		StreamObserver<PostResponse> obs = mock();
		server.createPost(
				CreatePostRequest.newBuilder().setAuthorId(AUTHOR_ID.toString()).setContent("content").build(), obs);

		ArgumentCaptor<PostResponse> captor = ArgumentCaptor.forClass(PostResponse.class);
		verify(obs).onNext(captor.capture());
		assertThat(captor.getValue().getUpdatedAt()).isNotBlank();
	}

	@Test
	void create_post_returns_invalid_argument_on_blank_content() {
		when(postService.createPost(any(), any(), any(), any(), any(), any(), any(), any()))
				.thenThrow(new IllegalArgumentException("content cannot be empty"));

		StreamObserver<PostResponse> obs = mock();
		server.createPost(CreatePostRequest.newBuilder().setAuthorId(AUTHOR_ID.toString()).setContent("").build(), obs);

		assertGrpcStatus(obs, Status.INVALID_ARGUMENT);
	}

	@Test
	void create_post_returns_internal_on_unexpected_exception() {
		when(postService.createPost(any(), any(), any(), any(), any(), any(), any(), any()))
				.thenThrow(new RuntimeException("DB error"));

		StreamObserver<PostResponse> obs = mock();
		server.createPost(CreatePostRequest.newBuilder().setAuthorId(AUTHOR_ID.toString()).setContent("x").build(),
				obs);

		assertGrpcStatus(obs, Status.INTERNAL);
	}

	@Test
	void get_post_by_id_returns_correct_post_on_success() {
		when(postService.getById(eq(POST_ID), isNull())).thenReturn(new PostResult(buildPost(), false));

		StreamObserver<PostResponse> obs = mock();
		server.getPostById(GetPostByIdRequest.newBuilder().setPostId(POST_ID.toString()).build(), obs);

		ArgumentCaptor<PostResponse> captor = ArgumentCaptor.forClass(PostResponse.class);
		verify(obs).onNext(captor.capture());
		verify(obs).onCompleted();
		assertThat(captor.getValue().getId()).isEqualTo(POST_ID.toString());
	}

	@Test
	void get_post_by_id_passes_null_viewer_when_viewer_id_absent() {
		when(postService.getById(eq(POST_ID), isNull())).thenReturn(new PostResult(buildPost(), false));

		StreamObserver<PostResponse> obs = mock();
		server.getPostById(GetPostByIdRequest.newBuilder().setPostId(POST_ID.toString()).build(), obs);

		verify(postService).getById(POST_ID, null);
	}

	@Test
	void get_post_by_id_passes_viewer_id_when_present() {
		UUID viewerId = UUID.randomUUID();
		when(postService.getById(eq(POST_ID), eq(viewerId))).thenReturn(new PostResult(buildPost(), true));

		StreamObserver<PostResponse> obs = mock();
		server.getPostById(
				GetPostByIdRequest.newBuilder().setPostId(POST_ID.toString()).setViewerId(viewerId.toString()).build(),
				obs);

		verify(postService).getById(POST_ID, viewerId);
	}

	@Test
	void get_post_by_id_returns_not_found_when_post_missing() {
		when(postService.getById(any(), any())).thenThrow(new PostNotFoundException("not found"));

		StreamObserver<PostResponse> obs = mock();
		server.getPostById(GetPostByIdRequest.newBuilder().setPostId(POST_ID.toString()).build(), obs);

		assertGrpcStatus(obs, Status.NOT_FOUND);
	}

	@Test
	void get_post_by_id_returns_invalid_argument_on_bad_uuid() {
		StreamObserver<PostResponse> obs = mock();
		server.getPostById(GetPostByIdRequest.newBuilder().setPostId("not-a-uuid").build(), obs);

		assertGrpcStatus(obs, Status.INVALID_ARGUMENT);
	}

	@Test
	void get_posts_by_user_returns_list_with_correct_total() {
		PostPageResult pageResult = new PostPageResult(List.of(new PostResult(buildPost(), false)), 1L);
		when(postService.getByAuthor(any(), any(), anyInt(), anyInt())).thenReturn(pageResult);

		StreamObserver<PostListResponse> obs = mock();
		server.getPostsByUser(
				GetPostsByUserRequest.newBuilder().setAuthorId(AUTHOR_ID.toString()).setPage(0).setSize(20).build(),
				obs);

		ArgumentCaptor<PostListResponse> captor = ArgumentCaptor.forClass(PostListResponse.class);
		verify(obs).onNext(captor.capture());
		verify(obs).onCompleted();
		assertThat(captor.getValue().getTotal()).isEqualTo(1);
		assertThat(captor.getValue().getPostsCount()).isEqualTo(1);
	}

	@Test
	void get_posts_by_user_returns_invalid_argument_on_bad_uuid() {
		StreamObserver<PostListResponse> obs = mock();
		server.getPostsByUser(GetPostsByUserRequest.newBuilder().setAuthorId("bad-uuid").build(), obs);

		assertGrpcStatus(obs, Status.INVALID_ARGUMENT);
	}

	@Test
	void edit_post_returns_updated_post_on_success() {
		when(postService.editPost(any(), any(), anyBoolean(), any(), anyBoolean(), any()))
				.thenReturn(new PostResult(buildPost(), false));

		StreamObserver<PostResponse> obs = mock();
		server.editPost(EditPostRequest.newBuilder().setPostId(POST_ID.toString()).setRequesterId(AUTHOR_ID.toString())
				.setContent("new content").build(), obs);

		verify(obs).onNext(any());
		verify(obs).onCompleted();
		verify(obs, never()).onError(any());
	}

	@Test
	void edit_post_returns_not_found_when_post_missing() {
		when(postService.editPost(any(), any(), anyBoolean(), any(), anyBoolean(), any()))
				.thenThrow(new PostNotFoundException("not found"));

		StreamObserver<PostResponse> obs = mock();
		server.editPost(
				EditPostRequest.newBuilder().setPostId(POST_ID.toString()).setRequesterId(AUTHOR_ID.toString()).build(),
				obs);

		assertGrpcStatus(obs, Status.NOT_FOUND);
	}

	@Test
	void edit_post_returns_invalid_argument_when_requester_is_not_author() {
		when(postService.editPost(any(), any(), anyBoolean(), any(), anyBoolean(), any()))
				.thenThrow(new IllegalArgumentException("Cannot edit someone else's post"));

		StreamObserver<PostResponse> obs = mock();
		server.editPost(EditPostRequest.newBuilder().setPostId(POST_ID.toString())
				.setRequesterId(UUID.randomUUID().toString()).build(), obs);

		assertGrpcStatus(obs, Status.INVALID_ARGUMENT);
	}

	@Test
	void delete_post_returns_success_true_on_success() {
		doNothing().when(postService).deletePost(any(), any());

		StreamObserver<DeletePostResponse> obs = mock();
		server.deletePost(DeletePostRequest.newBuilder().setPostId(POST_ID.toString())
				.setRequesterId(AUTHOR_ID.toString()).build(), obs);

		ArgumentCaptor<DeletePostResponse> captor = ArgumentCaptor.forClass(DeletePostResponse.class);
		verify(obs).onNext(captor.capture());
		verify(obs).onCompleted();
		assertThat(captor.getValue().getSuccess()).isTrue();
	}

	@Test
	void delete_post_returns_not_found_when_post_missing() {
		doThrow(new PostNotFoundException("not found")).when(postService).deletePost(any(), any());

		StreamObserver<DeletePostResponse> obs = mock();
		server.deletePost(DeletePostRequest.newBuilder().setPostId(POST_ID.toString())
				.setRequesterId(AUTHOR_ID.toString()).build(), obs);

		assertGrpcStatus(obs, Status.NOT_FOUND);
	}

	@Test
	void delete_post_returns_invalid_argument_when_requester_is_not_author() {
		doThrow(new IllegalArgumentException("Cannot delete someone else's post")).when(postService).deletePost(any(),
				any());

		StreamObserver<DeletePostResponse> obs = mock();
		server.deletePost(DeletePostRequest.newBuilder().setPostId(POST_ID.toString())
				.setRequesterId(UUID.randomUUID().toString()).build(), obs);

		assertGrpcStatus(obs, Status.INVALID_ARGUMENT);
	}

	@Test
	void like_post_returns_success_true() {
		doNothing().when(postService).likePost(any(), any());

		StreamObserver<LikeResponse> obs = mock();
		server.likePost(
				LikePostRequest.newBuilder().setPostId(POST_ID.toString()).setUserId(AUTHOR_ID.toString()).build(),
				obs);

		ArgumentCaptor<LikeResponse> captor = ArgumentCaptor.forClass(LikeResponse.class);
		verify(obs).onNext(captor.capture());
		verify(obs).onCompleted();
		assertThat(captor.getValue().getSuccess()).isTrue();
	}

	@Test
	void like_post_returns_not_found_when_post_missing() {
		doThrow(new PostNotFoundException("not found")).when(postService).likePost(any(), any());

		StreamObserver<LikeResponse> obs = mock();
		server.likePost(
				LikePostRequest.newBuilder().setPostId(POST_ID.toString()).setUserId(AUTHOR_ID.toString()).build(),
				obs);

		assertGrpcStatus(obs, Status.NOT_FOUND);
	}

	@Test
	void unlike_post_returns_success_true() {
		doNothing().when(postService).unlikePost(any(), any());

		StreamObserver<LikeResponse> obs = mock();
		server.unlikePost(
				UnlikePostRequest.newBuilder().setPostId(POST_ID.toString()).setUserId(AUTHOR_ID.toString()).build(),
				obs);

		ArgumentCaptor<LikeResponse> captor = ArgumentCaptor.forClass(LikeResponse.class);
		verify(obs).onNext(captor.capture());
		assertThat(captor.getValue().getSuccess()).isTrue();
	}

	@Test
	void unlike_post_returns_not_found_when_post_missing() {
		doThrow(new PostNotFoundException("not found")).when(postService).unlikePost(any(), any());

		StreamObserver<LikeResponse> obs = mock();
		server.unlikePost(
				UnlikePostRequest.newBuilder().setPostId(POST_ID.toString()).setUserId(AUTHOR_ID.toString()).build(),
				obs);

		assertGrpcStatus(obs, Status.NOT_FOUND);
	}

	@Test
	void add_comment_returns_comment_response_on_success() {
		when(commentService.addComment(any(), any(), any(), any())).thenReturn(new CommentResult(buildComment(), true));

		StreamObserver<CommentResponse> obs = mock();
		server.addComment(AddCommentRequest.newBuilder().setPostId(POST_ID.toString()).setAuthorId(AUTHOR_ID.toString())
				.setContent("nice!").build(), obs);

		ArgumentCaptor<CommentResponse> captor = ArgumentCaptor.forClass(CommentResponse.class);
		verify(obs).onNext(captor.capture());
		verify(obs).onCompleted();
		assertThat(captor.getValue().getId()).isEqualTo(COMMENT_ID.toString());
		assertThat(captor.getValue().getUpdatedAt()).isNotBlank();
	}

	@Test
	void add_comment_returns_not_found_when_post_missing() {
		when(commentService.addComment(any(), any(), any(), any()))
				.thenThrow(new PostNotFoundException("post not found"));

		StreamObserver<CommentResponse> obs = mock();
		server.addComment(AddCommentRequest.newBuilder().setPostId(POST_ID.toString()).setAuthorId(AUTHOR_ID.toString())
				.setContent("text").build(), obs);

		assertGrpcStatus(obs, Status.NOT_FOUND);
	}

	@Test
	void add_comment_returns_invalid_argument_on_blank_content() {
		when(commentService.addComment(any(), any(), any(), any()))
				.thenThrow(new IllegalArgumentException("content cannot be empty"));

		StreamObserver<CommentResponse> obs = mock();
		server.addComment(AddCommentRequest.newBuilder().setPostId(POST_ID.toString()).setAuthorId(AUTHOR_ID.toString())
				.setContent("").build(), obs);

		assertGrpcStatus(obs, Status.INVALID_ARGUMENT);
	}

	@Test
	void get_comments_returns_comment_list_with_total() {
		when(commentService.getComments(any(), anyInt(), anyInt(), any()))
				.thenReturn(new CommentPageResult(List.of(new CommentResult(buildComment(), true)), 1L));

		StreamObserver<CommentListResponse> obs = mock();
		server.getComments(GetCommentsRequest.newBuilder().setPostId(POST_ID.toString()).setPage(0).setSize(20).build(),
				obs);

		ArgumentCaptor<CommentListResponse> captor = ArgumentCaptor.forClass(CommentListResponse.class);
		verify(obs).onNext(captor.capture());
		verify(obs).onCompleted();
		assertThat(captor.getValue().getCommentsCount()).isEqualTo(1);
		assertThat(captor.getValue().getTotal()).isEqualTo(1);
	}

	@Test
	void get_comments_returns_invalid_argument_on_bad_post_id() {
		StreamObserver<CommentListResponse> obs = mock();
		server.getComments(GetCommentsRequest.newBuilder().setPostId("bad-uuid").build(), obs);

		assertGrpcStatus(obs, Status.INVALID_ARGUMENT);
	}

	@Test
	void edit_comment_returns_updated_comment_on_success() {
		Comment edited = Comment.builder().id(COMMENT_ID).postId(POST_ID).authorId(AUTHOR_ID).content("updated")
				.createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now().plusSeconds(5)).build();
		when(commentService.editComment(any(), any(), any())).thenReturn(new CommentResult(edited, true));

		StreamObserver<CommentResponse> obs = mock();
		server.editComment(EditCommentRequest.newBuilder().setCommentId(COMMENT_ID.toString())
				.setRequesterId(AUTHOR_ID.toString()).setContent("updated").build(), obs);

		ArgumentCaptor<CommentResponse> captor = ArgumentCaptor.forClass(CommentResponse.class);
		verify(obs).onNext(captor.capture());
		verify(obs).onCompleted();
		assertThat(captor.getValue().getContent()).isEqualTo("updated");
	}

	@Test
	void edit_comment_returns_not_found_when_comment_missing() {
		when(commentService.editComment(any(), any(), any())).thenThrow(new CommentNotFoundException("not found"));

		StreamObserver<CommentResponse> obs = mock();
		server.editComment(EditCommentRequest.newBuilder().setCommentId(COMMENT_ID.toString())
				.setRequesterId(AUTHOR_ID.toString()).setContent("x").build(), obs);

		assertGrpcStatus(obs, Status.NOT_FOUND);
	}

	@Test
	void edit_comment_returns_invalid_argument_when_requester_is_not_author() {
		when(commentService.editComment(any(), any(), any()))
				.thenThrow(new IllegalArgumentException("Cannot edit someone else's comment"));

		StreamObserver<CommentResponse> obs = mock();
		server.editComment(EditCommentRequest.newBuilder().setCommentId(COMMENT_ID.toString())
				.setRequesterId(UUID.randomUUID().toString()).setContent("x").build(), obs);

		assertGrpcStatus(obs, Status.INVALID_ARGUMENT);
	}

	@Test
	void delete_comment_returns_success_true_on_success() {
		doNothing().when(commentService).deleteComment(any(), any());

		StreamObserver<DeleteCommentResponse> obs = mock();
		server.deleteComment(DeleteCommentRequest.newBuilder().setCommentId(COMMENT_ID.toString())
				.setRequesterId(AUTHOR_ID.toString()).build(), obs);

		ArgumentCaptor<DeleteCommentResponse> captor = ArgumentCaptor.forClass(DeleteCommentResponse.class);
		verify(obs).onNext(captor.capture());
		verify(obs).onCompleted();
		assertThat(captor.getValue().getSuccess()).isTrue();
	}

	@Test
	void delete_comment_returns_not_found_when_comment_missing() {
		doThrow(new CommentNotFoundException("not found")).when(commentService).deleteComment(any(), any());

		StreamObserver<DeleteCommentResponse> obs = mock();
		server.deleteComment(DeleteCommentRequest.newBuilder().setCommentId(COMMENT_ID.toString())
				.setRequesterId(AUTHOR_ID.toString()).build(), obs);

		assertGrpcStatus(obs, Status.NOT_FOUND);
	}

	@Test
	void delete_comment_returns_invalid_argument_when_requester_is_not_author() {
		doThrow(new IllegalArgumentException("Cannot delete someone else's comment")).when(commentService)
				.deleteComment(any(), any());

		StreamObserver<DeleteCommentResponse> obs = mock();
		server.deleteComment(DeleteCommentRequest.newBuilder().setCommentId(COMMENT_ID.toString())
				.setRequesterId(UUID.randomUUID().toString()).build(), obs);

		assertGrpcStatus(obs, Status.INVALID_ARGUMENT);
	}

	@Test
	void to_proto_maps_all_post_fields_including_updated_at() {
		Post post = buildPost();
		when(postService.getById(eq(POST_ID), isNull())).thenReturn(new PostResult(post, true));

		StreamObserver<PostResponse> obs = mock();
		server.getPostById(GetPostByIdRequest.newBuilder().setPostId(POST_ID.toString()).build(), obs);

		ArgumentCaptor<PostResponse> captor = ArgumentCaptor.forClass(PostResponse.class);
		verify(obs).onNext(captor.capture());

		PostResponse proto = captor.getValue();
		assertThat(proto.getId()).isEqualTo(POST_ID.toString());
		assertThat(proto.getAuthorId()).isEqualTo(AUTHOR_ID.toString());
		assertThat(proto.getContent()).isEqualTo("content");
		assertThat(proto.getCreatedAt()).isNotBlank();
		assertThat(proto.getUpdatedAt()).isNotBlank();
		assertThat(proto.getLikedByMe()).isTrue();
	}

	@Test
	void to_proto_maps_all_comment_fields_including_updated_at() {
		when(commentService.addComment(any(), any(), any(), any())).thenReturn(new CommentResult(buildComment(), true));

		StreamObserver<CommentResponse> obs = mock();
		server.addComment(AddCommentRequest.newBuilder().setPostId(POST_ID.toString()).setAuthorId(AUTHOR_ID.toString())
				.setContent("comment").build(), obs);

		ArgumentCaptor<CommentResponse> captor = ArgumentCaptor.forClass(CommentResponse.class);
		verify(obs).onNext(captor.capture());

		CommentResponse proto = captor.getValue();
		assertThat(proto.getId()).isEqualTo(COMMENT_ID.toString());
		assertThat(proto.getPostId()).isEqualTo(POST_ID.toString());
		assertThat(proto.getAuthorId()).isEqualTo(AUTHOR_ID.toString());
		assertThat(proto.getContent()).isEqualTo("comment");
		assertThat(proto.getCreatedAt()).isNotBlank();
		assertThat(proto.getUpdatedAt()).isNotBlank();
	}

	private <T> void assertGrpcStatus(StreamObserver<T> obs, Status expectedStatus) {
		ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
		verify(obs).onError(captor.capture());
		verify(obs, never()).onCompleted();

		assertThat(captor.getValue()).isInstanceOf(StatusRuntimeException.class);
		assertThat(((StatusRuntimeException) captor.getValue()).getStatus().getCode())
				.isEqualTo(expectedStatus.getCode());
	}
}
