[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$ScriptDirectory = $PSScriptRoot,
    [ValidateRange(1, 100)][int]$Limit = 20
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms, System.Drawing, UIAutomationClient, UIAutomationTypes
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class WeChatImageDesktop
{
    [StructLayout(LayoutKind.Sequential)]
    public struct Point { public int X, Y; }
    [StructLayout(LayoutKind.Sequential)]
    public struct Rect { public int Left, Top, Right, Bottom; }

    [DllImport("user32.dll")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr value);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern IntPtr GetAncestor(IntPtr hwnd, uint flags);
    [DllImport("user32.dll")] public static extern IntPtr WindowFromPoint(Point point);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hwnd, out Rect bounds);
    [DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr hwnd);
    [DllImport("user32.dll")] static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] static extern void mouse_event(uint flags, uint x, uint y, uint data, UIntPtr extra);

    public static void Click(IntPtr expected, int x, int y, bool twice)
    {
        var point = new Point { X = x, Y = y };
        if (GetAncestor(WindowFromPoint(point), 2) != expected || !SetCursorPos(x, y))
            throw new InvalidOperationException("The target is covered or outside the desktop. Move WeChat to the foreground.");
        mouse_event(2, 0, 0, 0, UIntPtr.Zero);
        mouse_event(4, 0, 0, 0, UIntPtr.Zero);
        if (twice)
        {
            mouse_event(2, 0, 0, 0, UIntPtr.Zero);
            mouse_event(4, 0, 0, 0, UIntPtr.Zero);
        }
    }
}
'@

function Wait-WeChatWindow([uint32]$ProcessId, [string]$Name, [int]$Milliseconds = 3500) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $handle = [WeChatImageDesktop]::GetForegroundWindow()
        $owner = [uint32]0
        if ($handle -ne [IntPtr]::Zero) {
            [void][WeChatImageDesktop]::GetWindowThreadProcessId($handle, [ref]$owner)
            if ($owner -eq $ProcessId) {
                $window = [System.Windows.Automation.AutomationElement]::FromHandle($handle)
                if ($window.Current.Name -eq $Name -or $window.Current.Name.StartsWith($Name + '.')) {
                    return $handle
                }
            }
        }
        Start-Sleep -Milliseconds 80
    } while ($timer.ElapsedMilliseconds -lt $Milliseconds)
}

function Set-ImageDestination([IntPtr]$Dialog, [string]$Directory, [int]$Index) {
    $focused = [System.Windows.Automation.AutomationElement]::FocusedElement
    $dialogElement = [System.Windows.Automation.AutomationElement]::FromHandle($Dialog)
    $walker = [System.Windows.Automation.TreeWalker]::RawViewWalker
    $parent = $focused
    while ($parent -and -not $parent.Equals($dialogElement)) {
        $parent = $walker.GetParent($parent)
    }
    if (-not $parent -or [WeChatImageDesktop]::GetForegroundWindow() -ne $Dialog) {
        throw 'The Save As dialog is no longer focused.'
    }
    $field = $focused.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
    $extension = [IO.Path]::GetExtension($field.Current.Value)
    if ($extension -notin '.jpg', '.jpeg', '.png', '.bmp', '.gif', '.webp') {
        throw 'Could not determine the image format in the Save As dialog.'
    }
    $name = 'wechat-image-{0:D4}-{1}{2}' -f $Index, ([guid]::NewGuid().ToString('N')), $extension
    $path = Join-Path $Directory $name
    $field.SetValue($path)
    if ($field.Current.Value -ne $path -or [WeChatImageDesktop]::GetForegroundWindow() -ne $Dialog) {
        throw 'Could not set the image destination safely.'
    }
    [System.Windows.Forms.SendKeys]::SendWait('{ENTER}')
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while (-not [IO.File]::Exists($path) -and $timer.ElapsedMilliseconds -lt 5000) {
        Start-Sleep -Milliseconds 100
    }
    $image = [System.Drawing.Image]::FromFile($path)
    try {
        [pscustomobject]@{ Index = $Index; Path = $path; Width = $image.Width; Height = $image.Height }
    } finally {
        $image.Dispose()
    }
}

[void][WeChatImageDesktop]::SetThreadDpiAwarenessContext([IntPtr](-4))
$modulePath = Join-Path (Split-Path -Parent $ScriptDirectory) 'chat_text\WeChatCapture.psm1'
$capture = New-Module -ScriptBlock ([scriptblock]::Create((Get-Content -Raw -LiteralPath $modulePath)))
$snapshot = & $capture { Get-WeChatConversation -IncludeImageTargets }
if (-not $snapshot.ConversationTitle) { throw 'Cannot verify the current conversation.' }
$directory = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory)
$targets = @($snapshot.Records | Where-Object ImageBounds | Select-Object -First $Limit)
if (-not $targets.Count) { throw 'No fully visible image messages found. Scroll to an image and try again.' }
[void][IO.Directory]::CreateDirectory($directory)

$main = [IntPtr]$snapshot.WindowHandle
$owner = [uint32]0
[void][WeChatImageDesktop]::GetWindowThreadProcessId($main, [ref]$owner)
$previewName = -join (0x56FE, 0x7247, 0x67E5, 0x770B | ForEach-Object { [char]$_ })
$saveName = -join (0x53E6, 0x5B58, 0x4E3A | ForEach-Object { [char]$_ })
foreach ($target in $targets) {
    $current = & $capture { Get-WeChatConversation -IncludeImageTargets }
    $row = $current.Records[$target.Index - 1]
    if ($current.WindowHandle -ne $snapshot.WindowHandle -or
        $current.ConversationTitle -cne $snapshot.ConversationTitle -or
        ($row.ImageBounds -join ',') -ne ($target.ImageBounds -join ',')) {
        throw 'The conversation or visible message positions changed. Export stopped.'
    }
    $left, $top, $width, $height = $target.ImageBounds
    [WeChatImageDesktop]::Click($main, [int]($left + $width / 2), [int]($top + $height / 2), $true)
    $viewer = Wait-WeChatWindow $owner $previewName
    if (-not $viewer) { throw 'WeChat did not open the expected image viewer.' }
    [System.Windows.Forms.SendKeys]::SendWait('^s')
    $dialog = Wait-WeChatWindow $owner $saveName 800
    if (-not $dialog) {
        if ([WeChatImageDesktop]::GetForegroundWindow() -ne $viewer) {
            throw 'The image viewer lost focus before saving.'
        }
        $bounds = New-Object WeChatImageDesktop+Rect
        [void][WeChatImageDesktop]::GetWindowRect($viewer, [ref]$bounds)
        $scale = [WeChatImageDesktop]::GetDpiForWindow($viewer) / 96
        if ($scale -le 0 -or $bounds.Right - $bounds.Left -lt 400 * $scale) {
            throw 'The image viewer layout is unsupported.'
        }
        # Save button on the tested WeChat 3.9 image viewer toolbar (96 DPI coordinates).
        [WeChatImageDesktop]::Click($viewer, [int]($bounds.Left + 373 * $scale),
            [int]($bounds.Top + 16 * $scale), $false)
        $dialog = Wait-WeChatWindow $owner $saveName
    }
    if (-not $dialog) { throw 'The image viewer did not open Save As.' }
    Set-ImageDestination $dialog $directory $target.Index
    if ([WeChatImageDesktop]::GetForegroundWindow() -eq $viewer) {
        [System.Windows.Forms.SendKeys]::SendWait('{ESC}')
    }
}
