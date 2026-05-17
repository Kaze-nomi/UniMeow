package uni.post.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uni.post.entity.Comment;
import uni.post.entity.CommentLike;
import uni.post.entity.IdempotencyKey;
import uni.post.entity.Post;
import uni.post.exception.CommentNotFoundException;
import uni.post.exception.PostNotFoundException;
import uni.post.outbox.OutboxService;
import uni.post.record.CommentPageResult;
import uni.post.record.CommentResult;
import uni.post.repository.CommentLikeRepository;
import uni.post.repository.CommentRepository;
import uni.post.repository.IdempotencyKeyRepository;
import uni.post.repository.PostRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

	private static final int DEFAULT_PAGE = 0;
	private static final int DEFAULT_SIZE = 20;
	private static final long DEFAULT_RECOMMENDATION_LIKE_BOOST_MS = 3_600_000L;

	private final CommentRepository commentRepository;
	private final CommentLikeRepository commentLikeRepository;
	private final PostRepository postRepository;
	private final IdempotencyKeyRepository idempotencyKeyRepository;
	private final OutboxService outboxService;
	private final MentionResolver mentionResolver;

	private long commentRecommendationLikeBoostMs = DEFAULT_RECOMMENDATION_LIKE_BOOST_MS;

	@Value("${app.comments.recommendation-like-boost-ms:3600000}")
	void setCommentRecommendationLikeBoostMs(long commentRecommendationLikeBoostMs) {
		this.commentRecommendationLikeBoostMs = commentRecommendationLikeBoostMs;
	}

	@Transactional
	public CommentResult addComment(UUID postId, UUID authorId, String content, UUID parentCommentId,
			String clientRequestId) {
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("Comment content cannot be empty");
		}

		if (clientRequestId != null && !clientRequestId.isBlank()) {
			var existing = idempotencyKeyRepository.findById(clientRequestId);
			if (existing.isPresent()) {
				UUID existingCommentId = UUID.fromString(existing.get().getEntityId());
				return commentRepository.findById(existingCommentId).map(c -> new CommentResult(c, false)).orElseThrow(
						() -> new CommentNotFoundException("Idempotent comment not found: " + existingCommentId));
			}
		}

		Post post = postRepository.findById(postId)
				.orElseThrow(() -> new PostNotFoundException("Post not found: " + postId));

		String parentAuthorId = null;
		if (parentCommentId != null) {
			Comment parent = findOrThrow(parentCommentId);
			if (!parent.getPostId().equals(postId)) {
				throw new IllegalArgumentException("Parent comment belongs to another post");
			}
			parentAuthorId = parent.getAuthorId().toString();
		}

		List<String> mentionedUserIds = mentionResolver.resolveUserIds(content);

		LocalDateTime now = LocalDateTime.now();

		Comment comment = commentRepository.save(Comment.builder().id(UUID.randomUUID()).postId(postId)
				.authorId(authorId).parentCommentId(parentCommentId).content(content.trim()).createdAt(now)
				.updatedAt(now).build());

		if (clientRequestId != null && !clientRequestId.isBlank()) {
			idempotencyKeyRepository.save(IdempotencyKey.builder().key(clientRequestId)
					.entityId(comment.getId().toString()).createdAt(now).build());
		}

		post.setCommentsCount(post.getCommentsCount() + 1);
		postRepository.save(post);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("commentId", comment.getId().toString());
		payload.put("postId", postId.toString());
		payload.put("authorId", authorId.toString());
		payload.put("postAuthorId", post.getAuthorId().toString());
		if (parentCommentId != null) {
			payload.put("parentCommentId", parentCommentId.toString());
		}
		if (parentAuthorId != null) {
			payload.put("parentAuthorId", parentAuthorId);
		}
		if (!mentionedUserIds.isEmpty()) {
			payload.put("mentionedUserIds", mentionedUserIds);
		}
		outboxService.enqueuePostEvent("COMMENT_CREATED", authorId.toString(), comment.getId().toString(), payload);

		log.info("Comment {} added to post {} by author {}", comment.getId(), postId, authorId);
		return new CommentResult(comment, false);
	}

	@Transactional(readOnly = true)
	public CommentPageResult getComments(UUID postId, int page, int size, UUID viewerId) {
		int resolvedPage = page >= 0 ? page : DEFAULT_PAGE;
		int resolvedSize = size > 0 ? size : DEFAULT_SIZE;

		List<Comment> sortedComments = sortCommentsForDisplay(commentRepository.findByPostId(postId));
		long offset = (long) resolvedPage * resolvedSize;
		int fromIndex = offset >= sortedComments.size() ? sortedComments.size() : (int) offset;
		int toIndex = Math.min(fromIndex + resolvedSize, sortedComments.size());

		List<CommentResult> results = sortedComments.subList(fromIndex, toIndex).stream().map(comment -> {
			boolean likedByMe = viewerId != null
					&& commentLikeRepository.existsByCommentIdAndUserId(comment.getId(), viewerId);
			return new CommentResult(comment, likedByMe);
		}).toList();

		return new CommentPageResult(results, sortedComments.size());
	}

	private List<Comment> sortCommentsForDisplay(List<Comment> comments) {
		Map<UUID, List<Comment>> repliesByParent = new HashMap<>();
		List<Comment> rootComments = new ArrayList<>();

		for (Comment comment : comments) {
			if (comment.getParentCommentId() == null) {
				rootComments.add(comment);
			} else {
				repliesByParent.computeIfAbsent(comment.getParentCommentId(), ignored -> new ArrayList<>())
						.add(comment);
			}
		}

		rootComments.sort(Comparator.comparingDouble(this::recommendationScore).reversed()
				.thenComparing(Comment::getCreatedAt, Comparator.reverseOrder())
				.thenComparing(c -> c.getId().toString(), Comparator.reverseOrder()));
		repliesByParent.values().forEach(replies -> replies
				.sort(Comparator.comparing(Comment::getCreatedAt).thenComparing(c -> c.getId().toString())));

		List<Comment> sortedComments = new ArrayList<>(comments.size());
		for (Comment root : rootComments) {
			sortedComments.add(root);
			sortedComments.addAll(repliesByParent.getOrDefault(root.getId(), List.of()));
		}

		Set<UUID> rootCommentIds = rootComments.stream().map(Comment::getId).collect(Collectors.toSet());
		repliesByParent.entrySet().stream().filter(entry -> !rootCommentIds.contains(entry.getKey()))
				.sorted(Map.Entry.comparingByKey()).forEach(entry -> sortedComments.addAll(entry.getValue()));
		return sortedComments;
	}

	@Transactional
	public CommentResult editComment(UUID commentId, UUID requesterId, String content) {
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("Comment content cannot be empty");
		}

		Comment comment = findOrThrow(commentId);

		if (!comment.getAuthorId().equals(requesterId)) {
			throw new IllegalArgumentException("Cannot edit someone else's comment");
		}

		comment.setContent(content.trim());
		comment.setUpdatedAt(LocalDateTime.now());

		boolean likedByMe = commentLikeRepository.existsByCommentIdAndUserId(comment.getId(), requesterId);
		return new CommentResult(comment, likedByMe);
	}

	@Transactional
	public void deleteComment(UUID commentId, UUID requesterId) {
		deleteComment(commentId, requesterId, false);
	}

	@Transactional
	public void deleteComment(UUID commentId, UUID requesterId, boolean adminOverride) {
		Comment comment = findOrThrow(commentId);

		if (!adminOverride && !comment.getAuthorId().equals(requesterId)) {
			throw new IllegalArgumentException("Cannot delete someone else's comment");
		}

		log.info("Comment {} deleted by requester {} (adminOverride={})", commentId, requesterId, adminOverride);
		commentRepository.delete(comment);

		postRepository.findById(comment.getPostId()).ifPresent(post -> {
			post.setCommentsCount(Math.max(0, post.getCommentsCount() - 1));
			postRepository.save(post);
		});
	}

	@Transactional
	public void likeComment(UUID commentId, UUID userId) {
		Comment comment = findOrThrow(commentId);

		if (commentLikeRepository.existsByCommentIdAndUserId(commentId, userId)) {
			return;
		}

		commentLikeRepository
				.save(CommentLike.builder().commentId(commentId).userId(userId).createdAt(LocalDateTime.now()).build());

		comment.setLikesCount(comment.getLikesCount() + 1);
		commentRepository.save(comment);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("commentId", commentId.toString());
		payload.put("postId", comment.getPostId().toString());
		payload.put("commentAuthorId", comment.getAuthorId().toString());
		payload.put("actorId", userId.toString());
		outboxService.enqueuePostEvent("COMMENT_LIKED", userId.toString(), commentId.toString(), payload);
	}

	@Transactional
	public void unlikeComment(UUID commentId, UUID userId) {
		Comment comment = findOrThrow(commentId);

		if (!commentLikeRepository.existsByCommentIdAndUserId(commentId, userId)) {
			return;
		}

		commentLikeRepository.deleteByCommentIdAndUserId(commentId, userId);

		comment.setLikesCount(Math.max(0, comment.getLikesCount() - 1));
		commentRepository.save(comment);
	}

	private Comment findOrThrow(UUID commentId) {
		return commentRepository.findById(commentId)
				.orElseThrow(() -> new CommentNotFoundException("Comment not found: " + commentId));
	}

	private double recommendationScore(Comment comment) {
		long createdAtMs = comment.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
		return createdAtMs + (double) comment.getLikesCount() * commentRecommendationLikeBoostMs;
	}
}
