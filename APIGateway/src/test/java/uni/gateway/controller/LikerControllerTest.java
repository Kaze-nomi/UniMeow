package uni.gateway.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import uni.gateway.grpc.PostGrpcClient;
import uni.gateway.grpc.UserGrpcClient;
import uni.grpc.post.LikerListResponse;
import uni.grpc.user.UserListResponse;
import uni.grpc.user.UserResponse;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LikerControllerTest {

	@LocalServerPort
	int port;

	@MockitoBean
	PostGrpcClient postGrpcClient;

	@MockitoBean
	UserGrpcClient userGrpcClient;

	WebTestClient client;

	private static final String POST_ID = "550e8400-e29b-41d4-a716-446655440001";
	private static final String COMMENT_ID = "550e8400-e29b-41d4-a716-446655440002";
	private static final String FIRST_USER = "550e8400-e29b-41d4-a716-446655440003";
	private static final String SECOND_USER = "550e8400-e29b-41d4-a716-446655440004";
	private static final String DELETED_USER = "550e8400-e29b-41d4-a716-446655440005";

	@BeforeEach
	void setUp() {
		client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
	}

	@Test
	void post_likers_are_batched_and_keep_like_order() {
		List<String> ids = List.of(FIRST_USER, SECOND_USER);
		when(postGrpcClient.getPostLikers(POST_ID, 0, 20))
				.thenReturn(Mono.just(LikerListResponse.newBuilder().addAllUserIds(ids).setTotal(2).build()));
		when(userGrpcClient.getUsersByIds(ids)).thenReturn(Mono.just(UserListResponse.newBuilder()
				.addUsers(UserResponse.newBuilder().setId(SECOND_USER).setName("Second"))
				.addUsers(UserResponse.newBuilder().setId(FIRST_USER).setName("First").setUsername("first")).build()));

		client.post().uri("/graphql")
				.bodyValue(Map.of("query",
						"{ getPostLikers(postId: \"" + POST_ID
								+ "\") { users { id name username avatarUrl } total } }"))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.errors").doesNotExist()
				.jsonPath("$.data.getPostLikers.users[0].id").isEqualTo(FIRST_USER)
				.jsonPath("$.data.getPostLikers.users[1].id").isEqualTo(SECOND_USER)
				.jsonPath("$.data.getPostLikers.total").isEqualTo(2);

		verify(userGrpcClient).getUsersByIds(ids);
		verify(userGrpcClient, never()).getUserById(anyString());
	}

	@Test
	void comment_likers_pass_pagination_and_omit_deleted_users() {
		List<String> ids = List.of(DELETED_USER, FIRST_USER);
		when(postGrpcClient.getCommentLikers(COMMENT_ID, 2, 10))
				.thenReturn(Mono.just(LikerListResponse.newBuilder().addAllUserIds(ids).setTotal(22).build()));
		when(userGrpcClient.getUsersByIds(ids)).thenReturn(Mono.just(UserListResponse.newBuilder()
				.addUsers(UserResponse.newBuilder().setId(FIRST_USER).setName("First")).build()));

		client.post().uri("/graphql")
				.bodyValue(Map.of("query",
						"{ getCommentLikers(commentId: \"" + COMMENT_ID
								+ "\", page: 2, size: 10) { users { id name } total } }"))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.errors").doesNotExist()
				.jsonPath("$.data.getCommentLikers.users.length()").isEqualTo(1)
				.jsonPath("$.data.getCommentLikers.users[0].id").isEqualTo(FIRST_USER)
				.jsonPath("$.data.getCommentLikers.total").isEqualTo(22);

		verify(userGrpcClient).getUsersByIds(ids);
		verify(userGrpcClient, never()).getUserById(anyString());
	}

	@Test
	void liker_queries_do_not_expose_private_profile_fields() {
		for (String query : List.of("getPostLikers(postId: \"" + POST_ID + "\")",
				"getCommentLikers(commentId: \"" + COMMENT_ID + "\")")) {
			client.post().uri("/graphql")
					.bodyValue(Map.of("query", "{ " + query + " { users { emailGoogle emailUniversity } } }"))
					.exchange().expectStatus().isOk().expectBody().jsonPath("$.errors").isNotEmpty().jsonPath("$.data")
					.doesNotExist();
		}

		verifyNoInteractions(postGrpcClient, userGrpcClient);
	}

	@Test
	void empty_likers_do_not_load_users() {
		when(postGrpcClient.getPostLikers(POST_ID, 0, 20))
				.thenReturn(Mono.just(LikerListResponse.getDefaultInstance()));

		client.post().uri("/graphql")
				.bodyValue(Map.of("query", "{ getPostLikers(postId: \"" + POST_ID + "\") { users { id } total } }"))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.errors").doesNotExist()
				.jsonPath("$.data.getPostLikers.users").isEmpty().jsonPath("$.data.getPostLikers.total").isEqualTo(0);

		verifyNoInteractions(userGrpcClient);
	}
}
