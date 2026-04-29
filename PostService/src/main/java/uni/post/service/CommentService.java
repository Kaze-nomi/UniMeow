package uni.post.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uni.post.entity.Comment;
import uni.post.entity.CommentLike;
import uni.post.entity.Post;
import uni.post.exception.CommentNotFoundException;
import uni.post.exception.PostNotFoundException;
import uni.post.record.CommentPageResult;
import uni.post.record.CommentResult;
import uni.post.repository.CommentLikeRepository;
import uni.post.repository.CommentRepository;
import uni.post.repository.PostRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

	private static final int DEFAULT_PAGE = 0;
	private static final int DEFAULT_SIZE = 20;

	private final CommentRepository commentRepository;
	private final CommentLikeRepository commentLikeRepository;
	private final PostRepository postRepository;

	@Transactional
	public CommentResult addComment(UUID postId, UUID authorId, String content, UUID parentCommentId) {
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("Comment content cannot be empty");
		}

		Post post = postRepository.findById(postId)
				.orElseThrow(() -> new PostNotFoundException("Post not found: " + postId));

		if (parentCommentId != null) {
			Comment parent = findOrThrow(parentCommentId);
			if (!parent.getPostId().equals(postId)) {
				throw new IllegalArgumentException("Parent comment belongs to another post");
			}
		}

		LocalDateTime now = LocalDateTime.now();

		Comment comment = commentRepository.save(Comment.builder().id(UUID.randomUUID()).postId(postId)
				.authorId(authorId).parentCommentId(parentCommentId).content(content.trim()).createdAt(now)
				.updatedAt(now).build());

		post.setCommentsCount(post.getCommentsCount() + 1);
		postRepository.save(post);

		log.info("Comment {} added to post {} by author {}", comment.getId(), postId, authorId);
		return new CommentResult(comment, false);
	}

	@Transactional(readOnly = true)
	public CommentPageResult getComments(UUID postId, int page, int size, UUID viewerId) {
		int resolvedPage = page >= 0 ? page : DEFAULT_PAGE;
		int resolvedSize = size > 0 ? size : DEFAULT_SIZE;

		Page<Comment> comments = commentRepository.findByPostIdOrderByCreatedAtAsc(postId,
				PageRequest.of(resolvedPage, resolvedSize));

		List<CommentResult> results = comments.getContent().stream().map(comment -> {
			boolean likedByMe = viewerId != null
					&& commentLikeRepository.existsByCommentIdAndUserId(comment.getId(), viewerId);
			return new CommentResult(comment, likedByMe);
		}).toList();

		return new CommentPageResult(results, comments.getTotalElements());
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
}
