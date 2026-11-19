# 一键部署：gateway + sentinel-dashboard，从 ACR 拉镜像
# 用法：.\deploy.ps1                          # 用当前 HEAD 的 sha-xxxxxxx
#       .\deploy.ps1 -Tag sha-abc1234

param(
  [string]$Tag = ""
)

$ErrorActionPreference = 'Stop'
$helm = 'C:\Users\jnxj\AppData\Local\helm3\windows-amd64\helm.exe'
if (-not (Test-Path $helm)) { $helm = 'helm' }
$kubectl = 'C:\Users\jnxj\AppData\Local\Microsoft\WinGet\Packages\Kubernetes.kubectl_Microsoft.Winget.Source_8wekyb3d8bbwe\kubectl.exe'
if (-not (Test-Path $kubectl)) { $kubectl = 'kubectl' }

$repo = $PSScriptRoot
Push-Location $repo

if (-not $Tag) {
  $sha = (git rev-parse --short=7 HEAD).Trim()
  $Tag = "sha-$sha"
}
Write-Host "[1/4] 镜像 tag = $Tag"

Write-Host "[2/4] helm upgrade --install springcloud-gateway ..."
& $helm upgrade --install springcloud-gateway (Join-Path $repo 'helm\springcloud-gateway') `
  -n default `
  --set image.tag=$Tag `
  --atomic --timeout 8m
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "helm 部署失败（已自动回滚）" }

Write-Host "[3/4] 等待 rollout ..."
& $kubectl -n default rollout status deploy/springcloud-gateway --timeout=240s
& $kubectl -n default rollout status deploy/sentinel-dashboard --timeout=240s

Write-Host "[4/4] 探活 ..."
$nodeIp = (& $kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')
$code = & curl.exe -s -o NUL -w '%{http_code}' -m 10 -H 'Host: gateway.minikube.local' "http://$nodeIp/actuator/health"
Write-Host "  gateway /actuator/health -> $code"
$code2 = & curl.exe -s -o NUL -w '%{http_code}' -m 10 -H 'Host: sentinel.minikube.local' "http://$nodeIp/"
Write-Host "  sentinel dashboard / -> $code2"

& $helm history springcloud-gateway -n default
Pop-Location
