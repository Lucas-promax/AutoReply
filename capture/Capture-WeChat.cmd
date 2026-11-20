@echo off
setlocal
set "AUTOREPLY_CAPTURE_ROOT=%~dp0"
powershell.exe -NoProfile -Command "$ErrorActionPreference = 'Stop'; [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false); $path = Join-Path $env:AUTOREPLY_CAPTURE_ROOT 'WeChatCapture.psm1'; $module = New-Module -Name WeChatCapture -ScriptBlock ([scriptblock]::Create((Get-Content -Raw -LiteralPath $path))); $snapshot = & $module { Get-WeChatConversation }; [Console]::WriteLine((ConvertTo-Json -InputObject $snapshot -Depth 4 -Compress))"
exit /b %ERRORLEVEL%
