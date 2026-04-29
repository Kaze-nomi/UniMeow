package uni.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class CookieUtil {

	// TODO: Изменить на true в проде
	@Value("${app.security.secure-cookie:false}")
	private boolean isSecure;

	// 15 минут
	public ResponseCookie createAccessTokenCookie(String token) {
		return createCookie("ACCESS_TOKEN", token, 15 * 60);
	}

	// 30 дней
	public ResponseCookie createRefreshTokenCookie(String token) {
		return createCookie("REFRESH_TOKEN", token, 30 * 24 * 60 * 60);
	}

	public ResponseCookie revokeCookie(String name) {
		return ResponseCookie.from(name, "").httpOnly(true).secure(isSecure).path("/").maxAge(0).sameSite("Lax")
				.build();
	}

	private ResponseCookie createCookie(String name, String value, long maxAgeSeconds) {
		return ResponseCookie.from(name, value).httpOnly(true).secure(isSecure).path("/").maxAge(maxAgeSeconds)
				.sameSite("Lax").build();
	}
}
