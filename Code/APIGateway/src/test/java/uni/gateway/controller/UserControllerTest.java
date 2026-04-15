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

import static org.mockito.ArgumentMatchers.any;
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

    private static final String USER_ID  = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
    private static final String USERNAME = "ivan_petrov";

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    private String validToken() {
        return jwtUtil.generateToken(USER_ID);
    }

    private UserResponse defaultUserResponse() {
        return UserResponse.newBuilder()
                .setId(USER_ID)
                .setEmailGoogle("ivan@gmail.com")
                .setUsername(USERNAME)
                .setName("Иван")
                .setSurname("Петров")
                .setPatronymic("")
                .setEmailUniversity("")
                .setAvatarUrl("")
                .setStatus("")
                .setIsStudentVerified(false)
                .setIsEmployeeVerified(false)
                .setCreatedAt("2024-01-01T00:00:00")
                .build();
    }

    private WebTestClient.RequestBodySpec graphqlPost() {
        return client.post().uri("/graphql")
                .cookie("ACCESS_TOKEN", validToken())
                .header("Content-Type", "application/json");
    }

    @Test
    void me_returns_user_id() {
        when(userGrpcClient.getUserById(USER_ID))
                .thenReturn(Mono.just(defaultUserResponse()));

        graphqlPost()
                .bodyValue("{\"query\": \"{ me { id } }\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.me.id").isEqualTo(USER_ID);
    }

    @Test
    void me_returns_username() {
        when(userGrpcClient.getUserById(USER_ID))
                .thenReturn(Mono.just(defaultUserResponse()));

        graphqlPost()
                .bodyValue("{\"query\": \"{ me { username } }\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.me.username").isEqualTo(USERNAME);
    }

    @Test
    void me_returns_401_without_token() {
        client.post().uri("/graphql")
                .header("Content-Type", "application/json")
                .bodyValue("{\"query\": \"{ me { id } }\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void get_user_by_id_returns_correct_user() {
        when(userGrpcClient.getUserById(USER_ID))
                .thenReturn(Mono.just(defaultUserResponse()));

        graphqlPost()
                .bodyValue(String.format(
                        "{\"query\": \"{ getUser(id: \\\"%s\\\") { id username } }\"}",
                        USER_ID
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.getUser.id").isEqualTo(USER_ID)
                .jsonPath("$.data.getUser.username").isEqualTo(USERNAME);
    }

    @Test
    void get_user_by_id_returns_graphql_error_when_not_found() {
        when(userGrpcClient.getUserById(USER_ID))
                .thenReturn(Mono.error(
                        io.grpc.Status.NOT_FOUND.asRuntimeException()
                ));

        graphqlPost()
                .bodyValue(String.format(
                        "{\"query\": \"{ getUser(id: \\\"%s\\\") { id } }\"}",
                        USER_ID
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.errors").isArray();
    }

    @Test
    void get_user_by_username_returns_correct_user() {
        when(userGrpcClient.getUserByUsername(USERNAME))
                .thenReturn(Mono.just(defaultUserResponse()));

        graphqlPost()
                .bodyValue(String.format(
                        "{\"query\": \"{ getUserByUsername(username: \\\"%s\\\") { id username } }\"}",
                        USERNAME
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.getUserByUsername.username").isEqualTo(USERNAME);
    }

    @Test
    void get_user_by_username_returns_graphql_error_when_not_found() {
        when(userGrpcClient.getUserByUsername(USERNAME))
                .thenReturn(Mono.error(
                        io.grpc.Status.NOT_FOUND.asRuntimeException()
                ));

        graphqlPost()
                .bodyValue(String.format(
                        "{\"query\": \"{ getUserByUsername(username: \\\"%s\\\") { id } }\"}",
                        USERNAME
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.errors").isArray();
    }

    @Test
    void update_profile_returns_updated_username() {
        UserResponse updated = defaultUserResponse().toBuilder()
                .setUsername("new_username")
                .build();

        when(userGrpcClient.updateUser(any()))
                .thenReturn(Mono.just(updated));

        graphqlPost()
                .bodyValue("{\"query\": \"mutation { updateProfile(input: { username: \\\"new_username\\\" }) { username } }\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.updateProfile.username").isEqualTo("new_username");
    }

    @Test
    void update_profile_returns_graphql_error_when_username_taken() {
        when(userGrpcClient.updateUser(any()))
                .thenReturn(Mono.error(
                        io.grpc.Status.ALREADY_EXISTS.asRuntimeException()
                ));

        graphqlPost()
                .bodyValue("{\"query\": \"mutation { updateProfile(input: { username: \\\"taken\\\" }) { id } }\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.errors").isArray();
    }
}