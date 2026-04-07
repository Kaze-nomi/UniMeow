package uni.gateway.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import uni.gateway.dto.UpdateProfileInput;
import uni.gateway.dto.UserDto;
import uni.gateway.grpc.UserGrpcClient;
import uni.grpc.user.UpdateUserRequest;
import uni.grpc.user.UserResponse;
import reactor.core.publisher.Mono;

@Controller
@RequiredArgsConstructor
public class UserController {

    private final UserGrpcClient userGrpcClient;

    @QueryMapping
    public Mono<UserDto> me(@ContextValue(required = false) String userId) {
        if (userId == null) {
            return Mono.error(new RuntimeException("Authentication required"));
        }
        return userGrpcClient.getUserById(userId).map(this::toDto);
    }

    @QueryMapping
    public Mono<UserDto> getUser(@Argument String id) {
        return userGrpcClient.getUserById(id).map(this::toDto);
    }

    @QueryMapping
    public Mono<UserDto> getUserByUsername(@Argument String username) {
        return userGrpcClient.getUserByUsername(username).map(this::toDto);
    }

    @MutationMapping
    public Mono<UserDto> updateProfile(
            @Argument UpdateProfileInput input,
            @ContextValue(required = false) String userId
    ) {
        if (userId == null) {
            return Mono.error(new RuntimeException("Authentication required"));
        }

        UpdateUserRequest.Builder builder = UpdateUserRequest.newBuilder().setId(userId);

        if (input.username() != null) builder.setUsername(input.username());
        if (input.name() != null) builder.setName(input.name());
        if (input.surname() != null) builder.setSurname(input.surname());
        if (input.patronymic() != null) builder.setPatronymic(input.patronymic());
        if (input.status() != null) builder.setStatus(input.status());
        if (input.avatarUrl() != null) builder.setAvatarUrl(input.avatarUrl());

        return userGrpcClient.updateUser(builder.build()).map(this::toDto);
    }

    private UserDto toDto(UserResponse r) {
        return UserDto.builder()
                .id(r.getId())
                .emailGoogle(r.getEmailGoogle())
                .username(r.getUsername())
                .name(r.getName())
                .surname(r.getSurname().isEmpty() ? null : r.getSurname())
                .patronymic(r.getPatronymic().isEmpty() ? null : r.getPatronymic())
                .emailUniversity(r.getEmailUniversity().isEmpty() ? null : r.getEmailUniversity())
                .avatarUrl(r.getAvatarUrl().isEmpty() ? null : r.getAvatarUrl())
                .status(r.getStatus().isEmpty() ? null : r.getStatus())
                .isStudentVerified(r.getIsStudentVerified())
                .isEmployeeVerified(r.getIsEmployeeVerified())
                .createdAt(r.getCreatedAt())
                .build();
    }
}