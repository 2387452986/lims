package com.lanchuan.lims.configuration;

import org.springframework.core.env.Environment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/external-integrations")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class ExternalIntegrationController {
    private static final Map<String, Spec> SPECS = specs();
    private final Environment environment;
    private final Map<String, ExternalIntegrationAdapter> adapters;

    public ExternalIntegrationController(Environment environment, List<ExternalIntegrationAdapter> adapters) {
        this.environment = environment;
        this.adapters = adapters.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                ExternalIntegrationAdapter::key, adapter -> adapter));
    }

    public record Status(String key, String title, String state, boolean configured, boolean adapterAvailable,
                         List<String> requiredSettings, List<String> blockers, String safetyPolicy) {}
    private record Spec(String title, List<String> settings, String dependency) {}

    @GetMapping("/{key}")
    public Status status(@PathVariable String key) {
        Spec spec = SPECS.get(key);
        if (spec == null) throw com.lanchuan.lims.common.ApiException.notFound("INTEGRATION_NOT_FOUND", "外部集成不存在");
        String prefix = "lims.integrations." + key + ".";
        boolean enabled = environment.getProperty(prefix + "enabled", Boolean.class, false);
        List<String> missing = spec.settings().stream()
                .filter(setting -> environment.getProperty(prefix + setting) == null).toList();
        ExternalIntegrationAdapter adapter = adapters.get(key);
        java.util.ArrayList<String> blockers = new java.util.ArrayList<>();
        if (!enabled) blockers.add("生产配置未启用");
        if (!missing.isEmpty()) blockers.add("缺少配置：" + String.join("、", missing));
        if (adapter == null) {
            blockers.add("尚未安装经联调验收的正式适配器");
            if (spec.dependency() != null) blockers.add(spec.dependency());
        }
        if (adapter != null) blockers.addAll(adapter.readiness().blockers());
        boolean configured = enabled && missing.isEmpty();
        boolean ready = configured && adapter != null && adapter.readiness().ready() && blockers.isEmpty();
        String state = ready ? "READY" : !enabled ? "DISABLED" : !missing.isEmpty() ? "CONFIGURATION_REQUIRED" : "ADAPTER_UNAVAILABLE";
        return new Status(key, spec.title(), state, configured, adapter != null,
                spec.settings(), List.copyOf(blockers), "外部依赖未就绪时保持受控禁用，不生成模拟读数或伪造业务结果。");
    }

    private static Map<String, Spec> specs() {
        Map<String, Spec> values = new LinkedHashMap<>();
        values.put("iot-temperature-humidity", new Spec("温湿度仪", List.of("endpoint", "device-protocol", "credential-ref"), "需提供设备协议和测试设备"));
        values.put("iot-video", new Spec("视频监控", List.of("endpoint", "vendor", "credential-ref"), "需提供视频平台测试环境"));
        values.put("iot-pressure-machine", new Spec("压力机数据", List.of("endpoint", "device-protocol", "credential-ref"), "需提供压力机协议和测试设备"));
        values.put("iot-universal-machine", new Spec("万能机数据", List.of("endpoint", "device-protocol", "credential-ref"), "需提供万能机协议和测试设备"));
        values.put("iot-rebound-tester", new Spec("回弹仪数据", List.of("endpoint", "device-protocol", "credential-ref"), "需提供回弹仪协议和测试设备"));
        values.put("static-load-monitoring", new Spec("静载数据监控", List.of("endpoint", "device-protocol", "credential-ref"), "需提供静载平台联调环境"));
        values.put("static-load-management", new Spec("静载数据管理", List.of("endpoint", "device-protocol", "credential-ref"), "需提供静载平台联调环境"));
        values.put("static-load-unknown", new Spec("静载未知数据", List.of("endpoint", "device-protocol", "credential-ref"), "需提供未识别数据判定规则"));
        values.put("static-load-devices", new Spec("静载在线设备", List.of("endpoint", "device-protocol", "credential-ref"), "需提供在线设备注册协议"));
        values.put("payment-order-records", new Spec("支付订单记录", List.of("endpoint", "merchant-id", "credential-ref"), "需提供支付测试环境、证书和回调域名"));
        values.put("identity-intent", new Spec("实名核身与签署意愿", List.of("endpoint", "provider", "credential-ref"),
                "需提供实名核身服务协议、个人信息处理规则和联调沙箱；就绪前不采集身份证件或生物识别数据"));
        return Map.copyOf(values);
    }
}
