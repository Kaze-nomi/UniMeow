package uni.gateway.security;

import graphql.ErrorType;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphqlErrorBuilder;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.Node;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.parser.Parser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.support.DefaultExecutionGraphQlResponse;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import uni.gateway.grpc.UserGrpcClient;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class GraphQlAuthInterceptor implements WebGraphQlInterceptor {

	private static final Set<String> MUTATIONS_ALLOWED_BEFORE_REGISTRATION = Set.of("updateProfile", "deleteAccount");

	private final UserGrpcClient userGrpcClient;

	@Override
	public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
		String userId = request.getHeaders().getFirst("X-User-Id");

		if (userId != null) {
			request.configureExecutionInput(
					(input, builder) -> builder.graphQLContext(ctx -> ctx.put("userId", userId)).build());
		}

		long start = System.nanoTime();
		String op = request.getOperationName();
		String preview = request.getDocument();
		if (preview != null && preview.length() > 80) {
			preview = preview.substring(0, 80).replaceAll("\\s+", " ") + "…";
		}
		String tag = (op != null && !op.isBlank()) ? op : preview;

		Set<String> restrictedMutations = restrictedMutationFields(request);
		Mono<WebGraphQlResponse> chainMono;
		if (userId == null || restrictedMutations.isEmpty()) {
			chainMono = chain.next(request);
		} else {
			chainMono = userGrpcClient.getUserById(userId).flatMap(user -> {
				if (user.getUsername() == null || user.getUsername().isBlank()) {
					return Mono.just(registrationRequiredResponse(request));
				}
				return chain.next(request);
			});
		}

		return chainMono.doOnSuccess(r -> {
			long ms = (System.nanoTime() - start) / 1_000_000L;
			if (ms >= 1_000L) {
				log.warn("Slow GraphQL request '{}' took {}ms", tag, ms);
			}
		}).doOnError(e -> {
			long ms = (System.nanoTime() - start) / 1_000_000L;
			log.warn("Failed GraphQL request '{}' after {}ms: {}", tag, ms, e.toString());
		});
	}

	private WebGraphQlResponse registrationRequiredResponse(WebGraphQlRequest request) {
		ExecutionInput input = request.toExecutionInput();
		ExecutionResult result = ExecutionResult.newExecutionResult()
				.addError(GraphqlErrorBuilder.newError().message("Завершите регистрацию перед выполнением действия")
						.errorType(ErrorType.ValidationError).extensions(Map.of("code", "REGISTRATION_REQUIRED"))
						.build())
				.build();
		return new WebGraphQlResponse(new DefaultExecutionGraphQlResponse(input, result));
	}

	private Set<String> restrictedMutationFields(WebGraphQlRequest request) {
		Set<String> mutationFields = new HashSet<>(mutationFields(request));
		mutationFields.removeAll(MUTATIONS_ALLOWED_BEFORE_REGISTRATION);
		return mutationFields;
	}

	private Set<String> mutationFields(WebGraphQlRequest request) {
		Document document;
		try {
			document = Parser.parse(request.getDocument());
		} catch (RuntimeException ignored) {
			return Set.of();
		}

		Map<String, FragmentDefinition> fragments = document.getDefinitionsOfType(FragmentDefinition.class).stream()
				.collect(java.util.stream.Collectors.toMap(FragmentDefinition::getName, f -> f, (a, b) -> a));
		List<OperationDefinition> operations = document.getDefinitionsOfType(OperationDefinition.class);
		String operationName = request.getOperationName();
		if (operationName != null && !operationName.isBlank()) {
			return operations.stream().filter(op -> operationName.equals(op.getName())).findFirst()
					.map(op -> mutationFields(op, fragments)).orElse(Set.of());
		}
		if (operations.size() != 1) {
			return Set.of();
		}
		return mutationFields(operations.get(0), fragments);
	}

	private Set<String> mutationFields(OperationDefinition operation, Map<String, FragmentDefinition> fragments) {
		if (operation.getOperation() != OperationDefinition.Operation.MUTATION) {
			return Set.of();
		}
		Set<String> fields = new HashSet<>();
		collectRootFields(operation.getSelectionSet(), fragments, fields, new HashSet<>());
		return fields;
	}

	private void collectRootFields(SelectionSet selectionSet, Map<String, FragmentDefinition> fragments,
			Set<String> fields, Set<String> visitedFragments) {
		if (selectionSet == null) {
			return;
		}
		for (Selection<?> selection : selectionSet.getSelections()) {
			Node<?> node = (Node<?>) selection;
			if (node instanceof Field field) {
				fields.add(field.getName());
			} else if (node instanceof InlineFragment fragment) {
				collectRootFields(fragment.getSelectionSet(), fragments, fields, visitedFragments);
			} else if (node instanceof FragmentSpread spread && visitedFragments.add(spread.getName())) {
				FragmentDefinition fragment = fragments.get(spread.getName());
				if (fragment != null) {
					collectRootFields(fragment.getSelectionSet(), fragments, fields, visitedFragments);
				}
			}
		}
	}
}
