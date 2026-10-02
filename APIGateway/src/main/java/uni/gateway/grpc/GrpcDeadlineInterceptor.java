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
import org.springframework.beans.factory.annotation.Value;

import java.util.concurrent.TimeUnit;

@Slf4j
@GrpcGlobalClientInterceptor
public class GrpcDeadlineInterceptor implements ClientInterceptor {

	@Value("${app.grpc.deadline-ms:10000}")
	private long defaultDeadlineMs;

	@Value("${app.grpc.slow-threshold-ms:1000}")
	private long slowThresholdMs;

	@Override
	public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
			CallOptions callOptions, Channel next) {
		CallOptions opts = callOptions.getDeadline() == null
				? callOptions.withDeadlineAfter(defaultDeadlineMs, TimeUnit.MILLISECONDS)
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
							log.atWarn().addKeyValue("event", "grpc_call_failed").addKeyValue("method", methodName)
									.addKeyValue("status", status.getCode().name()).addKeyValue("durationMs", durMs)
									.setCause(status.getCause()).log("gRPC call failed");
						} else if (durMs >= slowThresholdMs) {
							log.atWarn().addKeyValue("event", "grpc_call_slow").addKeyValue("method", methodName)
									.addKeyValue("status", status.getCode().name()).addKeyValue("durationMs", durMs)
									.log("Slow gRPC call");
						}
						super.onClose(status, trailers);
					}
				}, headers);
			}
		};
	}
}
