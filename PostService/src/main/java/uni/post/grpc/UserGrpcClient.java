package uni.post.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Service;
import uni.grpc.user.GetUserByUsernameRequest;
import uni.grpc.user.UserResponse;
import uni.grpc.user.UserServiceGrpc;

import java.util.Optional;

@Service
@Slf4j
public class UserGrpcClient {

	@GrpcClient("user-service")
	private UserServiceGrpc.UserServiceBlockingStub stub;

	public Optional<String> getUserIdByUsername(String username) {
		try {
			UserResponse response = stub
					.getUserByUsername(GetUserByUsernameRequest.newBuilder().setUsername(username).build());
			return Optional.of(response.getId());
		} catch (StatusRuntimeException e) {
			if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
				return Optional.empty();
			}
			log.warn("Failed to resolve username '{}' to userId: {}", username, e.getMessage());
			return Optional.empty();
		}
	}
}
