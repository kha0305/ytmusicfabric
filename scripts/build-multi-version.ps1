Param(
	[string]$JavaHome = "C:\Program Files\Java\jdk-21"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $JavaHome)) {
	throw "Không tìm thấy JDK tại '$JavaHome'."
}

$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$env:Path"

$projectRoot = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectRoot "dist\multi-version"

$variants = @(
	@{
		Name = "mc1.21.1"
		MinecraftVersion = "1.21.1"
		YarnMappings = "1.21.1+build.3"
		FabricApi = "0.116.12+1.21.1"
		LoaderVersion = "0.16.10"
	},
	@{
		Name = "mc1.21.11"
		MinecraftVersion = "1.21.11"
		YarnMappings = "1.21.11+build.5"
		FabricApi = "0.141.4+1.21.11"
		LoaderVersion = "0.19.2"
	}
)

foreach ($variant in $variants) {
	Write-Host "=== Building $($variant.Name) ==="
	& "$projectRoot\gradlew.bat" clean build `
		"-Pminecraft_version=$($variant.MinecraftVersion)" `
		"-Pyarn_mappings=$($variant.YarnMappings)" `
		"-Pfabric_api_version=$($variant.FabricApi)" `
		"-Ploader_version=$($variant.LoaderVersion)"

	if ($LASTEXITCODE -ne 0) {
		throw "Build thất bại cho $($variant.Name)."
	}

	New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
	$builtJar = Join-Path $projectRoot "build\libs\ytmusicfabric-0.1.0.jar"
	$targetJar = Join-Path $outputDir "ytmusicfabric-0.1.0+$($variant.Name).jar"
	Copy-Item -LiteralPath $builtJar -Destination $targetJar -Force
}

Write-Host "Done. Multi-version jars:"
Get-ChildItem $outputDir | Select-Object Name, Length, LastWriteTime
