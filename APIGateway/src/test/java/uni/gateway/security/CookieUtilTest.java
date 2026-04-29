package uni.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import uni.gateway.grpc.UserGrpcClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class CookieUtilTest {

	@MockitoBean
	UserGrpcClient userGrpcClient;

	@Autowired
	CookieUtil cookieUtil;

	@Test
	void access_cookie_has_correct_name() {
		assertThat(cookieUtil.createAccessTokenCookie("tok").getName()).isEqualTo("ACCESS_TOKEN");
	}

	@Test
	void access_cookie_has_correct_value() {
		assertThat(cookieUtil.createAccessTokenCookie("my-token").getValue()).isEqualTo("my-token");
	}

	@Test
	void access_cookie_max_age_is_15_minutes() {
		assertThat(cookieUtil.createAccessTokenCookie("tok").getMaxAge()).isEqualTo(Duration.ofMinutes(15));
	}

	@Test
	void access_cookie_is_http_only() {
		assertThat(cookieUtil.createAccessTokenCookie("tok").isHttpOnly()).isTrue();
	}

	@Test
	void access_cookie_same_site_is_lax() {
		assertThat(cookieUtil.createAccessTokenCookie("tok").getSameSite()).isEqualTo("Lax");
	}

	@Test
	void access_cookie_path_is_root() {
		assertThat(cookieUtil.createAccessTokenCookie("tok").getPath()).isEqualTo("/");
	}

	@Test
	void access_cookie_is_not_secure_in_dev_mode() {
		assertThat(cookieUtil.createAccessTokenCookie("tok").isSecure()).isFalse();
	}

	@Test
	void refresh_cookie_has_correct_name() {
		assertThat(cookieUtil.createRefreshTokenCookie("uuid").getName()).isEqualTo("REFRESH_TOKEN");
	}

	@Test
	void refresh_cookie_max_age_is_30_days() {
		assertThat(cookieUtil.createRefreshTokenCookie("uuid").getMaxAge()).isEqualTo(Duration.ofDays(30));
	}

	@Test
	void refresh_cookie_is_http_only() {
		assertThat(cookieUtil.createRefreshTokenCookie("uuid").isHttpOnly()).isTrue();
	}

	@Test
	void revoke_cookie_has_empty_value() {
		assertThat(cookieUtil.revokeCookie("ACCESS_TOKEN").getValue()).isEmpty();
	}

	@Test
	void revoke_cookie_max_age_is_zero() {
		assertThat(cookieUtil.revokeCookie("ACCESS_TOKEN").getMaxAge()).isEqualTo(Duration.ZERO);
	}

	@Test
	void revoke_cookie_preserves_name() {
		assertThat(cookieUtil.revokeCookie("REFRESH_TOKEN").getName()).isEqualTo("REFRESH_TOKEN");
	}

	@Test
	void revoke_cookie_is_still_http_only() {
		assertThat(cookieUtil.revokeCookie("ACCESS_TOKEN").isHttpOnly()).isTrue();
	}
}
