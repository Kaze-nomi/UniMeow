package uni.post.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import uni.post.entity.Comment;
import uni.post.entity.CommentLike;
import uni.post.entity.Post;
import uni.post.exception.CommentNotFoundException;
import uni.post.exception.PostNotFoundException;
import uni.post.outbox.OutboxService;
import uni.post.record.CommentResult;
import uni.post.repository.CommentLikeRepository;
import uni.post.repository.CommentRepository;
import uni.post.repository.IdempotencyKeyRepository;
import uni.post.repository.PostRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

	@Mock
	CommentRepository commentRepository;

	@Mock
	CommentLikeRepository commentLikeRepository;

	@Mock
	PostRepository postRepository;

	@Mock
	IdempotencyKeyRepository idempotencyKeyRepository;

	@Mock
	OutboxService outboxService;

	@Mock
	MentionResolver mentionResolver;

	@InjectMocks
	CommentService commentService;

	private static final UUID POST_ID = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479");
	private static final UUID AUTHOR_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
	private static final UUID COMMENT_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");

	private Post buildPost() {
		LocalDateTime now = LocalDateTime.now();
		return Post.builder().id(POST_ID).authorId(AUTHOR_ID).content("Post").mediaUrls(List.of()).likesCount(0)
				.commentsCount(0).createdAt(now).updatedAt(now).build();
	}

	private Comment buildComment() {
		LocalDateTime now = LocalDateTime.now();
		return Comment.builder().id(COMMENT_ID).postId(POST_ID).authorId(AUTHOR_ID).content("Nice post!").createdAt(now)
				.updatedAt(now).build();
	}

	@Test
	void add_comment_saves_and_returns_comment() {
		Comment comment = buildComment();
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(buildPost()));
		when(commentRepository.save(any())).thenReturn(comment);

		CommentResult result = commentService.addComment(POST_ID, AUTHOR_ID, "Nice post!", null, null);

		assertThat(result).isEqualTo(new CommentResult(comment, false));
		verify(commentRepository).save(any());
	}

	@Test
	void add_comment_increments_comments_count_on_post() {
		Post post = buildPost();
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
		when(commentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		commentService.addComment(POST_ID, AUTHOR_ID, "text", null, null);

		assertThat(post.getCommentsCount()).isEqualTo(1);
		verify(postRepository).save(post);
	}

	@Test
	void add_comment_sets_created_at_equal_to_updated_at() {
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(buildPost()));
		when(commentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		commentService.addComment(POST_ID, AUTHOR_ID, "text", null, null);

		ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
		verify(commentRepository).save(captor.capture());
		assertThat(captor.getValue().getUpdatedAt()).isEqualTo(captor.getValue().getCreatedAt());
	}

	@Test
	void add_comment_trims_content() {
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(buildPost()));
		when(commentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		commentService.addComment(POST_ID, AUTHOR_ID, "  trimmed  ", null, null);

		ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
		verify(commentRepository).save(captor.capture());
		assertThat(captor.getValue().getContent()).isEqualTo("trimmed");
	}

	@Test
	void add_comment_throws_when_content_is_blank() {
		assertThatThrownBy(() -> commentService.addComment(POST_ID, AUTHOR_ID, "  ", null, null))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("content cannot be empty");

		verifyNoInteractions(postRepository, commentRepository);
	}

	@Test
	void add_comment_throws_post_not_found_when_post_missing() {
		when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> commentService.addComment(POST_ID, AUTHOR_ID, "text", null, null))
				.isInstanceOf(PostNotFoundException.class).hasMessageContaining(POST_ID.toString());
	}

	@Test
	void add_comment_sets_parent_comment_id_for_replies() {
		Comment parent = buildComment();
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(buildPost()));
		when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(parent));
		when(commentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		commentService.addComment(POST_ID, AUTHOR_ID, "reply", COMMENT_ID, null);

		ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
		verify(commentRepository).save(captor.capture());
		assertThat(captor.getValue().getParentCommentId()).isEqualTo(COMMENT_ID);
	}

	@Test
	void add_comment_rejects_parent_from_another_post() {
		UUID otherPostId = UUID.fromString("aaaaaaaa-58cc-4372-a567-0e02b2c3d479");
		Comment parent = buildComment();
		parent.setPostId(otherPostId);
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(buildPost()));
		when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(parent));

		assertThatThrownBy(() -> commentService.addComment(POST_ID, AUTHOR_ID, "reply", COMMENT_ID, null))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another post");
	}

	@Test
	void get_comments_returns_page_of_comments() {
		Comment comment = buildComment();
		when(commentRepository.findByPostIdOrderByCreatedAtAsc(eq(POST_ID), any()))
				.thenReturn(new PageImpl<>(List.of(comment)));

		var result = commentService.getComments(POST_ID, 0, 20, null);

		assertThat(result.comments()).containsExactly(new CommentResult(comment, false));
		assertThat(result.total()).isEqualTo(1);
	}

	@Test
	void get_comments_defaults_negative_page_to_zero() {
		when(commentRepository.findByPostIdOrderByCreatedAtAsc(eq(POST_ID), any()))
				.thenReturn(new PageImpl<>(List.of()));

		commentService.getComments(POST_ID, -1, 20, null);

		ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
		verify(commentRepository).findByPostIdOrderByCreatedAtAsc(eq(POST_ID), captor.capture());
		assertThat(captor.getValue().getPageNumber()).isZero();
	}

	@Test
	void get_comments_defaults_zero_size_to_20() {
		when(commentRepository.findByPostIdOrderByCreatedAtAsc(eq(POST_ID), any()))
				.thenReturn(new PageImpl<>(List.of()));

		commentService.getComments(POST_ID, 0, 0, null);

		ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
		verify(commentRepository).findByPostIdOrderByCreatedAtAsc(eq(POST_ID), captor.capture());
		assertThat(captor.getValue().getPageSize()).isEqualTo(20);
	}

	@Test
	void edit_comment_updates_content() {
		Comment comment = buildComment();
		when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(comment));

		CommentResult result = commentService.editComment(COMMENT_ID, AUTHOR_ID, "Updated text");

		assertThat(result.comment().getContent()).isEqualTo("Updated text");
	}

	@Test
	void edit_comment_updates_updated_at_but_not_created_at() {
		Comment comment = buildComment();
		LocalDateTime originalCreatedAt = comment.getCreatedAt();
		LocalDateTime originalUpdatedAt = comment.getUpdatedAt();

		when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(comment));

		commentService.editComment(COMMENT_ID, AUTHOR_ID, "updated");

		assertThat(comment.getCreatedAt()).isEqualTo(originalCreatedAt);
		assertThat(comment.getUpdatedAt()).isAfterOrEqualTo(originalUpdatedAt);
	}

	@Test
	void edit_comment_trims_content() {
		Comment comment = buildComment();
		when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(comment));

		commentService.editComment(COMMENT_ID, AUTHOR_ID, "  trimmed  ");

		assertThat(comment.getContent()).isEqualTo("trimmed");
	}

	@Test
	void edit_comment_throws_when_requester_is_not_author() {
		when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(buildComment()));

		assertThatThrownBy(() -> commentService.editComment(COMMENT_ID, UUID.randomUUID(), "hack"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("someone else");

		verify(commentRepository, never()).save(any());
	}

	@Test
	void edit_comment_throws_when_content_is_blank() {
		assertThatThrownBy(() -> commentService.editComment(COMMENT_ID, AUTHOR_ID, ""))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("content cannot be empty");

		verifyNoInteractions(commentRepository);
	}

	@Test
	void edit_comment_throws_comment_not_found_when_missing() {
		when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> commentService.editComment(COMMENT_ID, AUTHOR_ID, "text"))
				.isInstanceOf(CommentNotFoundException.class).hasMessageContaining(COMMENT_ID.toString());
	}

	@Test
	void delete_comment_calls_repository_delete() {
		when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(buildComment()));
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(buildPost()));

		commentService.deleteComment(COMMENT_ID, AUTHOR_ID);

		verify(commentRepository).delete(any(Comment.class));
	}

	@Test
	void delete_comment_decrements_comments_count_on_post() {
		Post post = buildPost();
		post.setCommentsCount(5);

		when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(buildComment()));
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));

		commentService.deleteComment(COMMENT_ID, AUTHOR_ID);

		assertThat(post.getCommentsCount()).isEqualTo(4);
		verify(postRepository).save(post);
	}

	@Test
	void delete_comment_does_not_decrement_below_zero() {
		Post post = buildPost();
		post.setCommentsCount(0);

		when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(buildComment()));
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));

		commentService.deleteComment(COMMENT_ID, AUTHOR_ID);

		assertThat(post.getCommentsCount()).isZero();
	}

	@Test
	void delete_comment_throws_when_requester_is_not_author() {
		when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(buildComment()));

		assertThatThrownBy(() -> commentService.deleteComment(COMMENT_ID, UUID.randomUUID()))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("someone else");

		verify(commentRepository, never()).delete(any());
	}

	@Test
	void delete_comment_throws_comment_not_found_when_missing() {
		when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> commentService.deleteComment(COMMENT_ID, AUTHOR_ID))
				.isInstanceOf(CommentNotFoundException.class);
	}

	@Test
	void like_comment_calls_repository_save() {
		Comment comment = buildComment();
		when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(comment));

		commentService.likeComment(COMMENT_ID, AUTHOR_ID);

		verify(commentLikeRepository).save(any(CommentLike.class));
	}

	@Test
	void unlike_comment_calls_repository_delete() {
		Comment comment = buildComment();
		when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(comment));
		when(commentLikeRepository.existsByCommentIdAndUserId(COMMENT_ID, AUTHOR_ID)).thenReturn(true);

		commentService.unlikeComment(COMMENT_ID, AUTHOR_ID);

		verify(commentLikeRepository).deleteByCommentIdAndUserId(COMMENT_ID, AUTHOR_ID);
	}
}
