package uni.gateway.exception;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.web.bind.annotation.ControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.security.auth.login.CredentialException;

@ControllerAdvice
public class GraphQlExceptionAdvice {

	@GraphQlExceptionHandler(CredentialException.class)
	public GraphQLError handleAuthRequired(CredentialException ex, DataFetchingEnvironment env) {
		return build(env, "Authentication required", ErrorType.UNAUTHORIZED, 401, "UNAUTHORIZED", null);
	}

	@GraphQlExceptionHandler(StatusRuntimeException.class)
	public GraphQLError handleGrpc(StatusRuntimeException ex, DataFetchingEnvironment env) {
		Status status = ex.getStatus();
		Status.Code grpc = status.getCode();
		String description = status.getDescription();

		String msg = (description != null && !description.isBlank()) ? description : defaultMessage(grpc);

		Mapped mapped = map(grpc);

		return build(env, msg, mapped.errorType, mapped.httpStatus, mapped.code, grpc.name());
	}

	@GraphQlExceptionHandler(IllegalArgumentException.class)
	public GraphQLError handleBadInput(IllegalArgumentException ex, DataFetchingEnvironment env) {
		return build(env, ex.getMessage() != null ? ex.getMessage() : "Invalid input", ErrorType.BAD_REQUEST, 400,
				"BAD_REQUEST", null);
	}

	@GraphQlExceptionHandler(Exception.class)
	public GraphQLError handleAny(Exception ex, DataFetchingEnvironment env) {
		return build(env, "Internal error", ErrorType.INTERNAL_ERROR, 500, "INTERNAL_ERROR", null);
	}

	private GraphQLError build(DataFetchingEnvironment env, String message, ErrorType errorType, int httpStatus,
			String code, String grpcStatus) {
		Map<String, Object> ext = new LinkedHashMap<>();
		ext.put("code", code);
		ext.put("httpStatus", httpStatus);
		if (grpcStatus != null)
			ext.put("grpcStatus", grpcStatus);

		return GraphqlErrorBuilder.newError(env).message(message).errorType(errorType).extensions(ext).build();
	}

	private String defaultMessage(Status.Code code) {
		return switch (code) {
			case NOT_FOUND -> "Not found";
			case ALREADY_EXISTS -> "Already exists";
			case INVALID_ARGUMENT -> "Invalid input";
			case UNAUTHENTICATED -> "Authentication required";
			case PERMISSION_DENIED -> "Forbidden";
			case UNAVAILABLE -> "Service temporarily unavailable";
			case DEADLINE_EXCEEDED -> "Upstream timeout";
			default -> "Upstream service error";
		};
	}

	private Mapped map(Status.Code code) {
		return switch (code) {
			case INVALID_ARGUMENT -> new Mapped(400, ErrorType.BAD_REQUEST, "BAD_REQUEST");
			case UNAUTHENTICATED -> new Mapped(401, ErrorType.UNAUTHORIZED, "UNAUTHORIZED");
			case PERMISSION_DENIED -> new Mapped(403, ErrorType.FORBIDDEN, "FORBIDDEN");
			case NOT_FOUND -> new Mapped(404, ErrorType.NOT_FOUND, "NOT_FOUND");
			case ALREADY_EXISTS -> new Mapped(409, ErrorType.BAD_REQUEST, "CONFLICT");
			case UNAVAILABLE -> new Mapped(503, ErrorType.INTERNAL_ERROR, "SERVICE_UNAVAILABLE");
			case DEADLINE_EXCEEDED -> new Mapped(504, ErrorType.INTERNAL_ERROR, "GATEWAY_TIMEOUT");
			default -> new Mapped(502, ErrorType.INTERNAL_ERROR, "UPSTREAM_ERROR");
		};
	}

	private record Mapped(int httpStatus, ErrorType errorType, String code) {
	}
}
