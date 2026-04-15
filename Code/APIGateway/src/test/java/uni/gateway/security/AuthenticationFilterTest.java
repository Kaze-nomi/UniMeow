package uni.gateway.security;

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
import uni.grpc.user.UserResponse;

import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthenticationFilterTest {

    @LocalServerPort
    int port;

    @MockitoBean
    UserGrpcClient userGrpcClient;

    @Autowired
    JwtUtil jwtUtil;

    WebTestClient client;

    private static final String USER_ID = "f47ac10b-58cc-4372-a567-0e02b2c3d479";

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void request_without_cookie_returns_401() {
        client.post().uri("/graphql")
                .header("Content-Type", "application/json")
                .bodyValue("{\"query\": \"{ me { id } }\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void request_with_invalid_token_returns_401() {
        client.post().uri("/graphql")
                .cookie("ACCESS_TOKEN", "totally.wrong.token")
                .header("Content-Type", "application/json")
                .bodyValue("{\"query\": \"{ me { id } }\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void request_with_expired_token_returns_401() {
        String expired = io.jsonwebtoken.Jwts.builder()
                .subject(USER_ID)
                .issuedAt(new java.util.Date(0))
                .expiration(new java.util.Date(1))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        io.jsonwebtoken.io.Decoders.BASE64.decode(
                                "dGVzdFNlY3JldEtleUprivetprivetprivetprivetMaksimZvckpXVFRlc3RpbmcxMjM="
                        )
                ))
                .compact();

        client.post().uri("/graphql")
                .cookie("ACCESS_TOKEN", expired)
                .header("Content-Type", "application/json")
                .bodyValue("{\"query\": \"{ me { id } }\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void error_response_contains_json_error_field() {
        client.post().uri("/graphql")
                .header("Content-Type", "application/json")
                .bodyValue("{\"query\": \"{ me { id } }\"}")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.errors").exists()
                .jsonPath("$.errors[0].extensions.httpStatus").isEqualTo(401);
    }

    @Test
    void oauth2_endpoint_is_accessible_without_token() {
        client.get().uri("/oauth2/authorization/google")
                .exchange()
                .expectStatus().value(status ->
                        org.assertj.core.api.Assertions
                                .assertThat(status).isNotEqualTo(401)
                );
    }

    @Test
    void refresh_endpoint_is_accessible_without_token() {
        client.post().uri("/api/auth/refresh")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void valid_token_passes_filter() {
        UserResponse grpcResponse = UserResponse.newBuilder()
                .setId(USER_ID)
                .setEmailGoogle("test@gmail.com")
                .setUsername("test_user")
                .setName("Test")
                .setCreatedAt("2024-01-01T00:00:00")
                .build();

        when(userGrpcClient.getUserById(USER_ID))
                .thenReturn(Mono.just(grpcResponse));

        client.post().uri("/graphql")
                .cookie("ACCESS_TOKEN", jwtUtil.generateToken(USER_ID))
                .header("Content-Type", "application/json")
                .bodyValue("{\"query\": \"{ me { id } }\"}")
                .exchange()
                .expectStatus().value(status ->
                        org.assertj.core.api.Assertions
                                .assertThat(status).isNotEqualTo(401)
                );
    }

    @Test
    void filter_order_is_minus_one() {
        AuthenticationFilter filter = new AuthenticationFilter(jwtUtil);
        org.assertj.core.api.Assertions.assertThat(filter.getOrder()).isEqualTo(-1);
    }
}