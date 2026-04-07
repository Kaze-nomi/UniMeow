package uni.gateway.grpc;

import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Service;
import uni.grpc.user.CreateOrGetUserRequest;
import uni.grpc.user.GetUserByIdRequest;
import uni.grpc.user.GetUserByUsernameRequest;
import uni.grpc.user.UpdateUserRequest;
import uni.grpc.user.UserResponse;
import uni.grpc.user.UserServiceGrpc;
import uni.grpc.user.CreateSessionRequest;
import uni.grpc.user.RefreshSessionRequest;
import uni.grpc.user.RefreshSessionResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Обёртка над gRPC-стабом для User Service.
 * Все вызовы оборачиваются в Mono + boundedElastic,
 * потому что BlockingStub блокирует поток,
 * а Gateway работает на WebFlux (event loop).
 * boundedElastic - отдельный пул потоков для блокирующих операций.
 */
@Service
public class UserGrpcClient {

    @GrpcClient("user-service")
    private UserServiceGrpc.UserServiceBlockingStub stub;

    public Mono<UserResponse> createOrGetUser(CreateOrGetUserRequest request) {
        return Mono.fromCallable(() -> stub.createOrGetUser(request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<UserResponse> getUserById(String id) {
        return Mono.fromCallable(() -> stub.getUserById(
                GetUserByIdRequest.newBuilder().setId(id).build()
        )).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<UserResponse> getUserByUsername(String username) {
        return Mono.fromCallable(() -> stub.getUserByUsername(
                GetUserByUsernameRequest.newBuilder().setUsername(username).build()
        )).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<UserResponse> updateUser(UpdateUserRequest request) {
        return Mono.fromCallable(() -> stub.updateUser(request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> createSession(String userId, String refreshToken) {
        return Mono.fromRunnable(() -> stub.createSession(
                CreateSessionRequest.newBuilder()
                        .setUserId(userId)
                        .setRefreshToken(refreshToken)
                        .setExpiresInDays(30)
                        .build()
        )).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public Mono<RefreshSessionResponse> refreshSession(String oldRefreshToken) {
        return Mono.fromCallable(() -> stub.refreshSession(
                RefreshSessionRequest.newBuilder()
                        .setOldRefreshToken(oldRefreshToken)
                        .build()
        )).subscribeOn(Schedulers.boundedElastic());
    }
}