package uni.post.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uni.post.repository.CommentLikeRepository;
import uni.post.repository.CommentRepository;
import uni.post.repository.PostLikeRepository;
import uni.post.repository.PostRepository;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class PlatformMetrics implements MeterBinder {

	private final PostRepository postRepository;
	private final CommentRepository commentRepository;
	private final PostLikeRepository postLikeRepository;
	private final CommentLikeRepository commentLikeRepository;

	@Override
	public void bindTo(MeterRegistry registry) {
		Gauge.builder("platform_posts_total", postRepository, PostRepository::count)
				.description("Total number of posts on the platform").register(registry);
		Gauge.builder("platform_posts_last_24h_total", postRepository,
				repository -> repository.countByCreatedAtAfter(LocalDateTime.now().minusDays(1)))
				.description("Total number of posts created during the last 24 hours").register(registry);
		Gauge.builder("platform_posts_with_university_total", postRepository,
				PostRepository::countByUniversityIdIsNotNull)
				.description("Total number of posts linked to a university").register(registry);
		Gauge.builder("platform_post_likes_total", postLikeRepository, PostLikeRepository::count)
				.description("Total number of post like rows").register(registry);
		Gauge.builder("platform_post_like_count_total", postRepository, PostRepository::sumLikesCount)
				.description("Total denormalized post like count").register(registry);
		Gauge.builder("platform_comments_total", commentRepository, CommentRepository::count)
				.description("Total number of comments on the platform").register(registry);
		Gauge.builder("platform_comments_last_24h_total", commentRepository,
				repository -> repository.countByCreatedAtAfter(LocalDateTime.now().minusDays(1)))
				.description("Total number of comments created during the last 24 hours").register(registry);
		Gauge.builder("platform_comment_replies_total", commentRepository,
				CommentRepository::countByParentCommentIdIsNotNull).description("Total number of comment replies")
				.register(registry);
		Gauge.builder("platform_comment_likes_total", commentLikeRepository, CommentLikeRepository::count)
				.description("Total number of comment like rows").register(registry);
		Gauge.builder("platform_comment_like_count_total", commentRepository, CommentRepository::sumLikesCount)
				.description("Total denormalized comment like count").register(registry);
	}
}
