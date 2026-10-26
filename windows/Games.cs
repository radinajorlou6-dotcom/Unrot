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

    [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [StructLayout(LayoutKind.Sequential)] struct RECT { public int Left, Top, Right, Bottom; }

    // ---------------------------------------------------------------- game library index
    // Anti-cheat (EA FC, eFootball, Valorant…) often hides a game's file location from other programs,
    // so games are also recognized by program name, from a scan of your Steam and Epic libraries.

    static Dictionary<string, string> _index = new(StringComparer.OrdinalIgnoreCase);
    static long _indexedAt;
    static bool _indexing;

    public static int IndexedCount => _index.Count;

    /// <summary>Rescans the libraries in the background every 10 minutes.</summary>
    public static void RefreshIndexIfStale()
    {
        if (_indexing || Store.Now - _indexedAt < 10 * 60_000) return;
        _indexing = true;
        Task.Run(() =>
        {
            try { _index = BuildIndex(); }
            catch { }
            finally { _indexedAt = Store.Now; _indexing = false; }
        });
    }

    static Dictionary<string, string> BuildIndex()
    {
        var map = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        var opts = new EnumerationOptions { RecurseSubdirectories = true, MaxRecursionDepth = 4, IgnoreInaccessible = true };
        foreach (var lib in SteamLibraries())
        {
            var common = Path.Combine(lib, "steamapps", "common");
            if (!Directory.Exists(common)) continue;
            foreach (var game in Directory.EnumerateDirectories(common))
            {
                try
                {
                    foreach (var exe in Directory.EnumerateFiles(game, "*.exe", opts))
                        if (IsGame(exe)) map.TryAdd(Path.GetFileName(exe), exe);
                }
                catch { }
            }
        }
        foreach (var exe in EpicGames())
            if (IsGame(exe)) map.TryAdd(Path.GetFileName(exe), exe);
        return map;
    }

    static IEnumerable<string> SteamLibraries()
    {
        var libs = new List<string>();
        try
        {
            using var k = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(@"Software\Valve\Steam");
            var steam = (k?.GetValue("SteamPath") as string)?.Replace('/', '\\');
            if (string.IsNullOrEmpty(steam)) return libs;
            libs.Add(steam);
            var vdf = Path.Combine(steam, "steamapps", "libraryfolders.vdf");
            if (File.Exists(vdf))
            {
                foreach (System.Text.RegularExpressions.Match m in
                         System.Text.RegularExpressions.Regex.Matches(File.ReadAllText(vdf), "\"path\"\\s+\"(.+?)\""))
                    libs.Add(m.Groups[1].Value.Replace("\\\\", "\\"));
            }
        }
        catch { }
        return libs.Distinct(StringComparer.OrdinalIgnoreCase);
    }

    static IEnumerable<string> EpicGames()
    {
        var list = new List<string>();
        try
        {
            var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
                "Epic", "EpicGamesLauncher", "Data", "Manifests");
            if (!Directory.Exists(dir)) return list;
            foreach (var f in Directory.EnumerateFiles(dir, "*.item"))
            {
                try
                {
                    using var doc = System.Text.Json.JsonDocument.Parse(File.ReadAllText(f));
                    var root = doc.RootElement;
                    if (root.TryGetProperty("InstallLocation", out var loc) && root.TryGetProperty("LaunchExecutable", out var exe))
                        list.Add(Path.Combine(loc.GetString() ?? "", exe.GetString() ?? ""));
                }
                catch { }
            }
        }
        catch { }
        return list;
    }

    /// <summary>Steam's own "a game is running" flag (0 = nothing running).</summary>
    public static int SteamRunningAppId()
    {
        try
        {
            using var k = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(@"Software\Valve\Steam");
            return k?.GetValue("RunningAppID") is int id ? id : 0;
        }
        catch { return 0; }
    }

    /// <summary>Program name without needing access to the process (works even with anti-cheat).</summary>
    static string ProcName(int pid)
    {
        try { using var p = Process.GetProcessById(pid); return p.ProcessName + ".exe"; }
        catch { return null; }
    }

    /// <summary>Is this process a game? Returns the game's path (or name) if so.</summary>
    public static string GameOf(int pid)
    {
        var path = ExePath((uint)pid);
        if (path != null) return IsGame(path) ? path : null;
        var name = ProcName(pid);
        if (name == null) return null;
        if (_index.TryGetValue(name, out var indexed) && IsGame(indexed)) return indexed;
        if (KnownGameExes.Contains(name.ToLowerInvariant())) return name;
        return null;
    }

    /// <summary>Things that are never "the game", even full-screen while a Steam game runs in the background.</summary>
    static readonly string[] NeverGame =
    {
        "explorer.exe", "steam.exe", "steamwebhelper.exe", "discord.exe", "unrot.exe", "vlc.exe", "mpc-hc64.exe",
        "code.exe", "devenv.exe", "searchhost.exe", "startmenuexperiencehost.exe", "shellexperiencehost.exe",
        "lockapp.exe", "textinputhost.exe", "taskmgr.exe",
    };

    static bool IsFullScreen(IntPtr hwnd)
    {
        if (!GetWindowRect(hwnd, out var r)) return false;
        var b = Screen.FromHandle(hwnd).Bounds;
        return r.Left <= b.Left && r.Top <= b.Top && r.Right >= b.Right && r.Bottom >= b.Bottom;
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
        var game = GameOf((int)pid);
        if (game != null) return ((int)pid, game);
        // Backup: Steam says a game is running and something full-screen (that isn't a browser etc.) is in front.
        var name = ProcName((int)pid)?.ToLowerInvariant();
        if (name != null && SteamRunningAppId() != 0 && IsFullScreen(hwnd) &&
            !NeverGame.Contains(name) && !Browsers.Contains(name) &&
            !Store.S.IgnoredGames.Any(g => Rules.Same(Path.GetFileName(g), name)))
            return ((int)pid, name);
        return null;
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
                var game = GameOf(proc.Id);
                if (game != null) list.Add((proc.Id, game));
            }
            catch { }
            finally { proc.Dispose(); }
        }
        return list;
    }

    /// <summary>
    /// Closes running games: first asks politely (like clicking X), then force-closes. If anti-cheat blocks
    /// both, sends Alt+F4 to the game in front.
    /// </summary>
    public static int CloseAll(int extraPid = 0)
    {
        var targets = Running().Select(r => r.Pid).ToList();
        if (extraPid > 0 && !targets.Contains(extraPid)) targets.Add(extraPid);
        int n = 0;
        foreach (var pid in targets)
        {
            try
            {
                using var proc = Process.GetProcessById(pid);
                try { proc.CloseMainWindow(); } catch { }
                if (!proc.WaitForExit(3000))
                {
                    try { proc.Kill(true); } catch { }
                }
                if (proc.WaitForExit(2000)) { n++; continue; }
            }
            catch { continue; }
            // Still alive: Alt+F4 if it's the window in front.
            var fg = GetForegroundWindow();
            GetWindowThreadProcessId(fg, out var fgPid);
            if (fgPid == pid)
            {
                try { SendKeys.SendWait("%{F4}"); n++; } catch { }
            }
        }
        return n;
    }
}
