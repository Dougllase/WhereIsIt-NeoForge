# WhereIsNeo 部署脚本
# 用法: .\deploy.ps1 [-TargetDir <路径>] [-NoBuild]
#
# 参数:
#   -TargetDir  目标 mods 文件夹路径（默认: E:\thw\.minecraft\versions\MP1.21.1\mods）
#   -NoBuild    跳过构建，直接复制现有 jar

param(
    [string]$TargetDir = "E:\thw\.minecraft\versions\MP1.21.1\mods",
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$JarName = "whereisit-2.6.4-neoforge-client.jar"
$BuildOutput = Join-Path $ProjectDir "build\libs\$JarName"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  WhereIsNeo 部署脚本" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 1. 构建
if (-not $NoBuild) {
    Write-Host "[1/3] 正在构建..." -ForegroundColor Yellow
    Push-Location $ProjectDir
    try {
        & .\gradlew.bat build 2>&1 | ForEach-Object {
            Write-Host "  $_" -ForegroundColor Gray
        }
        if ($LASTEXITCODE -ne 0) {
            Write-Host "构建失败！" -ForegroundColor Red
            exit 1
        }
    } finally {
        Pop-Location
    }
    Write-Host "  构建完成" -ForegroundColor Green
} else {
    Write-Host "[1/3] 跳过构建" -ForegroundColor Yellow
}

# 2. 检查 jar 是否存在
Write-Host ""
Write-Host "[2/3] 检查构建产物..." -ForegroundColor Yellow
if (-not (Test-Path $BuildOutput)) {
    Write-Host "  未找到 $BuildOutput" -ForegroundColor Red
    Write-Host "  请先运行构建（去掉 -NoBuild 参数）" -ForegroundColor Red
    exit 1
}
$jarSize = (Get-Item $BuildOutput).Length
Write-Host "  找到 $JarName ($jarSize bytes)" -ForegroundColor Green

# 3. 部署
Write-Host ""
Write-Host "[3/3] 部署到 $TargetDir ..." -ForegroundColor Yellow

# 确保目标目录存在
if (-not (Test-Path $TargetDir)) {
    Write-Host "  目标目录不存在，正在创建..." -ForegroundColor Yellow
    New-Item -ItemType Directory -Path $TargetDir -Force | Out-Null
}

# 复制 jar
Copy-Item -Path $BuildOutput -Destination $TargetDir -Force
Write-Host "  已复制到 $TargetDir\$JarName" -ForegroundColor Green

# 验证
$deployed = Join-Path $TargetDir $JarName
if (Test-Path $deployed) {
    $deployedSize = (Get-Item $deployed).Length
    if ($deployedSize -eq $jarSize) {
        Write-Host "  验证通过 ($deployedSize bytes)" -ForegroundColor Green
    } else {
        Write-Host "  警告：文件大小不匹配！($deployedSize vs $jarSize)" -ForegroundColor Red
    }
} else {
    Write-Host "  错误：部署后文件不存在！" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  部署成功！" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
