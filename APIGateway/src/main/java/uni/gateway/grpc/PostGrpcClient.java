package uni.gateway.grpc;

import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import uni.grpc.post.*;

import java.util.List;

@Service
public class PostGrpcClient {

	@GrpcClient("post-service")
	private PostServiceGrpc.PostServiceBlockingStub stub;

	public Mono<PostResponse> createPost(String authorId, String content, List<String> mediaUrls, Long universityId,
			Long facultyId, Long programId, Long topicId, Long parentTopicId, String clientRequestId) {
		CreatePostRequest.Builder builder = CreatePostRequest.newBuilder().setAuthorId(authorId).setContent(content)
				.addAllMediaUrls(mediaUrls == null ? List.of() : mediaUrls);
		if (universityId != null)
			builder.setUniversityId(universityId);
		if (facultyId != null)
			builder.setFacultyId(facultyId);
		if (programId != null)
			builder.setProgramId(programId);
		if (topicId != null)
			builder.setTopicId(topicId);
		if (parentTopicId != null)
			builder.setParentTopicId(parentTopicId);
		if (clientRequestId != null && !clientRequestId.isBlank())
			builder.setClientRequestId(clientRequestId);
		return Mono.fromCallable(() -> stub.createPost(builder.build())).subscribeOn(Schedulers.boundedElastic())
				.retry(2);
	}

	public Mono<PostResponse> getPostById(String postId, String viewerId) {
		GetPostByIdRequest.Builder builder = GetPostByIdRequest.newBuilder().setPostId(postId);
		if (viewerId != null)
			builder.setViewerId(viewerId);
		return Mono.fromCallable(() -> stub.getPostById(builder.build())).subscribeOn(Schedulers.boundedElastic())
				.retry(2);
	}

	public Mono<PostListResponse> getPostsByUser(String authorId, String viewerId, int page, int size) {
		GetPostsByUserRequest.Builder builder = GetPostsByUserRequest.newBuilder().setAuthorId(authorId).setPage(page)
				.setSize(size);
		if (viewerId != null)
			builder.setViewerId(viewerId);
		return Mono.fromCallable(() -> stub.getPostsByUser(builder.build())).subscribeOn(Schedulers.boundedElastic())
				.retry(2);
	}

	public Mono<PostListResponse> getPostsByIds(List<String> postIds, String viewerId) {
		GetPostsByIdsRequest.Builder builder = GetPostsByIdsRequest.newBuilder()
				.addAllPostIds(postIds == null ? List.of() : postIds);
		if (viewerId != null) {
			builder.setViewerId(viewerId);
		}
		return Mono.fromCallable(() -> stub.getPostsByIds(builder.build())).subscribeOn(Schedulers.boundedElastic())
				.retry(2);
	}

	public Mono<PostResponse> editPost(String postId, String requesterId, String content, boolean updateMediaUrls,
			List<String> mediaUrls) {
		EditPostRequest.Builder builder = EditPostRequest.newBuilder().setPostId(postId).setRequesterId(requesterId)
				.setUpdateMediaUrls(updateMediaUrls);

		if (content != null)
			builder.setContent(content);
		if (updateMediaUrls && mediaUrls != null)
			builder.addAllMediaUrls(mediaUrls);

		return Mono.fromCallable(() -> stub.editPost(builder.build())).subscribeOn(Schedulers.boundedElastic())
				.retry(2);
	}

	public Mono<Boolean> deletePost(String postId, String requesterId) {
		return deletePost(postId, requesterId, false);
	}

	public Mono<Boolean> deletePost(String postId, String requesterId, boolean adminOverride) {
		return Mono
				.fromCallable(() -> stub.deletePost(DeletePostRequest.newBuilder().setPostId(postId)
						.setRequesterId(requesterId).setAdminOverride(adminOverride).build()).getSuccess())
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<Boolean> likePost(String postId, String userId) {
		return Mono.fromCallable(() -> stub
				.likePost(LikePostRequest.newBuilder().setPostId(postId).setUserId(userId).build()).getSuccess())
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<Boolean> unlikePost(String postId, String userId) {
		return Mono.fromCallable(() -> stub
				.unlikePost(UnlikePostRequest.newBuilder().setPostId(postId).setUserId(userId).build()).getSuccess())
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<CommentResponse> addComment(String postId, String authorId, String content, String parentCommentId,
			String clientRequestId) {
		AddCommentRequest.Builder builder = AddCommentRequest.newBuilder().setPostId(postId).setAuthorId(authorId)
				.setContent(content);
		if (parentCommentId != null && !parentCommentId.isBlank()) {
			builder.setParentCommentId(parentCommentId);
		}
		if (clientRequestId != null && !clientRequestId.isBlank()) {
			builder.setClientRequestId(clientRequestId);
		}
		return Mono.fromCallable(() -> stub.addComment(builder.build())).subscribeOn(Schedulers.boundedElastic())
				.retry(2);
	}

	public Mono<CommentListResponse> getComments(String postId, int page, int size, String viewerId) {
		GetCommentsRequest.Builder builder = GetCommentsRequest.newBuilder().setPostId(postId).setPage(page)
				.setSize(size);
		if (viewerId != null && !viewerId.isBlank()) {
			builder.setViewerId(viewerId);
		}
		return Mono.fromCallable(() -> stub.getComments(builder.build())).subscribeOn(Schedulers.boundedElastic())
				.retry(2);
	}

	public Mono<CommentResponse> editComment(String commentId, String requesterId, String content) {
		return Mono
				.fromCallable(() -> stub.editComment(EditCommentRequest.newBuilder().setCommentId(commentId)
						.setRequesterId(requesterId).setContent(content).build()))
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<Boolean> deleteComment(String commentId, String requesterId) {
		return deleteComment(commentId, requesterId, false);
	}

	public Mono<Boolean> deleteComment(String commentId, String requesterId, boolean adminOverride) {
		return Mono
				.fromCallable(
						() -> stub
								.deleteComment(DeleteCommentRequest.newBuilder().setCommentId(commentId)
										.setRequesterId(requesterId).setAdminOverride(adminOverride).build())
								.getSuccess())
				.subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<Boolean> likeComment(String commentId, String userId) {
		return Mono.fromCallable(() -> stub
				.likeComment(LikeCommentRequest.newBuilder().setCommentId(commentId).setUserId(userId).build())
				.getSuccess()).subscribeOn(Schedulers.boundedElastic()).retry(2);
	}

	public Mono<Boolean> unlikeComment(String commentId, String userId) {
		return Mono.fromCallable(() -> stub
				.unlikeComment(UnlikeCommentRequest.newBuilder().setCommentId(commentId).setUserId(userId).build())
				.getSuccess()).subscribeOn(Schedulers.boundedElastic()).retry(2);

	}

}
