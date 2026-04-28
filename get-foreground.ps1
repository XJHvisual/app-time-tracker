# Get foreground window process name
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Win32Helper {
    [DllImport("user32.dll")]
    public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")]
    public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
}
"@
$hwnd = [Win32Helper]::GetForegroundWindow()
$procId = 0
[Win32Helper]::GetWindowThreadProcessId($hwnd, [ref]$procId) | Out-Null
if ($procId -gt 0) {
    $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
    if ($proc) { $proc.ProcessName }
}
