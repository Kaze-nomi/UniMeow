package uni.user.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;
import uni.grpc.user.*;
import uni.user.entity.User;
import uni.user.exception.*;
import uni.user.service.*;

import java.util.UUID;

@GrpcService
@RequiredArgsConstructor
public class UserGrpcServer extends UserServiceGrpc.UserServiceImplBase {

    private final UserService userService;
    private final SessionService sessionService;
    private final VerificationService verificationService;

    @Override
    public void createOrGetUser(
            CreateOrGetUserRequest req,
            StreamObserver<UserResponse> obs) {
        try {
            User user = userService.createOrGet(
                    req.getEmailGoogle(),
                    req.getName(),
                    req.getSurname(),
                    req.getAvatarUrl());
            obs.onNext(toProto(user));
            obs.onCompleted();
        } catch (Exception e) {
            obs.onError(Status.INTERNAL
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void getUserById(
            GetUserByIdRequest req,
            StreamObserver<UserResponse> obs) {
        try {
            User user = userService.getById(UUID.fromString(req.getId()));
            obs.onNext(toProto(user));
            obs.onCompleted();
        } catch (UserNotFoundException e) {
            obs.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        } catch (IllegalArgumentException e) {
            obs.onError(Status.INVALID_ARGUMENT.withDescription("Invalid UUID").asRuntimeException());
        }
    }

    @Override
    public void getUserByUsername(
            GetUserByUsernameRequest req,
            StreamObserver<UserResponse> obs) {
        try {
            User user = userService.getByUsername(req.getUsername());
            obs.onNext(toProto(user));
            obs.onCompleted();
        } catch (UserNotFoundException e) {
            obs.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void updateUser(
            UpdateUserRequest req,
            StreamObserver<UserResponse> obs) {
        try {
            User user = userService.update(
                    UUID.fromString(req.getId()),
                    req.hasUsername(), req.getUsername(),
                    req.hasName(), req.getName(),
                    req.hasSurname(), req.getSurname(),
                    req.hasPatronymic(), req.getPatronymic(),
                    req.hasStatus(), req.getStatus(),
                    req.hasAvatarUrl(), req.getAvatarUrl());
            obs.onNext(toProto(user));
            obs.onCompleted();
        } catch (UserNotFoundException e) {
            obs.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        } catch (UsernameAlreadyTakenException e) {
            obs.onError(Status.ALREADY_EXISTS.withDescription(e.getMessage()).asRuntimeException());
        } catch (IllegalArgumentException e) {
            obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void createSession(
            CreateSessionRequest req,
            StreamObserver<CreateSessionResponse> obs) {
        try {
            sessionService.createSession(
                    UUID.fromString(req.getUserId()),
                    req.getRefreshToken(),
                    req.getExpiresInDays());
            obs.onNext(CreateSessionResponse.newBuilder().setSuccess(true).build());
            obs.onCompleted();
        } catch (Exception e) {
            obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void refreshSession(
            RefreshSessionRequest req,
            StreamObserver<RefreshSessionResponse> obs) {
        try {
            String[] result = sessionService.rotateRefreshToken(req.getOldRefreshToken());
            obs.onNext(RefreshSessionResponse.newBuilder()
                    .setUserId(result[0])
                    .setNewRefreshToken(result[1])
                    .build());
            obs.onCompleted();
        } catch (SessionExpiredException e) {
            obs.onError(Status.UNAUTHENTICATED.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void revokeRefreshToken(
            RevokeRefreshTokenRequest req,
            StreamObserver<RevokeRefreshTokenResponse> obs) {
        try {
            sessionService.revokeToken(req.getRefreshToken());
            obs.onNext(RevokeRefreshTokenResponse.newBuilder().setSuccess(true).build());
            obs.onCompleted();
        } catch (Exception e) {
            obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void sendVerificationCode(
            SendVerificationCodeRequest req,
            StreamObserver<SendVerificationCodeResponse> obs) {
        try {
            verificationService.sendCode(
                    UUID.fromString(req.getUserId()),
                    req.getUniversityEmail());
            obs.onNext(SendVerificationCodeResponse.newBuilder().setSuccess(true).build());
            obs.onCompleted();
        } catch (UnknownDomainException e) {
            obs.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (EmailAlreadyUsedException e) {
            obs.onError(Status.ALREADY_EXISTS
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (UserNotFoundException e) {
            obs.onError(Status.NOT_FOUND
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (MailDeliveryException e) {
            obs.onError(Status.UNAVAILABLE
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (Exception e) {
            obs.onError(Status.INTERNAL
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void verifyEmailCode(
            VerifyEmailCodeRequest req,
            StreamObserver<VerifyEmailCodeResponse> obs) {
        try {
            String result = verificationService.verify(
                    UUID.fromString(req.getUserId()),
                    req.getCode());
            VerifyEmailCodeResponse.Builder response = VerifyEmailCodeResponse.newBuilder();
            if ("SUCCESS".equals(result)) {
                response.setSuccess(true);
            } else {
                response.setSuccess(false).setError(result);
            }
            obs.onNext(response.build());
            obs.onCompleted();
        } catch (Exception e) {
            obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void subscribe(
            SubscribeRequest req,
            StreamObserver<SubscribeResponse> obs) {
        try {
            userService.subscribe(
                    UUID.fromString(req.getSubscriberId()),
                    UUID.fromString(req.getTargetUserId()));
            obs.onNext(SubscribeResponse.newBuilder().setSuccess(true).build());
            obs.onCompleted();
        } catch (IllegalArgumentException e) {
            obs.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (UserNotFoundException e) {
            obs.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void unsubscribe(
            UnsubscribeRequest req,
            StreamObserver<UnsubscribeResponse> obs) {
        try {
            userService.unsubscribe(
                    UUID.fromString(req.getSubscriberId()),
                    UUID.fromString(req.getTargetUserId()));
            obs.onNext(UnsubscribeResponse.newBuilder().setSuccess(true).build());
            obs.onCompleted();
        } catch (Exception e) {
            obs.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    private UserResponse toProto(User u) {
        return UserResponse.newBuilder()
                .setId(u.getId().toString())
                .setEmailGoogle(u.getEmailGoogle())
                .setUsername(u.getUsername() != null ? u.getUsername() : "")
                .setName(u.getName())
                .setSurname(u.getSurname() != null ? u.getSurname() : "")
                .setPatronymic(u.getPatronymic() != null ? u.getPatronymic() : "")
                .setEmailUniversity(u.getEmailUniversity() != null ? u.getEmailUniversity() : "")
                .setAvatarUrl(u.getAvatarUrl() != null ? u.getAvatarUrl() : "")
                .setStatus(u.getStatus() != null ? u.getStatus() : "")
                .setIsStudentVerified(u.isStudentVerified())
                .setIsEmployeeVerified(u.isEmployeeVerified())
                .setCreatedAt(u.getCreatedAt().toString())
                .build();
    }
}