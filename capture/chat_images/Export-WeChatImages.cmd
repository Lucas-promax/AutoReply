@echo off
setlocal
set "AUTOREPLY_IMAGE_SCRIPT=%~dp0Export-WeChatImages.ps1"
set "AUTOREPLY_IMAGE_DIRECTORY=%~1"
if not defined AUTOREPLY_IMAGE_DIRECTORY set "AUTOREPLY_IMAGE_DIRECTORY=%~dp0..\..\test\exports\images"
powershell.exe -NoProfile -STA -Command "$ErrorActionPreference = 'Stop'; $path = $env:AUTOREPLY_IMAGE_SCRIPT; $export = [scriptblock]::Create((Get-Content -Raw -LiteralPath $path)); & $export -OutputDirectory $env:AUTOREPLY_IMAGE_DIRECTORY -ScriptDirectory (Split-Path $path)"
exit /b %ERRORLEVEL%
