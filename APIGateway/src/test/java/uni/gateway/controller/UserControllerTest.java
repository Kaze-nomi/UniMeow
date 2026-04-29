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
import uni.gateway.grpc.UserGrpcClient;
import uni.gateway.security.JwtUtil;
import uni.grpc.user.UserResponse;

import uni.grpc.user.Faculty;
import uni.grpc.user.FacultyListResponse;
import uni.grpc.user.Topic;
import uni.grpc.user.TopicListResponse;
import uni.grpc.user.University;
import uni.grpc.user.UniversityListResponse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UserControllerTest {

	@LocalServerPort
	int port;

	@MockitoBean
	UserGrpcClient userGrpcClient;

	@Autowired
	JwtUtil jwtUtil;

	WebTestClient client;

	private static final String USER_ID = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
	private static final String USERNAME = "ivan_petrov";

	@BeforeEach
	void setUp() {
		client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
	}

	private String validToken() {
		return jwtUtil.generateToken(USER_ID);
	}

	private UserResponse defaultUserResponse() {
		return UserResponse.newBuilder().setId(USER_ID).setEmailGoogle("ivan@gmail.com").setUsername(USERNAME)
				.setName("Иван").setSurname("Петров").setPatronymic("").setEmailUniversity("").setAvatarUrl("")
				.setStatus("").setIsStudentVerified(false).setIsEmployeeVerified(false)
				.setCreatedAt("2024-01-01T00:00:00").build();
	}

	private WebTestClient.RequestBodySpec graphqlPost() {
		return client.post().uri("/graphql").cookie("ACCESS_TOKEN", validToken()).header("Content-Type",
				"application/json");
	}

	@Test
	void me_returns_user_id() {
		when(userGrpcClient.getUserById(USER_ID)).thenReturn(Mono.just(defaultUserResponse()));

		graphqlPost().bodyValue("{\"query\": \"{ me { id } }\"}").exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.me.id").isEqualTo(USER_ID);
	}

	@Test
	void me_returns_username() {
		when(userGrpcClient.getUserById(USER_ID)).thenReturn(Mono.just(defaultUserResponse()));

		graphqlPost().bodyValue("{\"query\": \"{ me { username } }\"}").exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.me.username").isEqualTo(USERNAME);
	}

	@Test
	void me_returns_graphql_error_without_token() {
		client.post().uri("/graphql").header("Content-Type", "application/json")
				.bodyValue("{\"query\": \"{ me { id } }\"}").exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.errors").isNotEmpty();
	}

	@Test
	void get_user_by_id_returns_correct_user() {
		when(userGrpcClient.getUserById(USER_ID)).thenReturn(Mono.just(defaultUserResponse()));

		graphqlPost().bodyValue(String.format("{\"query\": \"{ getUser(id: \\\"%s\\\") { id username } }\"}", USER_ID))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.getUser.id").isEqualTo(USER_ID)
				.jsonPath("$.data.getUser.username").isEqualTo(USERNAME);
	}

	@Test
	void get_user_by_id_returns_graphql_error_when_not_found() {
		when(userGrpcClient.getUserById(USER_ID)).thenReturn(Mono.error(io.grpc.Status.NOT_FOUND.asRuntimeException()));

		graphqlPost().bodyValue(String.format("{\"query\": \"{ getUser(id: \\\"%s\\\") { id } }\"}", USER_ID))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.errors").isArray();
	}

	@Test
	void get_user_by_username_returns_correct_user() {
		when(userGrpcClient.getUserByUsername(USERNAME)).thenReturn(Mono.just(defaultUserResponse()));

		graphqlPost()
				.bodyValue(String.format("{\"query\": \"{ getUserByUsername(username: \\\"%s\\\") { id username } }\"}",
						USERNAME))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.getUserByUsername.username")
				.isEqualTo(USERNAME);
	}

	@Test
	void get_user_by_username_returns_graphql_error_when_not_found() {
		when(userGrpcClient.getUserByUsername(USERNAME))
				.thenReturn(Mono.error(io.grpc.Status.NOT_FOUND.asRuntimeException()));

		graphqlPost()
				.bodyValue(
						String.format("{\"query\": \"{ getUserByUsername(username: \\\"%s\\\") { id } }\"}", USERNAME))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.errors").isArray();
	}

	@Test
	void update_profile_returns_updated_username() {
		UserResponse updated = defaultUserResponse().toBuilder().setUsername("new_username").build();

		when(userGrpcClient.updateUser(any())).thenReturn(Mono.just(updated));

		graphqlPost().bodyValue(
				"{\"query\": \"mutation { updateProfile(input: { username: \\\"new_username\\\" }) { username } }\"}")
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.updateProfile.username")
				.isEqualTo("new_username");
	}

	@Test
	void list_universities_returns_list() {
		UniversityListResponse resp = UniversityListResponse.newBuilder()
				.addUniversities(University.newBuilder().setId(1L).setName("МГУ им. Ломоносова").setShortName("МГУ")
						.setIconUrl("").build())
				.addUniversities(
						University.newBuilder().setId(2L).setName("СПбГУ").setShortName("СПбГУ").setIconUrl("").build())
				.build();
		when(userGrpcClient.listUniversities()).thenReturn(reactor.core.publisher.Mono.just(resp));

		graphqlPost().bodyValue("{\"query\": \"{ listUniversities { id name shortName } }\"}").exchange().expectStatus()
				.isOk().expectBody().jsonPath("$.data.listUniversities").isArray()
				.jsonPath("$.data.listUniversities[0].name").isEqualTo("МГУ им. Ломоносова")
				.jsonPath("$.data.listUniversities[1].shortName").isEqualTo("СПбГУ");
	}

	@Test
	void list_universities_returns_empty_list_when_none() {
		when(userGrpcClient.listUniversities())
				.thenReturn(reactor.core.publisher.Mono.just(UniversityListResponse.newBuilder().build()));

		graphqlPost().bodyValue("{\"query\": \"{ listUniversities { id } }\"}").exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.listUniversities").isArray().jsonPath("$.data.listUniversities.length()")
				.isEqualTo(0);
	}

	@Test
	void list_faculties_returns_faculties_for_university() {
		FacultyListResponse resp = FacultyListResponse.newBuilder()
				.addFaculties(Faculty.newBuilder().setId(10L).setName("Факультет ВМК").setShortName("ВМК").build())
				.build();
		when(userGrpcClient.listFaculties(anyLong())).thenReturn(reactor.core.publisher.Mono.just(resp));

		graphqlPost().bodyValue("{\"query\": \"{ listFaculties(universityId: \\\"1\\\") { id name shortName } }\"}")
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.listFaculties[0].name")
				.isEqualTo("Факультет ВМК").jsonPath("$.data.listFaculties[0].shortName").isEqualTo("ВМК");
	}

	@Test
	void list_faculties_returns_graphql_error_for_invalid_id() {
		graphqlPost().bodyValue("{\"query\": \"{ listFaculties(universityId: \\\"abc\\\") { id } }\"}").exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.errors").isArray();
	}

	@Test
	void list_topics_returns_topic_tree_with_subtopics() {
		TopicListResponse resp = TopicListResponse.newBuilder()
				.addTopics(Topic.newBuilder().setId(1L).setSlug("general").setName("Общее").setIsSystem(true).build())
				.addTopics(Topic.newBuilder().setId(2L).setSlug("vmk").setName("ВМК").setIsSystem(false).build())
				.addTopics(Topic.newBuilder().setId(3L).setSlug("algos").setName("Алгоритмы").setIsSystem(false)
						.setParentId(2L).build())
				.build();
		when(userGrpcClient.listTopics(anyLong())).thenReturn(reactor.core.publisher.Mono.just(resp));

		graphqlPost().bodyValue(
				"{\"query\": \"{ listTopics(universityId: \\\"1\\\") { id name isSystem subtopics { id name } } }\"}")
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.listTopics").isArray()
				.jsonPath("$.data.listTopics.length()").isEqualTo(2).jsonPath("$.data.listTopics[1].name")
				.isEqualTo("ВМК").jsonPath("$.data.listTopics[1].subtopics[0].name").isEqualTo("Алгоритмы");
	}

	@Test
	void list_topics_returns_graphql_error_for_invalid_id() {
		graphqlPost().bodyValue("{\"query\": \"{ listTopics(universityId: \\\"xyz\\\") { id } }\"}").exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.errors").isArray();
	}

	@Test
	void update_profile_returns_graphql_error_when_username_taken() {
		when(userGrpcClient.updateUser(any()))
				.thenReturn(Mono.error(io.grpc.Status.ALREADY_EXISTS.asRuntimeException()));

		graphqlPost()
				.bodyValue("{\"query\": \"mutation { updateProfile(input: { username: \\\"taken\\\" }) { id } }\"}")
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.errors").isArray();
	}
}
