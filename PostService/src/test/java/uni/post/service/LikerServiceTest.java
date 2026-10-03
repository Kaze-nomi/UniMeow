package uni.post.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import uni.post.entity.Comment;
import uni.post.entity.Post;
import uni.post.exception.CommentNotFoundException;
import uni.post.exception.PostNotFoundException;
import uni.post.repository.CommentLikeRepository;
import uni.post.repository.CommentRepository;
import uni.post.repository.PostLikeRepository;
import uni.post.repository.PostRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LikerServiceTest {

	@Mock
	PostRepository postRepository;

	@Mock
	CommentRepository commentRepository;

	@Mock
	PostLikeRepository likeRepository;

	@Mock
	CommentLikeRepository commentLikeRepository;

	@InjectMocks
	PostService postService;

	@InjectMocks
	CommentService commentService;

	private static final UUID POST_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
	private static final UUID COMMENT_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
	private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440003");

	@Test
	void post_likers_limit_page_size_and_keep_total() {
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(Post.builder().id(POST_ID).build()));
		PageRequest request = PageRequest.of(1, 50);
		Page<UUID> expected = new PageImpl<>(List.of(USER_ID), request, 51);
		when(likeRepository.findLikerIdsByPostId(POST_ID, request)).thenReturn(expected);

		Page<UUID> result = postService.getPostLikers(POST_ID, 1, Integer.MAX_VALUE);

		assertThat(result.getContent()).containsExactly(USER_ID);
		assertThat(result.getTotalElements()).isEqualTo(51);
		verify(likeRepository).findLikerIdsByPostId(POST_ID, request);
	}

	@Test
	void comment_likers_limit_page_size_and_keep_total() {
		when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(Comment.builder().id(COMMENT_ID).build()));
		PageRequest request = PageRequest.of(1, 50);
		Page<UUID> expected = new PageImpl<>(List.of(USER_ID), request, 51);
		when(commentLikeRepository.findLikerIdsByCommentId(COMMENT_ID, request)).thenReturn(expected);

		Page<UUID> result = commentService.getCommentLikers(COMMENT_ID, 1, Integer.MAX_VALUE);

		assertThat(result.getContent()).containsExactly(USER_ID);
		assertThat(result.getTotalElements()).isEqualTo(51);
		verify(commentLikeRepository).findLikerIdsByCommentId(COMMENT_ID, request);
	}

	@Test
	void invalid_pagination_uses_defaults() {
		when(postRepository.findById(POST_ID)).thenReturn(Optional.of(Post.builder().id(POST_ID).build()));
		when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(Comment.builder().id(COMMENT_ID).build()));
		PageRequest request = PageRequest.of(0, 20);
		when(likeRepository.findLikerIdsByPostId(POST_ID, request)).thenReturn(Page.empty(request));
		when(commentLikeRepository.findLikerIdsByCommentId(COMMENT_ID, request)).thenReturn(Page.empty(request));

		assertThat(postService.getPostLikers(POST_ID, -1, 0).getContent()).isEmpty();
		assertThat(commentService.getCommentLikers(COMMENT_ID, -1, -10).getContent()).isEmpty();
		verify(likeRepository).findLikerIdsByPostId(POST_ID, request);
		verify(commentLikeRepository).findLikerIdsByCommentId(COMMENT_ID, request);
	}

	@Test
	void missing_post_is_rejected_before_loading_likes() {
		when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> postService.getPostLikers(POST_ID, 0, 20)).isInstanceOf(PostNotFoundException.class);
		verifyNoInteractions(likeRepository);
	}

	@Test
	void missing_comment_is_rejected_before_loading_likes() {
		when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> commentService.getCommentLikers(COMMENT_ID, 0, 20))
				.isInstanceOf(CommentNotFoundException.class);
		verifyNoInteractions(commentLikeRepository);
	}
}
