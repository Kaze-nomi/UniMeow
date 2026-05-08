package uni.gateway.exception;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
@Order(-2)
public class GatewayErrorWebExceptionHandler implements WebExceptionHandler {

	@Override
	public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
		if (exchange.getResponse().isCommitted()) {
			return Mono.error(ex);
		}

		HttpStatusCode status = statusOf(ex);
		exchange.getResponse().setStatusCode(status);

		String path = exchange.getRequest().getPath().value();
		boolean apiRequest = isApiPath(path);
		boolean html = !apiRequest && acceptsHtml(exchange);

		byte[] body = html ? htmlBody(status, path).getBytes(StandardCharsets.UTF_8)
				: jsonBody(status, path).getBytes(StandardCharsets.UTF_8);
		exchange.getResponse().getHeaders().setContentType(html ? MediaType.TEXT_HTML : MediaType.APPLICATION_JSON);
		return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
	}

	private static HttpStatusCode statusOf(Throwable ex) {
		if (ex instanceof ResponseStatusException responseStatusException) {
			return responseStatusException.getStatusCode();
		}
		return HttpStatus.INTERNAL_SERVER_ERROR;
	}

	private static boolean acceptsHtml(ServerWebExchange exchange) {
		var accepted = exchange.getRequest().getHeaders().getAccept();
		return accepted.isEmpty() || accepted.stream()
				.anyMatch(mediaType -> mediaType.includes(MediaType.TEXT_HTML) || mediaType.includes(MediaType.ALL));
	}

	private static boolean isApiPath(String path) {
		return path.equals("/graphql") || path.startsWith("/api") || path.startsWith("/oauth2/")
				|| path.startsWith("/login/oauth2/") || path.startsWith("/actuator") || path.startsWith("/graphiql");
	}

	private static String htmlBody(HttpStatusCode status, String path) {
		int code = status.value();
		String title = code == 404 ? "Страница не найдена" : "Ошибка сервиса";
		String text = code == 404 ? "Такой страницы нет или ссылка устарела."
				: "Сервис временно не смог обработать запрос.";
		return """
				<!doctype html>
				<html lang="ru">
				<head>
				  <meta charset="utf-8">
				  <meta name="viewport" content="width=device-width, initial-scale=1">
				  <title>%s</title>
				  <style>
				    :root { color-scheme: light; font-family: Inter, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
				    body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: #f7f8fb; color: #111827; }
				    main { width: min(520px, calc(100vw - 32px)); background: #fff; border: 1px solid #dfe3ea; border-radius: 16px; padding: 28px; box-shadow: 0 18px 50px rgba(17, 24, 39, .08); }
				    .code { font-size: 13px; font-weight: 800; letter-spacing: .08em; color: #64748b; text-transform: uppercase; }
				    h1 { margin: 10px 0 8px; font-size: 28px; line-height: 1.15; }
				    p { margin: 0; color: #475569; line-height: 1.55; }
				    .path { margin-top: 14px; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 13px; color: #64748b; word-break: break-all; }
				    a { display: inline-flex; margin-top: 22px; padding: 11px 16px; border-radius: 10px; background: #111827; color: white; text-decoration: none; font-weight: 700; }
				  </style>
				</head>
				<body>
				  <main>
				    <div class="code">Ошибка %d</div>
				    <h1>%s</h1>
				    <p>%s</p>
				    <div class="path">%s</div>
				    <a href="/">На главную</a>
				  </main>
				</body>
				</html>
				""".formatted(escapeHtml(title), code, escapeHtml(title), escapeHtml(text), escapeHtml(path));
	}

	private static String jsonBody(HttpStatusCode status, String path) {
		return "{\"status\":" + status.value() + ",\"error\":\"" + errorText(status) + "\",\"path\":\""
				+ escapeJson(path) + "\"}";
	}

	private static String errorText(HttpStatusCode status) {
		if (status.value() == 404) {
			return "Not Found";
		}
		if (status.value() == 401) {
			return "Unauthorized";
		}
		if (status.value() == 403) {
			return "Forbidden";
		}
		return "Service Error";
	}

	private static String escapeHtml(String value) {
		return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
				.replace("\"", "&quot;").replace("'", "&#39;");
	}

	private static String escapeJson(String value) {
		return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
