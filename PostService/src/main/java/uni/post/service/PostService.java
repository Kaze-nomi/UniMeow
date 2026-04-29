package uni.post.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uni.post.entity.PostLike;
import uni.post.entity.Post;
import uni.post.exception.PostNotFoundException;
import uni.post.outbox.OutboxService;
import uni.post.repository.PostLikeRepository;
import uni.post.repository.PostRepository;
import uni.post.record.PostPageResult;
import uni.post.record.PostResult;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import uni.post.grpc.UserGrpcClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

	private static final int DEFAULT_PAGE = 0;
	private static final int DEFAULT_SIZE = 20;

	private final PostRepository postRepository;
	private final PostLikeRepository likeRepository;
	private final OutboxService outboxService;
	private final UserGrpcClient userGrpcClient;

	@Transactional
	public PostResult createPost(UUID authorId, String content, List<String> mediaUrls, Long topicId) {
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("Post content cannot be empty");
		}

		UserGrpcClient.PostTarget target = userGrpcClient.resolvePostTarget(authorId.toString(), null);
		if (!target.success()) {
			throw new IllegalArgumentException(target.error());
		}

		LocalDateTime now = LocalDateTime.now();

		Post post = postRepository.save(Post.builder().id(UUID.randomUUID()).authorId(authorId).content(content.trim())
				.mediaUrls(mediaUrls == null ? List.of() : mediaUrls).universityId(target.universityId())
				.facultyId(target.facultyId()).programId(target.programId()).topicId(target.topicId())
				.parentTopicId(target.parentTopicId()).likesCount(0).commentsCount(0).createdAt(now).updatedAt(now)
				.build());

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("postId", post.getId().toString());
		payload.put("authorId", post.getAuthorId().toString());
		if (target.universityId() != null) {
			payload.put("authorUniversityId", target.universityId().toString());
		}
		if (target.topicId() != null) {
			payload.put("topicId", target.topicId().toString());
		}
		if (target.facultyId() != null) {
			payload.put("facultyId", target.facultyId().toString());
		}
		if (target.programId() != null) {
			payload.put("programId", target.programId().toString());
		}
		if (target.parentTopicId() != null) {
			payload.put("parentTopicId", target.parentTopicId().toString());
		}
		payload.put("createdAt", post.getCreatedAt().toString());

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
