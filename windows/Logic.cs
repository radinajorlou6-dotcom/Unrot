using System.Globalization;
using System.Text.Json;

namespace Unrot;

/// <summary>The shared time balance (same math as the phone app).</summary>
public static class Ledger
{
    public record Remote(string Id, string Name, long Earned, long Spent, long FocusUntil);

    static string _cacheRaw;
    static List<Remote> _cache = new();

    public static List<Remote> Remotes()
    {
        var raw = Store.S.RemoteJson ?? "{}";
        if (raw == _cacheRaw) return _cache;
        var list = new List<Remote>();
        try
        {
            using var doc = JsonDocument.Parse(raw);
            if (doc.RootElement.ValueKind == JsonValueKind.Object)
            {
                foreach (var p in doc.RootElement.EnumerateObject())
                {
                    if (p.Name == Store.S.DeviceId || p.Value.ValueKind != JsonValueKind.Object) continue;
                    list.Add(new Remote(p.Name, Str(p.Value, "name", "device"), Num(p.Value, "earned"),
                        Num(p.Value, "spent"), Num(p.Value, "focusUntil")));
                }
            }
        }
        catch { }
        _cacheRaw = raw;
        _cache = list;
        return list;
    }

    static long Num(JsonElement e, string key) =>
        e.TryGetProperty(key, out var v) && v.ValueKind == JsonValueKind.Number ? (long)v.GetDouble() : 0;

    static string Str(JsonElement e, string key, string d) =>
        e.TryGetProperty(key, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : d;

    public static long Balance() => Store.S.EarnedSec - Store.S.SpentSec + Remotes().Sum(r => r.Earned - r.Spent);

    public static long Credit(long sec, string reason)
    {
        if (sec <= 0) return 0;
        var cap = Rules.Current().MaxBankMin * 60L;
        var add = Math.Min(sec, Math.Max(0, cap - Balance()));
        Store.S.EarnedSec += add;
        Store.AddLog($"+{FmtShort(add)} · {reason}" + (add < sec ? " (bank full)" : ""));
        return add;
    }

    public static void Spend(long sec) => Store.S.SpentSec += sec;

    public static bool LocalFocus => Store.S.FocusUntil > Store.Now;
    public static long RemoteFocusUntil => Remotes().Select(r => r.FocusUntil).DefaultIfEmpty(0).Max();
    public static bool FocusActive => LocalFocus || RemoteFocusUntil > Store.Now;

    public static long FocusRemainingSec =>
        Math.Max(0, (Math.Max(Store.S.FocusUntil, RemoteFocusUntil) - Store.Now) / 1000);

    public static void StartFocus(int minutes)
    {
        Store.S.FocusPlannedSec = minutes * 60L;
        Store.S.FocusUntil = Store.Now + minutes * 60_000L;
        Store.AddLog($"Locked in for {minutes} min");
    }

    public static void CancelFocus()
    {
        Store.S.FocusUntil = 0;
        Store.S.FocusPlannedSec = 0;
        Store.AddLog("Ended a lock-in early (no time earned)");
    }

    /// <summary>Pays out a finished lock-in. Returns seconds earned or -1.</summary>
    public static long SettleFocus()
    {
        if (Store.S.FocusUntil == 0 || Store.Now < Store.S.FocusUntil) return -1;
        var planned = Store.S.FocusPlannedSec;
        Store.S.FocusUntil = 0;
        Store.S.FocusPlannedSec = 0;
        return Credit((long)(planned / Rules.Current().FocusRatio), $"locked in {planned / 60} min");
    }

    public static string Fmt(long sec)
    {
        if (sec < 0) sec = 0;
        var h = sec / 3600; var m = sec % 3600 / 60; var s = sec % 60;
        return h > 0 ? $"{h}:{m:00}:{s:00}" : $"{m}:{s:00}";
    }

    public static string FmtShort(long sec)
    {
        var m = sec / 60; var s = sec % 60;
        if (m == 0) return $"{s}s";
        return s == 0 ? $"{m} min" : $"{m} min {s}s";
    }

    public static string FmtRatio(double r) =>
        r == Math.Floor(r) ? ((long)r).ToString(CultureInfo.InvariantCulture) : r.ToString("0.0", CultureInfo.InvariantCulture);
}

/// <summary>Stricter changes apply now; looser ones wait 24 hours unless the PIN unlocked Unrot.</summary>
public static class Rules
{
    public const long DelayMs = 24L * 60 * 60 * 1000;

