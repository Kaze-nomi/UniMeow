package uni.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import uni.gateway.grpc.UserGrpcClient;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class JwtUtilTest {

    @MockitoBean
    UserGrpcClient userGrpcClient;

    @Autowired
    JwtUtil jwtUtil;

    @Test
    void generated_token_has_three_parts() {
        String token = jwtUtil.generateToken("user-123");

        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void fresh_token_is_valid() {
        String token = jwtUtil.generateToken("user-123");

        assertThat(jwtUtil.isValid(token)).isTrue();
    }

    @Test
    void random_string_is_not_valid() {
        assertThat(jwtUtil.isValid("not.a.token")).isFalse();
    }

    @Test
    void null_token_returns_false_without_exception() {
        assertThatCode(() -> jwtUtil.isValid(null))
                .doesNotThrowAnyException();

        assertThat(jwtUtil.isValid(null)).isFalse();
    }

    @Test
    void tampered_token_is_not_valid() {
        String token = jwtUtil.generateToken("user-123");
        String[] parts = token.split("\\.");

        String tampered = parts[0] + ".AAAAAAAAAAAAAAAAAAAAAA." + parts[2];

        assertThat(jwtUtil.isValid(tampered)).isFalse();
    }

    @Test
    void expired_token_is_not_valid() {
        String expired = io.jsonwebtoken.Jwts.builder()
                .subject("user-123")
                .issuedAt(new java.util.Date(0))
                .expiration(new java.util.Date(1))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        io.jsonwebtoken.io.Decoders.BASE64.decode(
                                "dGVzdFNlY3JlddGVzdFNlY3JldEtleUZvckpXVFRlc3RpbmcxMjMEtleUZvckpXVFRlc3RpbmcxMjM="
                        )
                ))
                .compact();

        assertThat(jwtUtil.isValid(expired)).isFalse();
    }

    @Test
    void extract_user_id_returns_correct_value() {
        String userId = "550e8400-e29b-41d4-a716-446655440000";
        String token = jwtUtil.generateToken(userId);

        assertThat(jwtUtil.extractUserId(token)).isEqualTo(userId);
    }

    @Test
    void extract_user_id_throws_on_invalid_token() {
        assertThatThrownBy(() -> jwtUtil.extractUserId("bad.token.here"))
                .isInstanceOf(Exception.class);
    }
}