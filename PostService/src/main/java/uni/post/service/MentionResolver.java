package uni.post.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uni.post.grpc.UserGrpcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class MentionResolver {

	private static final Pattern MENTION_PATTERN = Pattern.compile("@([a-zA-Z0-9_]{1,50})");

	private final UserGrpcClient userGrpcClient;

	public List<String> resolveUserIds(String content) {
		if (content == null || content.isBlank())
			return List.of();
		Matcher matcher = MENTION_PATTERN.matcher(content);
		List<String> userIds = new ArrayList<>();
		while (matcher.find()) {
			String username = matcher.group(1);
			userGrpcClient.getUserIdByUsername(username).ifPresent(userIds::add);
		}
		return userIds;
	}
}
