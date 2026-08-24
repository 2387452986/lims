package com.lanchuan.lims.configuration;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(title = "试验检测信息管理系统 API", version = "0.1.0-SNAPSHOT",
                description = "同域 REST JSON 接口；写操作使用 CSRF、幂等键和乐观锁。"),
        security = @SecurityRequirement(name = "sessionCookie")
)
@SecurityScheme(name = "sessionCookie", type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.COOKIE, paramName = "SESSION",
        description = "Spring Session 安全会话 Cookie")
public class OpenApiConfiguration {
    @Bean
    OperationCustomizer stableOperationSummaries() {
        return (operation, handler) -> {
            if (operation.getSummary() == null || operation.getSummary().isBlank()) {
                String controller = handler.getBeanType().getSimpleName().replace("Controller", "");
                operation.setSummary(controller + " " + handler.getMethod().getName());
            }
            return operation;
        };
    }
}
