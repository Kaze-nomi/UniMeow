package uni.post.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;
import uni.grpc.post.*;
import uni.post.entity.Comment;
import uni.post.entity.Post;
import uni.post.exception.CommentNotFoundException;
import uni.post.exception.PostNotFoundException;
import uni.post.service.CommentService;
import uni.post.service.PostService;
import uni.post.record.CommentResult;
import uni.post.record.PostPageResult;
import uni.post.record.PostResult;

import java.util.UUID;
import java.util.stream.Collectors;

@GrpcService
@RequiredArgsConstructor
public class PostGrpcServer extends PostServiceGrpc.PostServiceImplBase {

	private final PostService postService;
	private final CommentService commentService;

	@Override
	public void createPost(CreatePostRequest req, StreamObserver<PostResponse> obs) {
		try {
			PostResult result = postService.createPost(UUID.fromString(req.getAuthorId()), req.getContent(),
					req.getMediaUrlsList(), req.hasUniversityId() ? req.getUniversityId() : null,
					req.hasFacultyId() ? req.getFacultyId() : null, req.hasProgramId() ? req.getProgramId() : null,
					req.hasTopicId() ? req.getTopicId() : null, req.hasParentTopicId() ? req.getParentTopicId() : null,
					req.hasClientRequestId() ? req.getClientRequestId() : null);
			obs.onNext(toProto(result));
			obs.onCompleted();
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void getPostById(GetPostByIdRequest req, StreamObserver<PostResponse> obs) {
		try {
			UUID viewerId = req.hasViewerId() && !req.getViewerId().isBlank()
					? UUID.fromString(req.getViewerId())
					: null;

			PostResult result = postService.getById(UUID.fromString(req.getPostId()), viewerId);
			obs.onNext(toProto(result));
			obs.onCompleted();
		} catch (PostNotFoundException e) {
			obs.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription("Invalid UUID").asRuntimeException());
		}
	}

	@Override
	public void getPostsByUser(GetPostsByUserRequest req, StreamObserver<PostListResponse> obs) {
		try {
			UUID viewerId = req.hasViewerId() && !req.getViewerId().isBlank()
					? UUID.fromString(req.getViewerId())
					: null;

			PostPageResult pageResult = postService.getByAuthor(UUID.fromString(req.getAuthorId()), viewerId,
					req.getPage(), req.getSize());

			PostListResponse.Builder builder = PostListResponse.newBuilder().setTotal((int) pageResult.total());

			pageResult.posts().forEach(r -> builder.addPosts(toProto(r)));

			obs.onNext(builder.build());
			obs.onCompleted();
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription("Invalid UUID").asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void getPostsByIds(GetPostsByIdsRequest req, StreamObserver<PostListResponse> obs) {
		try {
			UUID viewerId = req.hasViewerId() && !req.getViewerId().isBlank()
					? UUID.fromString(req.getViewerId())
					: null;

			var ids = req.getPostIdsList().stream().map(UUID::fromString).collect(Collectors.toList());

			var posts = postService.getByIds(ids, viewerId);
			PostListResponse.Builder builder = PostListResponse.newBuilder().setTotal(posts.size());

			posts.forEach(r -> builder.addPosts(toProto(r)));

			obs.onNext(builder.build());
			obs.onCompleted();
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription("Invalid UUID").asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void editPost(EditPostRequest req, StreamObserver<PostResponse> obs) {
		try {
			PostResult result = postService.editPost(UUID.fromString(req.getPostId()),
					UUID.fromString(req.getRequesterId()), req.hasContent(), req.getContent(), req.getUpdateMediaUrls(),
					req.getMediaUrlsList());
			obs.onNext(toProto(result));
			obs.onCompleted();
		} catch (PostNotFoundException e) {
			obs.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void deletePost(DeletePostRequest req, StreamObserver<DeletePostResponse> obs) {
		try {
			if (req.getAdminOverride()) {
				postService.deletePost(UUID.fromString(req.getPostId()), UUID.fromString(req.getRequesterId()), true);
			} else {
				postService.deletePost(UUID.fromString(req.getPostId()), UUID.fromString(req.getRequesterId()));
			}
			obs.onNext(DeletePostResponse.newBuilder().setSuccess(true).build());
			obs.onCompleted();
		} catch (PostNotFoundException e) {
			obs.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void likePost(LikePostRequest req, StreamObserver<LikeResponse> obs) {
		try {
			postService.likePost(UUID.fromString(req.getPostId()), UUID.fromString(req.getUserId()));
			obs.onNext(LikeResponse.newBuilder().setSuccess(true).build());
			obs.onCompleted();
		} catch (PostNotFoundException e) {
			obs.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription("Invalid UUID").asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void unlikePost(UnlikePostRequest req, StreamObserver<LikeResponse> obs) {
		try {
			postService.unlikePost(UUID.fromString(req.getPostId()), UUID.fromString(req.getUserId()));
			obs.onNext(LikeResponse.newBuilder().setSuccess(true).build());
			obs.onCompleted();
		} catch (PostNotFoundException e) {
			obs.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription("Invalid UUID").asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void addComment(AddCommentRequest req, StreamObserver<CommentResponse> obs) {
		try {
			CommentResult comment = commentService.addComment(UUID.fromString(req.getPostId()),
					UUID.fromString(req.getAuthorId()), req.getContent(),
					req.hasParentCommentId() && !req.getParentCommentId().isBlank()
							? UUID.fromString(req.getParentCommentId())
							: null,
					req.hasClientRequestId() ? req.getClientRequestId() : null);
			obs.onNext(toProto(comment));
			obs.onCompleted();
		} catch (PostNotFoundException e) {
			obs.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void getComments(GetCommentsRequest req, StreamObserver<CommentListResponse> obs) {
		try {
			UUID viewerId = req.hasViewerId() && !req.getViewerId().isBlank()
					? UUID.fromString(req.getViewerId())
					: null;
			var commentPage = commentService.getComments(UUID.fromString(req.getPostId()), req.getPage(), req.getSize(),
					viewerId);

			CommentListResponse.Builder builder = CommentListResponse.newBuilder().setTotal((int) commentPage.total());

			commentPage.comments().forEach(c -> builder.addComments(toProto(c)));

			obs.onNext(builder.build());
			obs.onCompleted();
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription("Invalid UUID").asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void editComment(EditCommentRequest req, StreamObserver<CommentResponse> obs) {
		try {
			CommentResult comment = commentService.editComment(UUID.fromString(req.getCommentId()),
					UUID.fromString(req.getRequesterId()), req.getContent());
			obs.onNext(toProto(comment));
			obs.onCompleted();
		} catch (CommentNotFoundException e) {
			obs.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void deleteComment(DeleteCommentRequest req, StreamObserver<DeleteCommentResponse> obs) {
		try {
			if (req.getAdminOverride()) {
				commentService.deleteComment(UUID.fromString(req.getCommentId()), UUID.fromString(req.getRequesterId()),
						true);
			} else {
				commentService.deleteComment(UUID.fromString(req.getCommentId()),
						UUID.fromString(req.getRequesterId()));
			}
			obs.onNext(DeleteCommentResponse.newBuilder().setSuccess(true).build());
			obs.onCompleted();
		} catch (CommentNotFoundException e) {
			obs.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
		} catch (IllegalArgumentException e) {
			obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void likeComment(LikeCommentRequest req, StreamObserver<LikeResponse> obs) {
		try {
			commentService.likeComment(UUID.fromString(req.getCommentId()), UUID.fromString(req.getUserId()));
			obs.onNext(LikeResponse.newBuilder().setSuccess(true).build());
			obs.onCompleted();
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	@Override
	public void unlikeComment(UnlikeCommentRequest req, StreamObserver<LikeResponse> obs) {
		try {
			commentService.unlikeComment(UUID.fromString(req.getCommentId()), UUID.fromString(req.getUserId()));
			obs.onNext(LikeResponse.newBuilder().setSuccess(true).build());
			obs.onCompleted();
		} catch (Exception e) {
			obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
		}
	}

	private PostResponse toProto(PostResult r) {
		Post p = r.post();
		PostResponse.Builder builder = PostResponse.newBuilder().setId(p.getId().toString())
				.setAuthorId(p.getAuthorId().toString()).setContent(p.getContent()).setLikesCount(p.getLikesCount())
				.setCommentsCount(p.getCommentsCount()).setLikedByMe(r.likedByMe())
				.setCreatedAt(p.getCreatedAt().toString()).setUpdatedAt(p.getUpdatedAt().toString());

		if (p.getMediaUrls() != null) {
			builder.addAllMediaUrls(p.getMediaUrls());
		}
		if (p.getUniversityId() != null) {
			builder.setUniversityId(p.getUniversityId());
		}
		if (p.getTopicId() != null) {
			builder.setTopicId(p.getTopicId());
		}
		if (p.getFacultyId() != null) {
			builder.setFacultyId(p.getFacultyId());
		}
		if (p.getProgramId() != null) {
			builder.setProgramId(p.getProgramId());
		}

		return builder.build();
	}

	private CommentResponse toProto(CommentResult c) {
		Comment comment = c.comment();
		CommentResponse.Builder builder = CommentResponse.newBuilder().setId(comment.getId().toString())
				.setPostId(comment.getPostId().toString()).setAuthorId(comment.getAuthorId().toString())
				.setContent(comment.getContent()).setLikesCount(Integer.toString(comment.getLikesCount()))
				.setLikedByMe(c.likedByMe()).setCreatedAt(comment.getCreatedAt().toString())
				.setUpdatedAt(comment.getUpdatedAt().toString());
		if (comment.getParentCommentId() != null) {
			builder.setParentCommentId(comment.getParentCommentId().toString());
		}
		return builder.build();
	}
}
