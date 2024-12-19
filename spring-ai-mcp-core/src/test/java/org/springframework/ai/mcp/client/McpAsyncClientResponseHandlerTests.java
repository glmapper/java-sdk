/*
 * Copyright 2024-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.mcp.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.type.TypeReference;

import static org.awaitility.Awaitility.await;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import org.springframework.ai.mcp.spec.McpSchema;
import org.springframework.ai.mcp.spec.McpSchema.JSONRPCNotification;
import org.springframework.ai.mcp.spec.McpSchema.JSONRPCRequest;
import org.springframework.ai.mcp.spec.McpSchema.Root;
import org.springframework.ai.mcp.spec.McpTransport;

import static org.assertj.core.api.Assertions.assertThat;

class McpAsyncClientResponseHandlerTests {

	@SuppressWarnings("unused")
	private static class MockMcpTransport implements McpTransport {

		private final AtomicInteger inboundMessageCount = new AtomicInteger(0);

		private Sinks.Many<McpSchema.JSONRPCMessage> outgoing = Sinks.many().multicast().onBackpressureBuffer();

		private Sinks.Many<McpSchema.JSONRPCMessage> inbound = Sinks.many().unicast().onBackpressureBuffer();

		private Flux<McpSchema.JSONRPCMessage> outboundView = outgoing.asFlux().cache(1);

		public void simulateIncomingMessage(McpSchema.JSONRPCMessage message) {
			if (inbound.tryEmitNext(message).isFailure()) {
				throw new RuntimeException("Failed to emit message " + message);
			}
			inboundMessageCount.incrementAndGet();
		}

		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			if (outgoing.tryEmitNext(message).isFailure()) {
				return Mono.error(new RuntimeException("Can't emit outgoing message " + message));
			}
			return Mono.empty();
		}

		public McpSchema.JSONRPCRequest getLastSentMessageAsRequest() {
			return (JSONRPCRequest) outboundView.blockFirst();
		}

		public McpSchema.JSONRPCNotification getLastSentMessageAsNotifiation() {
			return (JSONRPCNotification) outboundView.blockFirst();
		}

		public McpSchema.JSONRPCMessage getLastSentMessage() {
			return outboundView.blockFirst();
		}

		@Override
		public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
			return inbound.asFlux()
				.publishOn(Schedulers.boundedElastic())
				.flatMap(message -> Mono.just(message).transform(handler))
				.then();
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
			return new ObjectMapper().convertValue(data, typeRef);
		}

	}

	@Test
	void testToolsChangeNotificationHandling() {
		MockMcpTransport transport = new MockMcpTransport();

		// Create a list to store received tools for verification
		List<McpSchema.Tool> receivedTools = new ArrayList<>();

		// Create a consumer that will be called when tools change
		Consumer<List<McpSchema.Tool>> toolsChangeConsumer = tools -> {
			receivedTools.addAll(tools);
		};

		// Create client with tools change consumer
		McpAsyncClient asyncMcpClient = McpClient.using(transport).toolsChangeConsumer(toolsChangeConsumer).async();

		// Create a mock tools list that the server will return
		Map<String, Object> inputSchema = Map.of("type", "object", "properties", Map.of(), "required", List.of());
		McpSchema.Tool mockTool = new McpSchema.Tool("test-tool", "Test Tool Description", inputSchema);
		McpSchema.ListToolsResult mockToolsResult = new McpSchema.ListToolsResult(List.of(mockTool), null);

		// Simulate server sending tools/list_changed notification
		McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				"notifications/tools/list_changed", null);
		transport.simulateIncomingMessage(notification);

		// Simulate server response to tools/list request
		McpSchema.JSONRPCRequest toolsListRequest = transport.getLastSentMessageAsRequest();
		assertThat(toolsListRequest.method()).isEqualTo("tools/list");

		McpSchema.JSONRPCResponse toolsListResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
				toolsListRequest.id(), mockToolsResult, null);
		transport.simulateIncomingMessage(toolsListResponse);

		// Verify the consumer received the expected tools
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(receivedTools).hasSize(1);
			assertThat(receivedTools.get(0).name()).isEqualTo("test-tool");
			assertThat(receivedTools.get(0).description()).isEqualTo("Test Tool Description");
		});

		asyncMcpClient.closeGracefully();
	}

	@Test
	void testRootsListRequestHandling() {
		MockMcpTransport transport = new MockMcpTransport();

		Supplier<List<Root>> rootsListProvider = () -> List.of(new Root("file:///test/path", "test-root"));

		McpAsyncClient asyncMcpClient = McpClient.using(transport).rootsListProvider(rootsListProvider).async();

		// Simulate incoming request
		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "roots/list",
				"test-id", null);
		transport.simulateIncomingMessage(request);

		// Verify response
		McpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(McpSchema.JSONRPCResponse.class);

		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.id()).isEqualTo("test-id");
		assertThat(response.result()).isEqualTo(List.of(new Root("file:///test/path", "test-root")));
		assertThat(response.error()).isNull();

		asyncMcpClient.closeGracefully();
	}

}
