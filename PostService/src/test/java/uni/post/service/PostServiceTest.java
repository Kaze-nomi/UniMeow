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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostServiceTest {

	@Mock
	PostRepository postRepository;
	@Mock
	CommentRepository commentRepository;
	@Mock
	PostLikeRepository likeRepository;
	@Mock
	CommentLikeRepository commentLikeRepository;
	@Mock
	IdempotencyKeyRepository idempotencyKeyRepository;
	@Mock
	OutboxService outboxService;
	@Mock
	MentionResolver mentionResolver;

	@InjectMocks
	PostService postService;

	private static final UUID AUTHOR_ID = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479");
	private static final UUID POST_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
	private static final UUID VIEWER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");

	private Post buildPost() {
		LocalDateTime now = LocalDateTime.now();
		return Post.builder().id(POST_ID).authorId(AUTHOR_ID).content("Hello UniMeow").mediaUrls(List.of())
				.universityId(1L).facultyId(10L).programId(100L).topicId(10L).parentTopicId(10L).likesCount(0)
				.commentsCount(0).createdAt(now).updatedAt(now).build();
	}

	@Test
	void create_post_saves_and_returns_result_with_liked_by_me_false() {
		Post post = buildPost();
		when(postRepository.save(any())).thenReturn(post);

		PostResult result = postService.createPost(AUTHOR_ID, "Hello UniMeow", List.of(), 1L, 10L, 100L, 10L, 10L,
				null);

		assertThat(result.post()).isEqualTo(post);
		assertThat(result.likedByMe()).isFalse();
		verify(postRepository).save(any());
		verifyNoInteractions(likeRepository);
	}

	@Test
	void create_post_trims_content() {
		when(postRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		postService.createPost(AUTHOR_ID, "  trimmed  ", List.of(), 1L, 10L, 100L, 10L, 10L, null);

		ArgumentCaptor<Post> captor = ArgumentCaptor.forClass(Post.class);
		verify(postRepository).save(captor.capture());
		assertThat(captor.getValue().getContent()).isEqualTo("trimmed");
	}

	@Test
	void create_post_sets_created_at_equal_to_updated_at() {
		when(postRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		postService.createPost(AUTHOR_ID, "content", List.of(), 1L, 10L, 100L, 10L, 10L, null);

		ArgumentCaptor<Post> captor = ArgumentCaptor.forClass(Post.class);
		verify(postRepository).save(captor.capture());
		assertThat(captor.getValue().getUpdatedAt()).isEqualTo(captor.getValue().getCreatedAt());
	}

	@Test
	void create_post_replaces_null_media_urls_with_empty_list() {
		when(postRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		postService.createPost(AUTHOR_ID, "content", null, 1L, 10L, 100L, 10L, 10L, null);

		ArgumentCaptor<Post> captor = ArgumentCaptor.forClass(Post.class);
		verify(postRepository).save(captor.capture());
		assertThat(captor.getValue().getMediaUrls()).isEmpty();
	}

	@Test
	void create_post_throws_when_content_is_blank() {
		assertThatThrownBy(() -> postService.createPost(AUTHOR_ID, "  ", List.of(), null, null, null, null, null, null))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("content cannot be empty");

		verifyNoInteractions(postRepository);
	}

	@Test
	void create_post_stores_provided_scope() {
		when(postRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		PostResult result = postService.createPost(AUTHOR_ID, "content", List.of(), 1L, 10L, 100L, 10L, 10L, null);

		assertThat(result.post().getUniversityId()).isEqualTo(1L);
		assertThat(result.post().getFacultyId()).isEqualTo(10L);
		assertThat(result.post().getProgramId()).isEqualTo(100L);
		assertThat(result.post().getTopicId()).isEqualTo(10L);
		assertThat(result.post().getParentTopicId()).isEqualTo(10L);
	}

	@Test
	void create_post_throws_when_content_is_null() {
		assertThatThrownBy(() -> postService.createPost(AUTHOR_ID, null, List.of(), null, null, null, null, null, null))
				.isInstanceOf(IllegalArgumentException.class);

		verifyNoInteractions(postRepository);
	}

	@Test
	void get_by_id_returns_post_without_checking_likes_when_viewer_is_null() {
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(buildPost()));

		PostResult result = postService.getById(POST_ID, null);

		assertThat(result.post().getId()).isEqualTo(POST_ID);
		assertThat(result.likedByMe()).isFalse();
		verifyNoInteractions(likeRepository);
	}

	@Test
	void get_by_id_returns_liked_by_me_true_when_viewer_liked_post() {
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(buildPost()));
		when(likeRepository.existsByPostIdAndUserId(POST_ID, VIEWER_ID)).thenReturn(true);

		PostResult result = postService.getById(POST_ID, VIEWER_ID);

		assertThat(result.likedByMe()).isTrue();
	}

	@Test
	void get_by_id_returns_liked_by_me_false_when_viewer_has_not_liked() {
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(buildPost()));
		when(likeRepository.existsByPostIdAndUserId(POST_ID, VIEWER_ID)).thenReturn(false);

		PostResult result = postService.getById(POST_ID, VIEWER_ID);

		assertThat(result.likedByMe()).isFalse();
	}

	@Test
	void get_by_id_throws_post_not_found_when_missing() {
		when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> postService.getById(POST_ID, null)).isInstanceOf(PostNotFoundException.class)
				.hasMessageContaining(POST_ID.toString());
	}

	@Test
	void get_by_author_returns_page_with_correct_liked_by_me_per_post() {
		Post post1 = buildPost();
		Post post2 = Post.builder().id(UUID.randomUUID()).authorId(AUTHOR_ID).content("second").mediaUrls(List.of())
				.likesCount(0).commentsCount(0).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

		when(postRepository.findByAuthorIdOrderByCreatedAtDesc(eq(AUTHOR_ID), any()))
				.thenReturn(new PageImpl<>(List.of(post1, post2)));
		when(likeRepository.existsByPostIdAndUserId(post1.getId(), VIEWER_ID)).thenReturn(true);
		when(likeRepository.existsByPostIdAndUserId(post2.getId(), VIEWER_ID)).thenReturn(false);

		PostPageResult result = postService.getByAuthor(AUTHOR_ID, VIEWER_ID, 0, 20);

		assertThat(result.total()).isEqualTo(2);
		assertThat(result.posts().get(0).likedByMe()).isTrue();
		assertThat(result.posts().get(1).likedByMe()).isFalse();
	}

	@Test
	void get_by_author_defaults_negative_page_to_zero() {
		when(postRepository.findByAuthorIdOrderByCreatedAtDesc(eq(AUTHOR_ID), any()))
				.thenReturn(new PageImpl<>(List.of()));

		postService.getByAuthor(AUTHOR_ID, null, -5, 20);

		ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
		verify(postRepository).findByAuthorIdOrderByCreatedAtDesc(eq(AUTHOR_ID), captor.capture());
		assertThat(captor.getValue().getPageNumber()).isZero();
	}

	@Test
	void get_by_author_defaults_zero_size_to_20() {
		when(postRepository.findByAuthorIdOrderByCreatedAtDesc(eq(AUTHOR_ID), any()))
				.thenReturn(new PageImpl<>(List.of()));

		postService.getByAuthor(AUTHOR_ID, null, 0, 0);

		ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
		verify(postRepository).findByAuthorIdOrderByCreatedAtDesc(eq(AUTHOR_ID), captor.capture());
		assertThat(captor.getValue().getPageSize()).isEqualTo(20);
	}

	@Test
	void get_by_author_skips_like_check_when_viewer_is_null() {
		when(postRepository.findByAuthorIdOrderByCreatedAtDesc(eq(AUTHOR_ID), any()))
				.thenReturn(new PageImpl<>(List.of(buildPost())));

		postService.getByAuthor(AUTHOR_ID, null, 0, 20);

		verifyNoInteractions(likeRepository);
	}

	@Test
	void edit_post_updates_content_and_returns_result() {
		Post post = buildPost();
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
		when(postRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(likeRepository.existsByPostIdAndUserId(POST_ID, AUTHOR_ID)).thenReturn(false);

		PostResult result = postService.editPost(POST_ID, AUTHOR_ID, true, "New content", false, null);

		assertThat(result.post().getContent()).isEqualTo("New content");
	}

	@Test
	void edit_post_updates_updated_at() {
		Post post = buildPost();
		LocalDateTime originalUpdatedAt = post.getUpdatedAt();

		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
		when(postRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(likeRepository.existsByPostIdAndUserId(any(), any())).thenReturn(false);

		postService.editPost(POST_ID, AUTHOR_ID, true, "updated", false, null);

		assertThat(post.getUpdatedAt()).isAfterOrEqualTo(originalUpdatedAt);
	}

	@Test
	void edit_post_does_not_change_created_at() {
		Post post = buildPost();
		LocalDateTime originalCreatedAt = post.getCreatedAt();

		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
		when(postRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(likeRepository.existsByPostIdAndUserId(any(), any())).thenReturn(false);

		postService.editPost(POST_ID, AUTHOR_ID, true, "updated", false, null);

		assertThat(post.getCreatedAt()).isEqualTo(originalCreatedAt);
	}

	@Test
	void edit_post_replaces_media_urls_when_update_flag_is_true() {
		Post post = buildPost();
		List<String> newUrls = List.of("url1", "url2");

		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
		when(postRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(likeRepository.existsByPostIdAndUserId(any(), any())).thenReturn(false);

		postService.editPost(POST_ID, AUTHOR_ID, false, null, true, newUrls);

		assertThat(post.getMediaUrls()).containsExactlyElementsOf(newUrls);
	}

	@Test
	void edit_post_does_not_touch_media_urls_when_flag_is_false() {
		Post post = buildPost();
		post.setMediaUrls(List.of("original"));

		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
		when(postRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(likeRepository.existsByPostIdAndUserId(any(), any())).thenReturn(false);

		postService.editPost(POST_ID, AUTHOR_ID, false, null, false, List.of("ignored"));

		assertThat(post.getMediaUrls()).containsExactly("original");
	}

	@Test
	void edit_post_throws_when_requester_is_not_author() {
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(buildPost()));

		assertThatThrownBy(() -> postService.editPost(POST_ID, UUID.randomUUID(), true, "hack", false, null))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("someone else");

		verify(postRepository, never()).save(any());
	}

	@Test
	void edit_post_throws_when_new_content_is_blank() {
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(buildPost()));

		assertThatThrownBy(() -> postService.editPost(POST_ID, AUTHOR_ID, true, "  ", false, null))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("content cannot be empty");
	}

	@Test
	void edit_post_throws_post_not_found_when_missing() {
		when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> postService.editPost(POST_ID, AUTHOR_ID, true, "x", false, null))
				.isInstanceOf(PostNotFoundException.class);
	}

	@Test
	void delete_post_calls_repository_delete() {
		Post post = buildPost();
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));

		postService.deletePost(POST_ID, AUTHOR_ID);

		verify(postRepository).delete(post);
	}

	@Test
	void delete_post_throws_when_requester_is_not_author() {
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(buildPost()));

		assertThatThrownBy(() -> postService.deletePost(POST_ID, UUID.randomUUID()))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("someone else");

		verify(postRepository, never()).delete(any());
	}

	@Test
	void delete_post_throws_post_not_found_when_missing() {
		when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> postService.deletePost(POST_ID, AUTHOR_ID)).isInstanceOf(PostNotFoundException.class);
	}

	@Test
	void delete_all_content_by_author_deletes_posts_and_comments() {
		UUID otherPostId = UUID.fromString("550e8400-e29b-41d4-a716-446655440099");
		Comment comment = Comment.builder().id(UUID.randomUUID()).postId(otherPostId).authorId(AUTHOR_ID)
				.content("comment").likesCount(0).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
		Post authoredPost = buildPost();

		when(commentRepository.findByAuthorId(AUTHOR_ID)).thenReturn(List.of(comment));
		when(postRepository.findByAuthorId(AUTHOR_ID)).thenReturn(List.of(authoredPost));

		postService.deleteAllContentByAuthor(AUTHOR_ID);

		verify(commentLikeRepository).deleteByUserId(AUTHOR_ID);
		verify(commentLikeRepository).deleteByCommentIdIn(eq(Set.of(comment.getId())));
		verify(commentRepository).deleteAllByIds(eq(Set.of(comment.getId())));
		verify(postRepository, never()).save(any());
		verify(postRepository).deleteAllByAuthorId(AUTHOR_ID);
	}

	@Test
	void delete_all_content_by_author_emits_unlike_for_foreign_liked_posts() {
		UUID foreignPostId = UUID.fromString("550e8400-e29b-41d4-a716-446655440099");
		Post foreignPost = buildPost();
		foreignPost.setId(foreignPostId);
		foreignPost.setAuthorId(VIEWER_ID);
		foreignPost.setLikesCount(3);

		when(likeRepository.findPostIdsByUserId(AUTHOR_ID)).thenReturn(List.of(foreignPostId));
		when(commentLikeRepository.findCommentIdsByUserId(AUTHOR_ID)).thenReturn(List.of());
		when(commentRepository.findByAuthorId(AUTHOR_ID)).thenReturn(List.of());
		when(postRepository.findByAuthorId(AUTHOR_ID)).thenReturn(List.of());
		when(postRepository.findById(foreignPostId)).thenReturn(Optional.of(foreignPost));

		postService.deleteAllContentByAuthor(AUTHOR_ID);

		assertThat(foreignPost.getLikesCount()).isEqualTo(2);
		verify(postRepository).save(foreignPost);
		ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
		verify(outboxService).enqueuePostEvent(eq("POST_UNLIKED"), eq(VIEWER_ID.toString()),
				eq(foreignPostId.toString()), payloadCaptor.capture());
		assertThat(payloadCaptor.getValue()).containsEntry("postId", foreignPostId.toString())
				.containsEntry("userId", AUTHOR_ID.toString()).containsEntry("likesCount", 2);
	}

	@Test
	void like_post_saves_like_and_increments_counter() {
		Post post = buildPost();
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
		when(likeRepository.existsByPostIdAndUserId(POST_ID, VIEWER_ID)).thenReturn(false);

		postService.likePost(POST_ID, VIEWER_ID);

		ArgumentCaptor<PostLike> captor = ArgumentCaptor.forClass(PostLike.class);
		verify(likeRepository).save(captor.capture());
		assertThat(captor.getValue().getPostId()).isEqualTo(POST_ID);
		assertThat(captor.getValue().getUserId()).isEqualTo(VIEWER_ID);
		assertThat(post.getLikesCount()).isEqualTo(1);
		verify(postRepository).save(post);
	}

	@Test
	void like_post_publishes_post_liked_event() {
		Post post = buildPost();
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
		when(likeRepository.existsByPostIdAndUserId(POST_ID, VIEWER_ID)).thenReturn(false);

		postService.likePost(POST_ID, VIEWER_ID);

		verify(outboxService).enqueuePostEvent(eq("POST_LIKED"), eq(AUTHOR_ID.toString()), eq(POST_ID.toString()),
				any());
	}

	@Test
	void like_post_event_payload_contains_feed_scope_fields() {
		Post post = buildPost();
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
		when(likeRepository.existsByPostIdAndUserId(POST_ID, VIEWER_ID)).thenReturn(false);

		postService.likePost(POST_ID, VIEWER_ID);

		ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
		verify(outboxService).enqueuePostEvent(eq("POST_LIKED"), any(), any(), payloadCaptor.capture());

		Map<String, Object> payload = payloadCaptor.getValue();
		assertThat(payload).containsEntry("authorUniversityId", "1").containsEntry("universityId", "1")
				.containsEntry("topicId", "10").containsEntry("parentTopicId", "10");
	}

	@Test
	void like_post_is_idempotent_when_already_liked() {
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(buildPost()));
		when(likeRepository.existsByPostIdAndUserId(POST_ID, VIEWER_ID)).thenReturn(true);

		postService.likePost(POST_ID, VIEWER_ID);

		verify(likeRepository, never()).save(any());
		verify(postRepository, never()).save(any());
		verify(outboxService, never()).enqueuePostEvent(eq("POST_LIKED"), any(), any(), any());
	}

	@Test
	void like_post_throws_post_not_found_when_missing() {
		when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> postService.likePost(POST_ID, VIEWER_ID)).isInstanceOf(PostNotFoundException.class);
	}

	@Test
	void unlike_post_deletes_like_and_decrements_counter() {
		Post post = buildPost();
		post.setLikesCount(3);

		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
		when(likeRepository.existsByPostIdAndUserId(POST_ID, VIEWER_ID)).thenReturn(true);

		postService.unlikePost(POST_ID, VIEWER_ID);

		verify(likeRepository).deleteByPostIdAndUserId(POST_ID, VIEWER_ID);
		assertThat(post.getLikesCount()).isEqualTo(2);
		verify(postRepository).save(post);
	}

	@Test
	void unlike_post_publishes_post_unliked_event() {
		Post post = buildPost();
		post.setLikesCount(3);

		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
		when(likeRepository.existsByPostIdAndUserId(POST_ID, VIEWER_ID)).thenReturn(true);

		postService.unlikePost(POST_ID, VIEWER_ID);

		verify(outboxService).enqueuePostEvent(eq("POST_UNLIKED"), eq(AUTHOR_ID.toString()), eq(POST_ID.toString()),
				any());
	}

	@Test
	void unlike_post_event_payload_contains_feed_scope_fields() {
		Post post = buildPost();
		post.setLikesCount(3);
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
		when(likeRepository.existsByPostIdAndUserId(POST_ID, VIEWER_ID)).thenReturn(true);

		postService.unlikePost(POST_ID, VIEWER_ID);

		ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
		verify(outboxService).enqueuePostEvent(eq("POST_UNLIKED"), any(), any(), payloadCaptor.capture());
		Map<String, Object> payload = payloadCaptor.getValue();
		assertThat(payload).containsEntry("authorUniversityId", "1").containsEntry("universityId", "1")
				.containsEntry("topicId", "10").containsEntry("parentTopicId", "10");
	}

	@Test
	void delete_post_event_payload_contains_feed_scope_fields() {
		Post post = buildPost();
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));

		postService.deletePost(POST_ID, AUTHOR_ID);

		ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
		verify(outboxService).enqueuePostEvent(eq("POST_DELETED"), any(), any(), payloadCaptor.capture());
		Map<String, Object> payload = payloadCaptor.getValue();
		assertThat(payload).containsEntry("authorUniversityId", "1").containsEntry("universityId", "1")
				.containsEntry("topicId", "10").containsEntry("parentTopicId", "10");
	}

	@Test
	void unlike_post_does_not_go_below_zero() {
		Post post = buildPost();
		post.setLikesCount(0);

		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
		when(likeRepository.existsByPostIdAndUserId(POST_ID, VIEWER_ID)).thenReturn(true);

		postService.unlikePost(POST_ID, VIEWER_ID);

		assertThat(post.getLikesCount()).isZero();
	}

	@Test
	void unlike_post_is_idempotent_when_no_like_exists() {
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(buildPost()));
		when(likeRepository.existsByPostIdAndUserId(POST_ID, VIEWER_ID)).thenReturn(false);

		postService.unlikePost(POST_ID, VIEWER_ID);

		verify(likeRepository, never()).deleteByPostIdAndUserId(any(), any());
		verify(postRepository, never()).save(any());
		verify(outboxService, never()).enqueuePostEvent(eq("POST_UNLIKED"), any(), any(), any());
	}
}
