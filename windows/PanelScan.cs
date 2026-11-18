using System.Collections.Concurrent;
using UIA = System.Windows.Automation;

namespace Unrot;

/// <summary>
/// Browsers can show sites outside the main tab: Vivaldi's side panels, Edge's sidebar, Opera's messengers.
/// The window title only names the main tab, so those are invisible to it. This reads the web pages actually
/// showing in each browser window (through Windows' accessibility interface), in the background every few
/// seconds, and remembers their addresses.
/// </summary>
public static class PanelScan
{
    static readonly ConcurrentDictionary<IntPtr, (long At, List<string> Urls)> Cache = new();
    static readonly ConcurrentDictionary<IntPtr, bool> Busy = new();

    /// <summary>Addresses of the pages showing in this browser window (last scan), starting a new scan if due.</summary>
    public static List<string> Urls(IntPtr hwnd)
    {
        var now = Store.Now;
        var hit = Cache.TryGetValue(hwnd, out var c) ? c : (0, new List<string>());
        if (now - hit.At > 4000 && Busy.TryAdd(hwnd, true))
        {
            Task.Run(() =>
            {
                try { Cache[hwnd] = (Store.Now, Scan(hwnd)); }
                catch { Cache[hwnd] = (Store.Now, new List<string>()); }
                finally { Busy.TryRemove(hwnd, out _); }
            });
        }
        // Forget windows that went away.
        if (Cache.Count > 30)
            foreach (var k in Cache.Where(x => now - x.Value.At > 60_000).Select(x => x.Key).ToList()) Cache.TryRemove(k, out _);
        return hit.Item2;
    }

    /// <summary>
    /// Walks the window's accessibility tree looking for web pages. The browser's own interface (Vivaldi draws
    /// its UI as a page too) is walked into; real sites aren't (their contents don't matter, only the address).
    /// </summary>
    static List<string> Scan(IntPtr hwnd)
    {
        var urls = new List<string>();
        var root = UIA.AutomationElement.FromHandle(hwnd);
        var walker = UIA.TreeWalker.ControlViewWalker;
        var queue = new Queue<(UIA.AutomationElement El, int Depth)>();
        queue.Enqueue((root, 0));
        var visited = 0;
        while (queue.Count > 0 && visited < 3000)
        {
            var (el, depth) = queue.Dequeue();
            visited++;
            var descend = true;
            try
            {
                if (el.Current.ControlType == UIA.ControlType.Document &&
                    el.TryGetCurrentPattern(UIA.ValuePattern.Pattern, out var p))
                {
                    var v = ((UIA.ValuePattern)p).Current.Value ?? "";
                    if (v.StartsWith("http", StringComparison.OrdinalIgnoreCase))
                    {
                        if (!el.Current.IsOffscreen) urls.Add(v);
                        descend = false;
                    }
                }
            }
            catch { continue; }
            if (!descend || depth >= 40) continue;
            try
            {
                var child = walker.GetFirstChild(el);
                while (child != null)
                {
                    queue.Enqueue((child, depth + 1));
                    child = walker.GetNextSibling(child);
                }
            }
            catch { }
        }
        return urls;
    }

    public static string Host(string url)
    {
        try { return new Uri(url).Host.ToLowerInvariant().Replace("www.", ""); }
        catch { return ""; }
    }

    /// <summary>Does this address belong to the blocked site (e.g. x.com, mobile.x.com)?</summary>
    public static bool Matches(string url, string domain)
    {
        var h = Host(url);
        var d = domain.ToLowerInvariant();
        return h.Length > 0 && (h == d || h.EndsWith("." + d));
    }
}
