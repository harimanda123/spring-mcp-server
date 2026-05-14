package com.example.mcpbridge.config;

import com.example.mcpbridge.tools.ShipmentTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers MCP tool definitions with the Spring AI MCP server.
 *
 * <p>The {@link MethodToolCallbackProvider} scans {@link ShipmentTools} for methods
 * annotated with {@code @Tool} and wraps each into a {@link org.springframework.ai.tool.ToolCallback}.
 * The MCP server auto-configuration discovers all {@link ToolCallbackProvider} beans and
 * advertises the resulting tools to connecting MCP clients.
 */
@Configuration
public class McpToolsConfig {

    @Bean
    public ToolCallbackProvider shipmentToolCallbackProvider(ShipmentTools shipmentTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(shipmentTools)
                .build();
    }
}
