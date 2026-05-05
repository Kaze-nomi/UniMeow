package uni.gateway.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor;

import java.util.concurrent.TimeUnit;

@Slf4j
@GrpcGlobalClientInterceptor
public class GrpcDeadlineInterceptor implements ClientInterceptor {

	private static final long DEADLINE_MS = 10_000L;
	private static final long SLOW_THRESHOLD_MS = 8_000L;

	@Override
	public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
			CallOptions callOptions, Channel next) {
		CallOptions opts = callOptions.getDeadline() == null
				? callOptions.withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)
				: callOptions;
		long start = System.nanoTime();
		String methodName = method.getFullMethodName();
		return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, opts)) {
			@Override
			public void start(Listener<RespT> responseListener, Metadata headers) {
				super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(responseListener) {
					@Override
					public void onClose(Status status, Metadata trailers) {
						long durMs = (System.nanoTime() - start) / 1_000_000L;
						if (!status.isOk()) {
							log.warn("gRPC call {} failed in {}ms: code={} desc={}", methodName, durMs,
									status.getCode(), status.getDescription());
						} else if (durMs >= SLOW_THRESHOLD_MS) {
							log.warn("gRPC slow call {} took {}ms", methodName, durMs);
						}
						super.onClose(status, trailers);
					}
				}, headers);
			}
		};
	}
}
