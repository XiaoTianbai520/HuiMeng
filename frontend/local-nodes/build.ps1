param(
  [Parameter(Mandatory = $true)][string]$ToonflowRoot
)

$ErrorActionPreference = 'Stop'
$rootPath = (Resolve-Path -LiteralPath $ToonflowRoot).Path
$specs = @(
  @{ Name = 'localQwenImage21Node'; Base = 'imageGenerationNode'; Label = 'QwenImage 2.1（本地预设）'; Hash = 'E027C2AE03763CD5ABFA1267E089BF70E285EFE38966A202FEED341D1D9A84A7' },
  @{ Name = 'localMiniMaxH3Node'; Base = 'videoGenerationNode'; Label = 'MiniMax H3（本地预设）'; Hash = '93F65A5FA6D6B34DE1AE0E4455B0BCF74AC6E92C076DBCFF6B7A00CF3FF4D572' }
)

foreach ($spec in $specs) {
  $baseFile = Join-Path $rootPath "packages/nodes/$($spec.Base)/src/index.vue"
  if (!(Test-Path -LiteralPath $baseFile) -or (Get-FileHash -LiteralPath $baseFile -Algorithm SHA256).Hash -ne $spec.Hash) {
    throw "原版节点源码与已验证版本不一致：$baseFile"
  }
  $baseDir = Join-Path $rootPath "packages/nodes/$($spec.Base)"
  $sourcePath = Join-Path $baseDir "src/$($spec.Name).vue"
  $configPath = Join-Path $baseDir "$($spec.Name).vite.config.ts"
  $sassLocal = Join-Path $baseDir 'node_modules/sass'
  if (!(Test-Path -LiteralPath $sassLocal)) {
    $sassSource = Join-Path $rootPath 'node_modules/sass'
    if (!(Test-Path -LiteralPath $sassSource)) {
      $sassPackage = Get-ChildItem -LiteralPath (Join-Path $rootPath 'node_modules/.bun') -Directory | Where-Object Name -Like 'sass@*' | Select-Object -First 1
      if ($sassPackage) { $sassSource = Join-Path $sassPackage.FullName 'node_modules/sass' }
    }
    if (!(Test-Path -LiteralPath $sassSource)) { throw "缺少 Sass 依赖：$sassSource" }
    Copy-Item -LiteralPath $sassSource -Destination $sassLocal -Recurse
  }
  foreach ($dependency in @('immutable', 'chokidar', 'source-map-js', 'readdirp')) {
    $destination = Join-Path $baseDir "node_modules/$dependency"
    if (Test-Path -LiteralPath $destination) { continue }
    $package = Get-ChildItem -LiteralPath (Join-Path $rootPath 'node_modules/.bun') -Directory | Where-Object Name -Like "$dependency@*" | Select-Object -First 1
    if (!$package) { throw "缺少 Sass 依赖：$dependency" }
    $source = Join-Path $package.FullName "node_modules/$dependency"
    if (!(Test-Path -LiteralPath $source)) { throw "缺少 Sass 依赖：$source" }
    Copy-Item -LiteralPath $source -Destination $destination -Recurse
  }
  if ((Test-Path -LiteralPath $sourcePath) -or (Test-Path -LiteralPath $configPath)) {
    throw "临时构建文件已存在，停止以避免覆盖：$($spec.Name)"
  }
}

foreach ($spec in $specs) {
  $baseDir = Join-Path $rootPath "packages/nodes/$($spec.Base)"
  $sourcePath = Join-Path $baseDir "src/$($spec.Name).vue"
  $configPath = Join-Path $baseDir "$($spec.Name).vite.config.ts"
  try {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot "$($spec.Name)/src/index.vue") -Destination $sourcePath
    @"
import { createNodeConfig } from '@toonflow/nodes-scaffold';
import { fileURLToPath } from 'node:url';

const config = createNodeConfig({
  name: '$($spec.Name)',
  displayName: '$($spec.Label)',
  author: 'Huimeng',
  github: '',
}, import.meta.url);
(config.build as { lib: { entry: string } }).lib.entry = fileURLToPath(new URL('./src/$($spec.Name).vue', import.meta.url));
export default config;
"@ | Set-Content -LiteralPath $configPath -Encoding utf8
    $vite = Join-Path $baseDir 'node_modules/.bin/vite.exe'
    if (!(Test-Path -LiteralPath $vite)) { throw "未找到 Vite：$vite" }
    & $vite build --config $configPath
    if ($LASTEXITCODE -ne 0) { throw "构建失败：$($spec.Name)" }
  } finally {
    Remove-Item -LiteralPath $sourcePath -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $configPath -Force -ErrorAction SilentlyContinue
  }
}
