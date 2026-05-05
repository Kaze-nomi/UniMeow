package uni.gateway.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import uni.gateway.grpc.FeedGrpcClient;
import uni.gateway.grpc.PostGrpcClient;
import uni.gateway.grpc.UserGrpcClient;
import uni.gateway.security.JwtUtil;
import uni.grpc.feed.FeedType;
import uni.grpc.feed.GetFeedResponse;
import uni.grpc.post.*;
import uni.grpc.user.UserResponse;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PostControllerTest {

	@LocalServerPort
	int port;

	@MockitoBean
	PostGrpcClient postGrpcClient;

	@MockitoBean
	UserGrpcClient userGrpcClient;

	@MockitoBean
	FeedGrpcClient feedGrpcClient;

	@Autowired
	JwtUtil jwtUtil;

	WebTestClient client;

	private static final String USER_ID = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
	private static final String POST_ID = "550e8400-e29b-41d4-a716-446655440001";
	private static final String COMMENT_ID = "550e8400-e29b-41d4-a716-446655440002";

	@BeforeEach
	void setUp() {
		client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
		when(userGrpcClient.getUserById(anyString())).thenReturn(Mono
				.just(UserResponse.newBuilder().setId(USER_ID).setUsername("test_user").setIsBanned(false).build()));
	}

	private String validToken() {
		return jwtUtil.generateToken(USER_ID);
	}

	private WebTestClient.RequestBodySpec graphqlPost() {
		return client.post().uri("/graphql").cookie("ACCESS_TOKEN", validToken()).header("Content-Type",
				"application/json");
	}

	private PostResponse buildPostResponse() {
		return PostResponse.newBuilder().setId(POST_ID).setAuthorId(USER_ID).setContent("Hello UniMeow!")
				.setLikesCount(5).setCommentsCount(2).setLikedByMe(true).setCreatedAt("2024-01-01T10:00:00")
				.setUpdatedAt("2024-01-01T12:00:00").setUniversityId(1L).setTopicId(1L).build();
	}

	private CommentResponse buildCommentResponse() {
		return CommentResponse.newBuilder().setId(COMMENT_ID).setPostId(POST_ID).setAuthorId(USER_ID)
				.setContent("Nice post!").setCreatedAt("2024-01-01T10:00:00").setUpdatedAt("2024-01-01T10:00:00")
				.build();
	}

	@Test
	void get_post_returns_id_and_content() {
		when(postGrpcClient.getPostById(eq(POST_ID), eq(USER_ID))).thenReturn(Mono.just(buildPostResponse()));

		graphqlPost().bodyValue(String.format("{\"query\": \"{ getPost(id: \\\"%s\\\") { id content } }\"}", POST_ID))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.getPost.id").isEqualTo(POST_ID)
				.jsonPath("$.data.getPost.content").isEqualTo("Hello UniMeow!");
	}

	@Test
	void get_post_returns_likes_count_and_liked_by_me() {
		when(postGrpcClient.getPostById(eq(POST_ID), eq(USER_ID))).thenReturn(Mono.just(buildPostResponse()));

		graphqlPost()
				.bodyValue(
						String.format("{\"query\": \"{ getPost(id: \\\"%s\\\") { likesCount likedByMe } }\"}", POST_ID))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.getPost.likesCount").isEqualTo(5)
				.jsonPath("$.data.getPost.likedByMe").isEqualTo(true);
	}

	@Test
	void get_post_returns_updated_at() {
		when(postGrpcClient.getPostById(eq(POST_ID), eq(USER_ID))).thenReturn(Mono.just(buildPostResponse()));

		graphqlPost().bodyValue(String.format("{\"query\": \"{ getPost(id: \\\"%s\\\") { updatedAt } }\"}", POST_ID))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.getPost.updatedAt")
				.isEqualTo("2024-01-01T12:00:00");
	}

	@Test
	void get_post_returns_graphql_error_when_not_found() {
		when(postGrpcClient.getPostById(any(), any()))
				.thenReturn(Mono.error(io.grpc.Status.NOT_FOUND.asRuntimeException()));

		graphqlPost().bodyValue(String.format("{\"query\": \"{ getPost(id: \\\"%s\\\") { id } }\"}", POST_ID))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.errors").isArray()
				.jsonPath("$.errors[0].extensions.code").isEqualTo("NOT_FOUND");
	}

	@Test
	void get_post_is_accessible_without_token() {
		client.post().uri("/graphql").header("Content-Type", "application/json")
				.bodyValue(String.format("{\"query\": \"{ getPost(id: \\\"%s\\\") { id } }\"}", POST_ID)).exchange()
				.expectStatus().isOk();
	}

	@Test
	void get_user_posts_returns_posts_and_total() {
		PostListResponse listResponse = PostListResponse.newBuilder().addPosts(buildPostResponse()).setTotal(1).build();
		when(postGrpcClient.getPostsByUser(eq(USER_ID), eq(USER_ID), eq(0), eq(20)))
				.thenReturn(Mono.just(listResponse));

		graphqlPost()
				.bodyValue(String.format("{\"query\": \"{ getUserPosts(userId: \\\"%s\\\") { total posts { id } } }\"}",
						USER_ID))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.getUserPosts.total").isEqualTo(1)
				.jsonPath("$.data.getUserPosts.posts[0].id").isEqualTo(POST_ID);
	}

	@Test
	void get_user_posts_passes_custom_page_and_size() {
		when(postGrpcClient.getPostsByUser(eq(USER_ID), eq(USER_ID), eq(2), eq(5)))
				.thenReturn(Mono.just(PostListResponse.newBuilder().setTotal(0).build()));

		graphqlPost()
				.bodyValue(String.format(
						"{\"query\": \"{ getUserPosts(userId: \\\"%s\\\", page: 2, size: 5) { total } }\"}", USER_ID))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.getUserPosts.total").isEqualTo(0);
	}

	@Test
	void get_comments_returns_comments_and_total() {
		CommentListResponse listResponse = CommentListResponse.newBuilder().addComments(buildCommentResponse())
				.setTotal(1).build();
		when(postGrpcClient.getComments(eq(POST_ID), eq(0), eq(20), eq(USER_ID))).thenReturn(Mono.just(listResponse));

		graphqlPost()
				.bodyValue(String.format(
						"{\"query\": \"{ getComments(postId: \\\"%s\\\") { total comments { id content } } }\"}",
						POST_ID))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.getComments.total").isEqualTo(1)
				.jsonPath("$.data.getComments.comments[0].id").isEqualTo(COMMENT_ID)
				.jsonPath("$.data.getComments.comments[0].content").isEqualTo("Nice post!");
	}

	@Test
	void get_comments_returns_updated_at_for_each_comment() {
		CommentListResponse listResponse = CommentListResponse.newBuilder().addComments(buildCommentResponse())
				.setTotal(1).build();
		when(postGrpcClient.getComments(eq(POST_ID), eq(0), eq(20), eq(USER_ID))).thenReturn(Mono.just(listResponse));

		graphqlPost()
				.bodyValue(String.format(
						"{\"query\": \"{ getComments(postId: \\\"%s\\\") { comments { updatedAt } } }\"}", POST_ID))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.getComments.comments[0].updatedAt")
				.isEqualTo("2024-01-01T10:00:00");
	}

	@Test
	void trending_feed_with_university_scope_calls_university_feed_client() {
		GetFeedResponse feed = GetFeedResponse.newBuilder().addPostIds(POST_ID).setHasMore(false).build();
		PostListResponse posts = PostListResponse.newBuilder().addPosts(buildPostResponse()).setTotal(1).build();
		when(feedGrpcClient.getUniversityFeed(eq(FeedType.TRENDING), eq(USER_ID), isNull(), eq(20), eq(1L), isNull(),
				isNull())).thenReturn(Mono.just(feed));
		when(postGrpcClient.getPostsByIds(eq(java.util.List.of(POST_ID)), eq(USER_ID))).thenReturn(Mono.just(posts));

		graphqlPost().bodyValue("{\"query\":\"{ trendingFeed(universityId: \\\"1\\\") { posts { id } hasMore } }\"}")
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.trendingFeed.posts[0].id")
				.isEqualTo(POST_ID).jsonPath("$.data.trendingFeed.hasMore").isEqualTo(false);

		verify(feedGrpcClient).getUniversityFeed(eq(FeedType.TRENDING), eq(USER_ID), isNull(), eq(20), eq(1L), isNull(),
				isNull());
	}

	@Test
	void trending_feed_with_no_university_topic_calls_outside_feed_scope() {
		GetFeedResponse feed = GetFeedResponse.newBuilder().setHasMore(false).build();
		when(feedGrpcClient.getFeed(eq(FeedType.TRENDING), eq(USER_ID), isNull(), eq(20), eq(-1L)))
				.thenReturn(Mono.just(feed));

		graphqlPost().bodyValue("{\"query\":\"{ trendingFeed(topicId: \\\"-1\\\") { posts { id } hasMore } }\"}")
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.trendingFeed.posts").isArray()
				.jsonPath("$.data.trendingFeed.hasMore").isEqualTo(false);

		verify(feedGrpcClient).getFeed(eq(FeedType.TRENDING), eq(USER_ID), isNull(), eq(20), eq(-1L));
	}

	@Test
	void create_post_returns_created_post() {
		UserResponse fullProfile = UserResponse.newBuilder().setId(USER_ID).setUsername("test_user").setIsBanned(false)
				.setUniversity(uni.grpc.user.University.newBuilder().setId(1L).setName("Uni").setShortName("UNI"))
				.setFaculty(uni.grpc.user.Faculty.newBuilder().setId(2L).setName("Faculty").setShortName("FAC"))
				.setProgram(uni.grpc.user.Program.newBuilder().setId(3L).setFacultyId(2L).setName("Program")
						.setShortName("PRG"))
				.build();
		when(userGrpcClient.getUserById(USER_ID)).thenReturn(Mono.just(fullProfile));
		when(postGrpcClient.createPost(eq(USER_ID), eq("Hello!"), any(), eq(1L), eq(2L), eq(3L), isNull(), isNull()))
				.thenReturn(Mono.just(buildPostResponse()));

		graphqlPost().bodyValue(
				"{\"query\": \"mutation { createPost(input: { content: \\\"Hello!\\\", topicId: 1 }) { id content universityId topicId } }\"}")
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.createPost.id").isEqualTo(POST_ID)
				.jsonPath("$.data.createPost.content").isEqualTo("Hello UniMeow!")
				.jsonPath("$.data.createPost.universityId").isEqualTo(1).jsonPath("$.data.createPost.topicId")
				.isEqualTo(1);
	}

	@Test
	void create_post_returns_graphql_error_without_token() {
		client.post().uri("/graphql").header("Content-Type", "application/json")
				.bodyValue("{\"query\": \"mutation { createPost(input: { content: \\\"x\\\" }) { id } }\"}").exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.errors").isArray();
	}

	@Test
	void create_post_is_blocked_before_completed_registration_by_graphql_guard() {
		when(userGrpcClient.getUserById(USER_ID))
				.thenReturn(Mono.just(UserResponse.newBuilder().setId(USER_ID).setIsBanned(false).build()));

		graphqlPost().bodyValue("{\"query\": \"mutation { createPost(input: { content: \\\"x\\\" }) { id } }\"}")
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.errors").isArray()
				.jsonPath("$.errors[0].message").value(v -> org.hamcrest.MatcherAssert.assertThat((String) v,
						org.hamcrest.Matchers.containsString("Завершите регистрацию")));

		verify(postGrpcClient, never()).createPost(any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void create_post_returns_graphql_error_on_blank_content() {
		when(postGrpcClient.createPost(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Mono.error(
				io.grpc.Status.INVALID_ARGUMENT.withDescription("content cannot be empty").asRuntimeException()));

		graphqlPost().bodyValue("{\"query\": \"mutation { createPost(input: { content: \\\"\\\" }) { id } }\"}")
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.errors").isArray();
	}

	@Test
	void edit_post_returns_updated_post() {
		PostResponse updated = buildPostResponse().toBuilder().setContent("updated content").build();
		when(postGrpcClient.editPost(eq(POST_ID), eq(USER_ID), eq("updated content"), eq(false), isNull()))
				.thenReturn(Mono.just(updated));

		graphqlPost().bodyValue(String.format(
				"{\"query\": \"mutation { editPost(postId: \\\"%s\\\", input: { content: \\\"updated content\\\" }) { content } }\"}",
				POST_ID)).exchange().expectStatus().isOk().expectBody().jsonPath("$.data.editPost.content")
				.isEqualTo("updated content");
	}

	@Test
	void edit_post_returns_graphql_error_without_token() {
		client.post().uri("/graphql").header("Content-Type", "application/json").bodyValue(String.format(
				"{\"query\": \"mutation { editPost(postId: \\\"%s\\\", input: { content: \\\"x\\\" }) { id } }\"}",
				POST_ID)).exchange().expectStatus().isOk().expectBody().jsonPath("$.errors").isArray();
	}

	@Test
	void edit_post_returns_graphql_error_when_not_author() {
		when(postGrpcClient.editPost(any(), any(), any(), anyBoolean(), any()))
				.thenReturn(Mono.error(io.grpc.Status.INVALID_ARGUMENT
						.withDescription("Cannot edit someone else's post").asRuntimeException()));

		graphqlPost().bodyValue(String.format(
				"{\"query\": \"mutation { editPost(postId: \\\"%s\\\", input: { content: \\\"x\\\" }) { id } }\"}",
				POST_ID)).exchange().expectStatus().isOk().expectBody().jsonPath("$.errors").isArray();
	}

	@Test
	void delete_post_returns_success_true() {
		when(postGrpcClient.deletePost(eq(POST_ID), eq(USER_ID))).thenReturn(Mono.just(true));

		graphqlPost()
				.bodyValue(String.format("{\"query\": \"mutation { deletePost(postId: \\\"%s\\\") { success } }\"}",
						POST_ID))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.deletePost.success").isEqualTo(true);
	}

	@Test
	void delete_post_returns_graphql_error_without_token() {
		client.post().uri("/graphql").header("Content-Type", "application/json").bodyValue(
				String.format("{\"query\": \"mutation { deletePost(postId: \\\"%s\\\") { success } }\"}", POST_ID))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.errors").isArray();
	}

	@Test
	void delete_post_returns_graphql_error_when_not_found() {
		when(postGrpcClient.deletePost(any(), any()))
				.thenReturn(Mono.error(io.grpc.Status.NOT_FOUND.asRuntimeException()));

		graphqlPost().bodyValue(
				String.format("{\"query\": \"mutation { deletePost(postId: \\\"%s\\\") { success } }\"}", POST_ID))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.errors").isArray();
	}

	@Test
	void like_post_returns_success_true() {
		when(postGrpcClient.likePost(eq(POST_ID), eq(USER_ID))).thenReturn(Mono.just(true));

		graphqlPost()
				.bodyValue(String.format("{\"query\": \"mutation { likePost(postId: \\\"%s\\\") { success } }\"}",
						POST_ID))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.likePost.success").isEqualTo(true);
	}

	@Test
	void like_post_returns_graphql_error_without_token() {
		client.post().uri("/graphql").header("Content-Type", "application/json").bodyValue(
				String.format("{\"query\": \"mutation { likePost(postId: \\\"%s\\\") { success } }\"}", POST_ID))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.errors").isArray();
	}

	@Test
	void unlike_post_returns_success_true() {
		when(postGrpcClient.unlikePost(eq(POST_ID), eq(USER_ID))).thenReturn(Mono.just(true));

		graphqlPost()
				.bodyValue(String.format("{\"query\": \"mutation { unlikePost(postId: \\\"%s\\\") { success } }\"}",
						POST_ID))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.unlikePost.success").isEqualTo(true);
	}

	@Test
	void unlike_post_returns_graphql_error_when_not_found() {
		when(postGrpcClient.unlikePost(any(), any()))
				.thenReturn(Mono.error(io.grpc.Status.NOT_FOUND.asRuntimeException()));

		graphqlPost().bodyValue(
				String.format("{\"query\": \"mutation { unlikePost(postId: \\\"%s\\\") { success } }\"}", POST_ID))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.errors").isArray();
	}

	@Test
	void add_comment_returns_comment_with_id_and_content() {
		when(postGrpcClient.addComment(eq(POST_ID), eq(USER_ID), eq("Nice post!"), isNull()))
				.thenReturn(Mono.just(buildCommentResponse()));

		graphqlPost().bodyValue(String.format(
				"{\"query\": \"mutation { addComment(postId: \\\"%s\\\", content: \\\"Nice post!\\\") { id content } }\"}",
				POST_ID)).exchange().expectStatus().isOk().expectBody().jsonPath("$.data.addComment.id")
				.isEqualTo(COMMENT_ID).jsonPath("$.data.addComment.content").isEqualTo("Nice post!");
	}

	@Test
	void add_comment_returns_updated_at() {
		when(postGrpcClient.addComment(eq(POST_ID), eq(USER_ID), eq("Nice post!"), isNull()))
				.thenReturn(Mono.just(buildCommentResponse()));

		graphqlPost().bodyValue(String.format(
				"{\"query\": \"mutation { addComment(postId: \\\"%s\\\", content: \\\"Nice post!\\\") { updatedAt } }\"}",
				POST_ID)).exchange().expectStatus().isOk().expectBody().jsonPath("$.data.addComment.updatedAt")
				.isEqualTo("2024-01-01T10:00:00");
	}

	@Test
	void add_comment_returns_graphql_error_without_token() {
		client.post().uri("/graphql").header("Content-Type", "application/json")
				.bodyValue(String.format(
						"{\"query\": \"mutation { addComment(postId: \\\"%s\\\", content: \\\"x\\\") { id } }\"}",
						POST_ID))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.errors").isArray();
	}

	@Test
	void add_comment_returns_graphql_error_when_post_not_found() {
		when(postGrpcClient.addComment(any(), any(), any(), any()))
				.thenReturn(Mono.error(io.grpc.Status.NOT_FOUND.asRuntimeException()));

		graphqlPost().bodyValue(String.format(
				"{\"query\": \"mutation { addComment(postId: \\\"%s\\\", content: \\\"x\\\") { id } }\"}", POST_ID))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.errors").isArray();
	}

	@Test
	void edit_comment_returns_updated_content() {
		CommentResponse edited = buildCommentResponse().toBuilder().setContent("edited text")
				.setUpdatedAt("2024-01-02T00:00:00").build();
		when(postGrpcClient.editComment(eq(COMMENT_ID), eq(USER_ID), eq("edited text"))).thenReturn(Mono.just(edited));

		graphqlPost().bodyValue(String.format(
				"{\"query\": \"mutation { editComment(commentId: \\\"%s\\\", content: \\\"edited text\\\") { content updatedAt } }\"}",
				COMMENT_ID)).exchange().expectStatus().isOk().expectBody().jsonPath("$.data.editComment.content")
				.isEqualTo("edited text").jsonPath("$.data.editComment.updatedAt").isEqualTo("2024-01-02T00:00:00");
	}

	@Test
	void edit_comment_returns_graphql_error_without_token() {
		client.post().uri("/graphql").header("Content-Type", "application/json")
				.bodyValue(String.format(
						"{\"query\": \"mutation { editComment(commentId: \\\"%s\\\", content: \\\"x\\\") { id } }\"}",
						COMMENT_ID))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.errors").isArray();
	}

	@Test
	void edit_comment_returns_graphql_error_when_not_author() {
		when(postGrpcClient.editComment(any(), any(), any())).thenReturn(Mono.error(io.grpc.Status.INVALID_ARGUMENT
				.withDescription("Cannot edit someone else's comment").asRuntimeException()));

		graphqlPost().bodyValue(String.format(
				"{\"query\": \"mutation { editComment(commentId: \\\"%s\\\", content: \\\"x\\\") { id } }\"}",
				COMMENT_ID)).exchange().expectStatus().isOk().expectBody().jsonPath("$.errors").isArray();
	}

	@Test
	void delete_comment_returns_success_true() {
		when(postGrpcClient.deleteComment(eq(COMMENT_ID), eq(USER_ID))).thenReturn(Mono.just(true));

		graphqlPost()
				.bodyValue(String.format(
						"{\"query\": \"mutation { deleteComment(commentId: \\\"%s\\\") { success } }\"}", COMMENT_ID))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.deleteComment.success").isEqualTo(true);
	}

	@Test
	void delete_comment_returns_graphql_error_without_token() {
		client.post().uri("/graphql").header("Content-Type", "application/json")
				.bodyValue(String.format(
						"{\"query\": \"mutation { deleteComment(commentId: \\\"%s\\\") { success } }\"}", COMMENT_ID))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.errors").isArray();
	}

	@Test
	void delete_comment_returns_graphql_error_when_not_found() {
		when(postGrpcClient.deleteComment(any(), any()))
				.thenReturn(Mono.error(io.grpc.Status.NOT_FOUND.asRuntimeException()));

		graphqlPost()
				.bodyValue(String.format(
						"{\"query\": \"mutation { deleteComment(commentId: \\\"%s\\\") { success } }\"}", COMMENT_ID))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.errors").isArray();
	}
}
