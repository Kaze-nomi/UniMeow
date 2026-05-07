package uni.post.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uni.post.entity.Comment;
import uni.post.entity.CommentLike;
import uni.post.entity.IdempotencyKey;
import uni.post.entity.PostLike;
import uni.post.entity.Post;
import uni.post.exception.PostNotFoundException;
import uni.post.outbox.OutboxService;
import uni.post.repository.CommentLikeRepository;
import uni.post.repository.CommentRepository;
import uni.post.repository.IdempotencyKeyRepository;
import uni.post.repository.PostLikeRepository;
import uni.post.repository.PostRepository;
import uni.post.record.PostPageResult;
import uni.post.record.PostResult;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

	private static final int DEFAULT_PAGE = 0;
	private static final int DEFAULT_SIZE = 20;

	private final PostRepository postRepository;
	private final CommentRepository commentRepository;
	private final PostLikeRepository likeRepository;
	private final CommentLikeRepository commentLikeRepository;
	private final IdempotencyKeyRepository idempotencyKeyRepository;
	private final OutboxService outboxService;
	private final MentionResolver mentionResolver;

	@Transactional
	public PostResult createPost(UUID authorId, String content, List<String> mediaUrls, Long universityId,
			Long facultyId, Long programId, Long topicId, Long parentTopicId, String clientRequestId) {
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("Post content cannot be empty");
		}

		if (clientRequestId != null && !clientRequestId.isBlank()) {
			var existing = idempotencyKeyRepository.findById(clientRequestId);
			if (existing.isPresent()) {
				UUID existingPostId = UUID.fromString(existing.get().getEntityId());
				return postRepository.findById(existingPostId).map(p -> new PostResult(p, false))
						.orElseThrow(() -> new PostNotFoundException("Idempotent post not found: " + existingPostId));
			}
		}

		LocalDateTime now = LocalDateTime.now();

		Post post = postRepository.save(Post.builder().id(UUID.randomUUID()).authorId(authorId).content(content.trim())
				.mediaUrls(mediaUrls == null ? List.of() : mediaUrls).universityId(universityId).facultyId(facultyId)
				.programId(programId).topicId(topicId).parentTopicId(parentTopicId).likesCount(0).commentsCount(0)
				.createdAt(now).updatedAt(now).build());

		if (clientRequestId != null && !clientRequestId.isBlank()) {
			idempotencyKeyRepository.save(IdempotencyKey.builder().key(clientRequestId)
					.entityId(post.getId().toString()).createdAt(now).build());
		}

		List<String> mentionedUserIds = mentionResolver.resolveUserIds(content);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("postId", post.getId().toString());
		payload.put("authorId", post.getAuthorId().toString());
		if (universityId != null) {
			payload.put("authorUniversityId", universityId.toString());
		}
		if (topicId != null) {
			payload.put("topicId", topicId.toString());
		}
		if (facultyId != null) {
			payload.put("facultyId", facultyId.toString());
		}
		if (programId != null) {
			payload.put("programId", programId.toString());
		}
		if (parentTopicId != null) {
			payload.put("parentTopicId", parentTopicId.toString());
		}
		payload.put("createdAt", post.getCreatedAt().toString());
		if (!mentionedUserIds.isEmpty()) {
			payload.put("mentionedUserIds", mentionedUserIds);
		}

		outboxService.enqueuePostEvent("POST_CREATED", post.getAuthorId().toString(), post.getId().toString(), payload);

		log.info("Post {} created by author {}", post.getId(), authorId);
		return new PostResult(post, false);
	}

	@Transactional(readOnly = true)
	public PostResult getById(UUID postId, UUID viewerId) {
		Post post = findOrThrow(postId);
		boolean likedByMe = viewerId != null && likeRepository.existsByPostIdAndUserId(postId, viewerId);
		return new PostResult(post, likedByMe);
	}

	@Transactional(readOnly = true)
	public PostPageResult getByAuthor(UUID authorId, UUID viewerId, int page, int size) {
		int resolvedPage = page >= 0 ? page : DEFAULT_PAGE;
		int resolvedSize = size > 0 ? size : DEFAULT_SIZE;

		Page<Post> postPage = postRepository.findByAuthorIdOrderByCreatedAtDesc(authorId,
				PageRequest.of(resolvedPage, resolvedSize));

		List<PostResult> results = postPage.getContent().stream().map(post -> {
			boolean likedByMe = viewerId != null && likeRepository.existsByPostIdAndUserId(post.getId(), viewerId);
			return new PostResult(post, likedByMe);
		}).toList();

		return new PostPageResult(results, postPage.getTotalElements());
	}

	@Transactional(readOnly = true)
	public List<PostResult> getByIds(List<UUID> postIds, UUID viewerId) {
		if (postIds == null || postIds.isEmpty()) {
			return List.of();
		}

		List<Post> posts = postRepository.findByIdIn(postIds);
		Map<UUID, Post> byId = new LinkedHashMap<>();
		for (Post post : posts) {
			byId.put(post.getId(), post);
		}

		return postIds.stream().map(byId::get).filter(post -> post != null).map(post -> {
			boolean likedByMe = viewerId != null && likeRepository.existsByPostIdAndUserId(post.getId(), viewerId);
			return new PostResult(post, likedByMe);
		}).toList();
	}

	@Transactional
	public PostResult editPost(UUID postId, UUID requesterId, boolean hasContent, String content,
			boolean updateMediaUrls, List<String> mediaUrls) {
		Post post = findOrThrow(postId);

		if (!post.getAuthorId().equals(requesterId)) {
			throw new IllegalArgumentException("Cannot edit someone else's post");
		}

		if (hasContent) {
			if (content == null || content.isBlank()) {
				throw new IllegalArgumentException("Post content cannot be empty");
			}
			post.setContent(content.trim());
		}

		if (updateMediaUrls) {
			post.setMediaUrls(mediaUrls == null ? List.of() : mediaUrls);
		}

		post.setUpdatedAt(LocalDateTime.now());
		Post saved = postRepository.save(post);

		boolean likedByMe = likeRepository.existsByPostIdAndUserId(saved.getId(), requesterId);
		return new PostResult(saved, likedByMe);
	}

	@Transactional
	public void deletePost(UUID postId, UUID requesterId) {
		deletePost(postId, requesterId, false);
	}

	@Transactional
	public void deletePost(UUID postId, UUID requesterId, boolean adminOverride) {
		Post post = findOrThrow(postId);

		if (!adminOverride && !post.getAuthorId().equals(requesterId)) {
			throw new IllegalArgumentException("Cannot delete someone else's post");
		}

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("postId", post.getId().toString());
		payload.put("authorId", post.getAuthorId().toString());
		payload.put("deletedAt", LocalDateTime.now().toString());
		appendFeedScopePayload(payload, post);

		outboxService.enqueuePostEvent("POST_DELETED", post.getAuthorId().toString(), post.getId().toString(), payload);

		log.info("Post {} deleted by requester {} (adminOverride={})", postId, requesterId, adminOverride);
		postRepository.delete(post);
	}

	@Transactional
	public void deleteAllContentByAuthor(UUID authorId) {
		List<PostLike> postLikes = likeRepository.findByUserId(authorId);
		List<CommentLike> commentLikes = commentLikeRepository.findByUserId(authorId);
		List<Comment> ownComments = commentRepository.findByAuthorId(authorId);
		List<Post> authoredPosts = postRepository.findByAuthorId(authorId);
		Set<UUID> authoredPostIds = authoredPosts.stream().map(Post::getId).collect(Collectors.toSet());

		Set<UUID> commentsToDelete = collectCommentSubtree(
				ownComments.stream().map(Comment::getId).collect(Collectors.toSet()));

		int decrementedPostLikes = 0;
		for (PostLike like : postLikes) {
			if (authoredPostIds.contains(like.getPostId())) {
				continue;
			}
			Post post = postRepository.findById(like.getPostId()).orElse(null);
			if (post != null) {
				post.setLikesCount(Math.max(0, post.getLikesCount() - 1));
				postRepository.save(post);
				decrementedPostLikes++;
			}
		}
		likeRepository.deleteByUserId(authorId);

		int decrementedCommentLikes = 0;
		for (CommentLike like : commentLikes) {
			if (commentsToDelete.contains(like.getCommentId())) {
				continue;
			}
			Comment comment = commentRepository.findById(like.getCommentId()).orElse(null);
			if (comment != null) {
				comment.setLikesCount(Math.max(0, comment.getLikesCount() - 1));
				commentRepository.save(comment);
				decrementedCommentLikes++;
			}
		}
		commentLikeRepository.deleteByUserId(authorId);

		Map<UUID, Long> commentsCountDecrementByPost = new java.util.HashMap<>();
		for (UUID commentId : commentsToDelete) {
			Comment c = commentRepository.findById(commentId).orElse(null);
			if (c != null && !authoredPostIds.contains(c.getPostId())) {
				commentsCountDecrementByPost.merge(c.getPostId(), 1L, Long::sum);
			}
		}

		if (!commentsToDelete.isEmpty()) {
			commentLikeRepository.deleteByCommentIdIn(commentsToDelete);
			commentRepository.deleteAllByIds(commentsToDelete);
		}

		for (Map.Entry<UUID, Long> entry : commentsCountDecrementByPost.entrySet()) {
			postRepository.findById(entry.getKey()).ifPresent(post -> {
				post.setCommentsCount(Math.max(0, post.getCommentsCount() - entry.getValue().intValue()));
				postRepository.save(post);
			});
		}

		if (!authoredPostIds.isEmpty()) {
			likeRepository.deleteByPostIdIn(authoredPostIds);
		}

		int deletedPosts = postRepository.deleteAllByAuthorId(authorId);
		log.info(
				"Deleted all content for user {}: {} comments (incl. replies), {} posts, {} post-likes (decremented {} foreign posts), {} comment-likes (decremented {} foreign comments)",
				authorId, commentsToDelete.size(), deletedPosts, postLikes.size(), decrementedPostLikes,
				commentLikes.size(), decrementedCommentLikes);
	}

	private Set<UUID> collectCommentSubtree(Set<UUID> rootIds) {
		Set<UUID> all = new java.util.HashSet<>(rootIds);
		Set<UUID> frontier = new java.util.HashSet<>(rootIds);
		while (!frontier.isEmpty()) {
			List<Comment> children = commentRepository.findByParentCommentIdIn(frontier);
			Set<UUID> next = new java.util.HashSet<>();
			for (Comment child : children) {
				if (all.add(child.getId())) {
					next.add(child.getId());
				}
			}
			frontier = next;
		}
		return all;
	}

	@Transactional
	public void likePost(UUID postId, UUID userId) {
		Post post = findOrThrow(postId);

		if (likeRepository.existsByPostIdAndUserId(postId, userId)) {
			return;
		}

		likeRepository.save(PostLike.builder().postId(postId).userId(userId).createdAt(LocalDateTime.now()).build());

		post.setLikesCount(post.getLikesCount() + 1);
		postRepository.save(post);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("postId", post.getId().toString());
		payload.put("authorId", post.getAuthorId().toString());
		payload.put("actorId", userId.toString());
		payload.put("userId", userId.toString());
		payload.put("likesCount", post.getLikesCount());
		payload.put("createdAtMs", post.getCreatedAt().toInstant(java.time.ZoneOffset.UTC).toEpochMilli());
		appendFeedScopePayload(payload, post);

		outboxService.enqueuePostEvent("POST_LIKED", post.getAuthorId().toString(), post.getId().toString(), payload);
	}

	@Transactional
	public void unlikePost(UUID postId, UUID userId) {
		Post post = findOrThrow(postId);

		if (!likeRepository.existsByPostIdAndUserId(postId, userId)) {
			return;
		}

		likeRepository.deleteByPostIdAndUserId(postId, userId);

		post.setLikesCount(Math.max(0, post.getLikesCount() - 1));
		postRepository.save(post);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("postId", post.getId().toString());
		payload.put("authorId", post.getAuthorId().toString());
		payload.put("userId", userId.toString());
		payload.put("likesCount", post.getLikesCount());
		payload.put("createdAtMs", post.getCreatedAt().toInstant(java.time.ZoneOffset.UTC).toEpochMilli());
		appendFeedScopePayload(payload, post);

		outboxService.enqueuePostEvent("POST_UNLIKED", post.getAuthorId().toString(), post.getId().toString(), payload);
	}

	private Post findOrThrow(UUID postId) {
		return postRepository.findById(postId)
				.orElseThrow(() -> new PostNotFoundException("Post not found: " + postId));
	}

	private static void appendFeedScopePayload(Map<String, Object> payload, Post post) {
		if (post.getUniversityId() != null) {
			payload.put("authorUniversityId", post.getUniversityId().toString());
			payload.put("universityId", post.getUniversityId().toString());
		}
		if (post.getTopicId() != null) {
			payload.put("topicId", post.getTopicId().toString());
		}
		if (post.getFacultyId() != null) {
			payload.put("facultyId", post.getFacultyId().toString());
		}
		if (post.getProgramId() != null) {
			payload.put("programId", post.getProgramId().toString());
		}
		if (post.getParentTopicId() != null) {
			payload.put("parentTopicId", post.getParentTopicId().toString());
		}
	}
}
