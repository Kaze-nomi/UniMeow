package uni.gateway.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import uni.gateway.grpc.MediaGrpcClient;
import uni.gateway.grpc.UserGrpcClient;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class UploadController {

	private static final long MAX_FILE_SIZE = 10L * 1024 * 1024; // 10 MB
	private static final Set<String> ALLOWED_BUCKETS = Set.of("university-icons", "user-avatars", "user-banners",
			"post-media");

	private final MediaGrpcClient mediaGrpcClient;
	private final UserGrpcClient userGrpcClient;

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Mono<ResponseEntity<Map<String, String>>> upload(@RequestHeader("X-User-Id") String userId,
			@RequestParam(defaultValue = "post-media") String bucket, @RequestPart("file") FilePart filePart) {

		if (!ALLOWED_BUCKETS.contains(bucket)) {
			return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "Неподдерживаемый тип загрузки")));
		}

		return userGrpcClient.getUserById(userId).flatMap(user -> {
			if (user.getIsBanned()) {
				return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN)
						.<Map<String, String>>body(Map.of("error", "Аккаунт заблокирован")));
			}
			return uploadActiveUserFile(userId, bucket, filePart);
		}).onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.<Map<String, String>>body(Map.of("error", "Нужно войти в аккаунт"))));
	}

	private Mono<ResponseEntity<Map<String, String>>> uploadActiveUserFile(String userId, String bucket,
			FilePart filePart) {
		return DataBufferUtils.join(filePart.content()).flatMap(dataBuffer -> {
			byte[] bytes = new byte[dataBuffer.readableByteCount()];
			dataBuffer.read(bytes);
			DataBufferUtils.release(dataBuffer);

			if (bytes.length > MAX_FILE_SIZE) {
				return Mono.just(ResponseEntity.badRequest()
						.<Map<String, String>>body(Map.of("error", "Файл слишком большой (максимум 10 МБ)")));
			}

			String contentType = filePart.headers().getContentType() != null
					? filePart.headers().getContentType().toString()
					: "application/octet-stream";

			String ext = getExtension(filePart.filename());
			String filename = userId + "/" + UUID.randomUUID() + ext;

			return mediaGrpcClient.uploadFile(bucket, filename, contentType, bytes)
					.map(url -> ResponseEntity.ok(Map.of("url", url))).onErrorResume(e -> Mono
							.just(ResponseEntity.internalServerError().body(Map.of("error", "Ошибка загрузки файла"))));
		});
	}

	private String getExtension(String filename) {
		if (filename == null)
			return "";
		int dot = filename.lastIndexOf('.');
		return dot >= 0 ? filename.substring(dot).toLowerCase() : "";
	}
}