    public static Settings Current()
    {
        var due = Store.S.Pending.Where(p => p.ApplyAt <= Store.Now).ToList();
        if (due.Count > 0)
        {
            foreach (var p in due)
            {
                Apply(p.Key, p.Value);
                Store.S.Pending.Remove(p);
                Store.AddLog("Rule change kicked in: " + p.Label);
            }
            Store.Save();
        }
        return Store.S.Settings;
    }

    static void Apply(string key, string v)
    {
        var s = Store.S.Settings;
        switch (key)
        {
            case "focusRatio": s.FocusRatio = double.Parse(v, CultureInfo.InvariantCulture); break;
            case "maxBankMin": s.MaxBankMin = int.Parse(v); break;
            case "graceSec": s.GraceSec = int.Parse(v); break;
            case "protection": s.Protection = bool.Parse(v); break;
            case "engagingCostPct": s.EngagingCostPct = int.Parse(v); break;
            default:
                if (key.StartsWith("ignore:")) Store.S.IgnoredGames.Add(key["ignore:".Length..]);
                else if (key.StartsWith("unextra:")) Store.S.ExtraGames.RemoveAll(g => Same(g, key["unextra:".Length..]));
                else if (key.StartsWith("unsite:")) s.Sites.RemoveAll(x => Same(x.Domain, key["unsite:".Length..]));
                else if (key.StartsWith("halfsite:"))
                {
                    var site = s.Sites.FirstOrDefault(x => Same(x.Domain, key["halfsite:".Length..]));
                    if (site != null) site.Half = true;
                }
                break;
        }
    }

    public static bool Same(string a, string b) => string.Equals(a, b, StringComparison.OrdinalIgnoreCase);

    /// <summary>Returns true if applied now, false if it was queued for 24h.</summary>
    static bool Change(string key, string value, bool looser, string label)
    {
        Store.S.Pending.RemoveAll(p => p.Key == key);
        if (looser && !Pin.Unlocked)
        {
            Store.S.Pending.Add(new PendingChange { Key = key, Value = value, ApplyAt = Store.Now + DelayMs, Label = label });
            Store.Save();
            return false;
        }
        Apply(key, value);
        Store.Save();
        return true;
    }

    public static int ProposeSettings(double focusRatio, int maxBankMin, int graceSec, bool protection, int engagingPct)
    {
        var old = Current();
        int delayed = 0;
        if (engagingPct != old.EngagingCostPct && !Change("engagingCostPct", engagingPct.ToString(),
                engagingPct < old.EngagingCostPct, $"Games & movies cost → {engagingPct}%")) delayed++;
        if (focusRatio != old.FocusRatio && !Change("focusRatio", focusRatio.ToString(CultureInfo.InvariantCulture),
                focusRatio < old.FocusRatio, $"Lock-in ratio → {Ledger.FmtRatio(focusRatio)}:1")) delayed++;
        if (maxBankMin != old.MaxBankMin && !Change("maxBankMin", maxBankMin.ToString(),
                maxBankMin > old.MaxBankMin, $"Max saved time → {maxBankMin} min")) delayed++;
        if (graceSec != old.GraceSec && !Change("graceSec", graceSec.ToString(),
                graceSec > old.GraceSec, $"Save-your-game warning → {graceSec}s")) delayed++;
        if (protection != old.Protection && !Change("protection", protection.ToString(),
                !protection, $"Protection → {(protection ? "on" : "off")}")) delayed++;
        return delayed;
    }

