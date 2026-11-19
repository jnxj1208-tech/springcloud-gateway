# springcloud-gateway

Spring Cloud Gateway 5.0（WebFlux）+ Sentinel 的 API 网关，跑在 minikube 上。

## 技术栈

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Spring Boot | 4.0.0 | SCA 2025.1.0.0 要求 |
| Spring Cloud | 2025.1.3 (Oakwood) | SCG 5.0.3 |
| Spring Cloud Alibaba | 2025.1.0.0 | Nacos 注册与配置 |
| Spring Cloud Gateway | 5.0.3 `server-webflux` | ⚠️ artifact 已改名 |
| Sentinel | 1.8.9 | adapter 1.8.10 依赖缺失的 common 包，与 SCA 声明对齐 |
| Nacos | 3.3.0 | 集群内 `nacos:8848` |

## 关键点

- **路由配置的命名空间变了**：`spring.cloud.gateway.server.webflux.routes`。旧的 `spring.cloud.gateway.routes` 在 SCG 5 里静默失效。
- **必须用 WebFlux 实现**：Sentinel 的网关适配器基于 `GlobalFilter`，走的是响应式链路；WebMVC 版是 RouterFunction，没有这条适配路径。
- **Sentinel 适配器要排除旧 artifact**：它传递依赖 `spring-cloud-starter-gateway` / `spring-cloud-gateway-core`（3.x/4.x 时代的名字），会与 5.0 冲突，pom 里已 exclude。
- **租户标识由网关注入**：`X-Tenant-Id` 缺失时回落 `anonymous`（demo 便于压测），生产应改成 400/403。
- **traceId**：优先复用上游 `traceparent` 的 trace-id，否则生成，写回 `X-Trace-Id` 响应头并透传给下游。

## 部署

CI（GitHub Actions）构建两个镜像推到 ACR：

- `springcloud-gateway:sha-xxxxxxx`
- `sentinel-dashboard:1.8.9`

本地部署（CI 够不到集群）：

```powershell
.\deploy.ps1 -Tag sha-xxxxxxx
```

## 验证限流

```bash
# 路由透传
curl -H 'Host: gateway.minikube.local' http://<nodeIp>/api/hello

# 租户级限流：同一 X-Tenant-Id 每秒超过 5 次返回 429
for i in $(seq 1 10); do curl -s -o /dev/null -w '%{http_code}\n' \
  -H 'Host: gateway.minikube.local' -H 'X-Tenant-Id: studio-a' \
  http://<nodeIp>/api/hello; done
```

Dashboard：`http://sentinel.minikube.local`（默认 sentinel / sentinel）
