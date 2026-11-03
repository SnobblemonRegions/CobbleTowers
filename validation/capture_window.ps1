param([int]$OriginX = 2560, [int]$OriginY = 0, [string]$Out, [string]$Title = "Minecraft", [int]$ProcessId = 0)
# Saves a picture of the Minecraft client window (moved to the top-left of the monitor at ($OriginX, $OriginY) and brought to the front first) to $Out.
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Win {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, int flags, int extra);
  [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr h, int x, int y, int w, int ht, bool repaint);
  public struct RECT { public int Left, Top, Right, Bottom; }
}
"@
[Win]::SetProcessDPIAware() | Out-Null
# only the test client, by process id: the player's own game window is never touched
$p = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
if ($p -and $p.MainWindowHandle -eq 0) { $p.Refresh() }
if (-not $p) { Write-Error "no client window"; exit 1 }
[Win]::ShowWindow($p.MainWindowHandle, 9) | Out-Null
# Windows only lets the process that last got input take the front: one Alt press (down, up) lifts that lock; the guard below still checks the result
[Win]::keybd_event(0x12, 0, 0, 0); [Win]::keybd_event(0x12, 0, 2, 0)
[Win]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
Start-Sleep -Milliseconds 400
# never photograph anything but the test client: if another window is in front, the screen holds somebody else's content
if ([Win]::GetForegroundWindow() -ne $p.MainWindowHandle) { Write-Error "the client window is not in front; nothing was captured"; exit 3 }
$r = New-Object Win+RECT
[Win]::GetWindowRect($p.MainWindowHandle, [ref]$r) | Out-Null
$w = $r.Right - $r.Left; $h = $r.Bottom - $r.Top
[Win]::MoveWindow($p.MainWindowHandle, $OriginX, $OriginY, $w, $h, $true) | Out-Null
Start-Sleep -Milliseconds 700
[Win]::GetWindowRect($p.MainWindowHandle, [ref]$r) | Out-Null
$w = $r.Right - $r.Left; $h = $r.Bottom - $r.Top
$bmp = New-Object System.Drawing.Bitmap $w, $h
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($r.Left, $r.Top, 0, 0, $bmp.Size)
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()
Write-Output "$w x $h"
