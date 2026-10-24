using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace Unrot;

/// <summary>Finds games: anything launched from Steam/Epic/Riot/Xbox/EA/etc. libraries, plus games you add.</summary>
public static class Games
{
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr OpenProcess(uint access, bool inherit, uint pid);
    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern bool QueryFullProcessImageName(IntPtr h, int flags, StringBuilder name, ref int size);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int max);
    [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr hWnd);

    static readonly string[] Browsers =
    {
        "vivaldi.exe", "chrome.exe", "msedge.exe", "brave.exe", "firefox.exe", "opera.exe", "opera_gx.exe", "arc.exe",
    };

    /// <summary>Search result pages mention site names too; those shouldn't count.</summary>
    static readonly string[] SearchPages = { "- Google Search", "- Search", "- Bing", "at DuckDuckGo" };

    const uint PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    static readonly string[] LibraryRoots =
    {
        @"\steamapps\common\", @"\epic games\", @"\riot games\", @"\xboxgames\", @"\ea games\",
        @"\ubisoft game launcher\games\", @"\gog galaxy\games\", @"\gog games\", @"\roblox\versions\",
        @"\rockstar games\", @"\battle.net\games\",
    };

    static readonly string[] NotGames =
    {
        @"\epic games\launcher\", @"\riot client\", @"steamworks shared", @"\_commonredist\",
        @"\steamvr\", @"\directx\", @"\vcredist", @"\redist\", @"\launcher\portal\",
    };

    static readonly string[] HelperNames =
    {
        "crashhandler", "crashreport", "unitycrashhandler", "easyanticheat", "beservice", "battleye",
        "uninstall", "unins000", "vc_redist", "dxsetup", "dotnet", "updater", "launcher", "bootstrapper",
    };

    static readonly string[] KnownGameExes = { "minecraft.windows.exe", "robloxplayerbeta.exe" };

    public static string ExePath(uint pid)
    {
        var h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
        if (h == IntPtr.Zero) return null;
        try
        {
            var sb = new StringBuilder(1024);
            int size = sb.Capacity;
            return QueryFullProcessImageName(h, 0, sb, ref size) ? sb.ToString() : null;
        }
        finally { CloseHandle(h); }
    }

    public static bool IsGame(string path)
    {
        if (string.IsNullOrEmpty(path)) return false;
        if (Store.S.IgnoredGames.Any(g => Rules.Same(g, path))) return false;
        if (Store.S.ExtraGames.Any(g => Rules.Same(g, path))) return true;
        var p = path.ToLowerInvariant();
        var file = Path.GetFileName(p);
        if (KnownGameExes.Contains(file)) return true;
        if (!LibraryRoots.Any(p.Contains)) return false;
        if (NotGames.Any(p.Contains)) return false;
        if (HelperNames.Any(file.Contains)) return false;
        return true;
    }

    /// <summary>The game whose window is in front right now, if any.</summary>
    public static (int Pid, string Path)? ForegroundGame()
    {
        var hwnd = GetForegroundWindow();
        if (hwnd == IntPtr.Zero) return null;
        GetWindowThreadProcessId(hwnd, out var pid);
        if (pid == 0 || pid == (uint)Environment.ProcessId) return null;
        var path = ExePath(pid);
        return IsGame(path) ? ((int)pid, path) : null;
    }

    /// <summary>The blocked site in the browser tab that's in front, if any.</summary>
    public static (IntPtr Hwnd, BlockedSite Site)? ForegroundSite()
    {
        var hwnd = GetForegroundWindow();
        if (hwnd == IntPtr.Zero) return null;
        GetWindowThreadProcessId(hwnd, out var pid);
        if (pid == 0) return null;
        var exe = Path.GetFileName(ExePath(pid) ?? "").ToLowerInvariant();
        if (!Browsers.Contains(exe)) return null;
        var sb = new StringBuilder(512);
        GetWindowText(hwnd, sb, sb.Capacity);
        var title = sb.ToString();
        if (title.Length == 0 || SearchPages.Any(title.Contains)) return null;
        foreach (var site in Store.S.Settings.Sites)
        {
            var keys = (site.Keywords ?? "").Split('|', StringSplitOptions.RemoveEmptyEntries);
            if (keys.Any(k => title.Contains(k, StringComparison.OrdinalIgnoreCase))) return (hwnd, site);
        }
        return null;
    }

    /// <summary>Closes the browser tab that's showing a blocked site (Ctrl+W).</summary>
    public static void CloseTab(IntPtr hwnd)
    {
        try
        {
            SetForegroundWindow(hwnd);
            Thread.Sleep(150);
            SendKeys.SendWait("^w");
        }
        catch { }
    }

    public static List<(int Pid, string Path)> Running()
    {
        var list = new List<(int, string)>();
        foreach (var proc in Process.GetProcesses())
        {
            try
            {
                if (proc.Id == Environment.ProcessId || proc.Id <= 4) continue;
                var path = ExePath((uint)proc.Id);
                if (IsGame(path)) list.Add((proc.Id, path));
            }
            catch { }
            finally { proc.Dispose(); }
        }
        return list;
    }

    public static int CloseAll()
    {
        int n = 0;
        foreach (var (pid, _) in Running())
        {
            try
            {
                using var proc = Process.GetProcessById(pid);
                proc.Kill(true);
                n++;
            }
            catch { }
        }
        return n;
    }
}