    public static void AddGame(string path)
    {
        Store.S.IgnoredGames.RemoveAll(g => Same(g, path));
        Store.S.Pending.RemoveAll(p => p.Key == "ignore:" + path || p.Key == "unextra:" + path);
        if (!Store.S.ExtraGames.Any(g => Same(g, path))) Store.S.ExtraGames.Add(path);
        Store.AddLog("Now counting " + Path.GetFileName(path) + " as a game");
    }

    public static bool RemoveGame(string path) =>
        Change("unextra:" + path, "1", true, "Stop counting " + Path.GetFileName(path));

    public static bool IgnoreGame(string path) =>
        Change("ignore:" + path, "1", true, "Not a game: " + Path.GetFileName(path));

    public static void AddSite(string domain, string keywords, bool half)
    {
        Store.S.Pending.RemoveAll(p => p.Key == "unsite:" + domain);
        var existing = Store.S.Settings.Sites.FirstOrDefault(x => Same(x.Domain, domain));
        if (existing != null) { existing.Keywords = keywords; }
        else Store.S.Settings.Sites.Add(new BlockedSite { Domain = domain, Keywords = keywords, Half = half });
        Store.AddLog("Now blocking " + domain);
    }

    public static bool RemoveSite(string domain) =>
        Change("unsite:" + domain, "1", true, "Unblock " + domain);

    /// <summary>Full price is instant; making a site cheaper waits.</summary>
    public static bool ToggleSiteHalf(BlockedSite site)
    {
        if (site.Half)
        {
            site.Half = false;
            Store.S.Pending.RemoveAll(p => p.Key == "halfsite:" + site.Domain);
            Store.Save();
            return true;
        }
        return Change("halfsite:" + site.Domain, "1", true, $"{site.Domain} costs {Store.S.Settings.EngagingCostPct}%");
    }

    public static void Cancel(string key)
    {
        Store.S.Pending.RemoveAll(p => p.Key == key);
        Store.Save();
    }
}

/// <summary>Shares the balance with the phone through Firebase Realtime Database.</summary>
public static class Sync
{
    static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(8) };

    public static bool Configured => (Store.S.DbUrl ?? "").StartsWith("https://") && (Store.S.SyncCode ?? "").Length >= 8;

    static string Base => Store.S.DbUrl.Trim().TrimEnd('/') + "/unrot/" + Store.S.SyncCode.Trim();

    public static string NewCode()
    {
        const string chars = "abcdefghjkmnpqrstuvwxyz23456789";
        return new string(Enumerable.Range(0, 20)
            .Select(_ => chars[System.Security.Cryptography.RandomNumberGenerator.GetInt32(chars.Length)]).ToArray());
    }

    public static async Task<bool> RunAsync()
    {
        if (!Configured) return false;
        try
        {
            var mine = JsonSerializer.Serialize(new Dictionary<string, object>
            {
                ["name"] = "laptop",
                ["earned"] = Store.S.EarnedSec,
                ["spent"] = Store.S.SpentSec,
                ["focusUntil"] = Store.S.FocusUntil,
                ["updated"] = Store.Now,
            });
            var put = await Http.PutAsync($"{Base}/devices/{Store.S.DeviceId}.json",
                new StringContent(mine, System.Text.Encoding.UTF8, "application/json"));
            put.EnsureSuccessStatusCode();
            var all = await Http.GetStringAsync($"{Base}/devices.json");
            Store.S.RemoteJson = all.Trim() == "null" ? "{}" : all;
            Store.S.LastSyncTs = Store.Now;
            Store.S.LastSyncError = "";
            Store.Save();
            return true;
        }
        catch (Exception e)
        {
            Store.S.LastSyncError = e.Message;
            return false;
        }
    }
}
