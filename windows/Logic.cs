using System.Globalization;
using System.Text.Json;

namespace Unrot;

/// <summary>The shared time balance (same math as the phone app).</summary>
public static class Ledger
{
    public record Remote(string Id, string Name, long Earned, long Spent, long FocusUntil, bool FocusFree,
        string DoomDay, long DoomSec, string DoomPrevDay, long DoomPrevSec, double RateNow = 0, long Updated = 0)
    {
        public long DoomOn(string day) => day == DoomDay ? DoomSec : day == DoomPrevDay ? DoomPrevSec : 0;
    }

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
                        Num(p.Value, "spent"), Num(p.Value, "focusUntil"),
                        p.Value.TryGetProperty("focusFree", out var ff) && ff.ValueKind == JsonValueKind.True,
                        Str(p.Value, "doomDay", ""), Num(p.Value, "doomSec"),
                        Str(p.Value, "doomPrevDay", ""), Num(p.Value, "doomPrevSec"),
                        p.Value.TryGetProperty("rateNow", out var rn) && rn.ValueKind == JsonValueKind.Number ? rn.GetDouble() : 0,
                        Num(p.Value, "updated")));
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
        Stat(d => d.Earned += add);
        Store.AddLog($"+{FmtShort(add)} · {reason}" + (add < sec ? " (bank full)" : ""));
        return add;
    }

    public static void Spend(long sec) => Store.S.SpentSec += sec;

    /// <summary>Updates today's stats (keeps 14 days).</summary>
    public static void Stat(Action<DayStat> change)
    {
        var t = Today;
        if (!Store.S.Days.TryGetValue(t, out var d))
        {
            d = new DayStat();
            Store.S.Days[t] = d;
            foreach (var old in Store.S.Days.Keys.OrderBy(k => k).SkipLast(14).ToList()) Store.S.Days.Remove(old);
        }
        change(d);
    }

    public static DayStat DayOf(string day) => Store.S.Days.TryGetValue(day, out var d) ? d : new DayStat();

    // ---------------------------------------------------------------- daily doomscroll cap

    public static string Today => DateTime.Now.ToString("yyyy-MM-dd");

    public static void Rollover()
    {
        var t = Today;
        if (Store.S.DoomDay == t) return;
        Store.S.DoomPrevDay = Store.S.DoomDay;
        Store.S.DoomPrevSec = Store.S.DoomSec;
        Store.S.DoomDay = t;
        Store.S.DoomSec = 0;
    }

    public static void AddDoom(long sec)
    {
        Rollover();
        Store.S.DoomSec += sec;
    }

    /// <summary>Doomscrolling today on this laptop plus a synced phone.</summary>
    public static long DoomToday()
    {
        var t = Today;
        return (Store.S.DoomDay == t ? Store.S.DoomSec : 0) + Remotes().Sum(r => r.DoomOn(t));
    }

    /// <summary>How fast the phone is spending right now (if it synced in the last 75 seconds).</summary>
    public static double PhoneRate => Remotes().Where(r => Store.Now - r.Updated < 75_000).Select(r => r.RateNow).DefaultIfEmpty(0).Max();

    public static bool RemoteFocusFree => Remotes().Any(r => r.FocusUntil > Store.Now && r.FocusFree);

    // ---------------------------------------------------------------- overtime

    public const int OvertimeMin = 20;
    public static bool OvertimeActive => Store.S.OvertimeUntil > Store.Now;

    public static void StartOvertime()
    {
        Store.S.OvertimeUntil = Store.Now + OvertimeMin * 60_000L;
        Store.S.OvertimeSpentStart = Store.S.SpentSec;
        Store.AddLog($"Overtime: up to {OvertimeMin} min to finish the match (borrowed)");
    }

    public static void EndOvertime()
    {
        if (Store.S.OvertimeUntil == 0) return;
        var borrowed = Math.Max(0, Store.S.SpentSec - Store.S.OvertimeSpentStart);
        Store.S.OvertimeUntil = 0;
        Store.AddLog($"Overtime over: borrowed {FmtShort(borrowed)}, paid back from what you earn next");
    }

    // ---------------------------------------------------------------- free lock-in

    public const long FreeMaxMs = 4L * 3600 * 1000;

    public static void StartFree()
    {
        Store.S.FocusFree = true;
        Store.S.FocusStart = Store.Now;
        Store.S.FocusPlannedSec = FreeMaxMs / 1000;
        Store.S.FocusUntil = Store.Now + FreeMaxMs;
        Store.AddLog("Started a free lock-in");
    }

    public static long FreeElapsedSec =>
        !Store.S.FocusFree || Store.S.FocusUntil == 0 ? 0 :
        Math.Max(0, (Math.Min(Store.Now, Store.S.FocusUntil) - Store.S.FocusStart) / 1000);

    public static long FreePayout => (long)(FreeElapsedSec / Rules.Current().FreeRatio);

    /// <summary>
    /// Stops a free lock-in and pays for the time done, or only <paramref name="keepSec"/> of it if part of it
    /// wasn't real (you forgot to stop it).
    /// </summary>
    public static long StopFree(long keepSec = long.MaxValue)
    {
        if (Store.S.FocusUntil == 0 || !Store.S.FocusFree) return 0;
        var ran = FreeElapsedSec;
        var done = Math.Clamp(keepSec, 0, ran);
        var pay = (long)(done / Rules.Current().FreeRatio);
        ClearFocus();
        Stat(d => d.Locked += done);
        var trimmed = done < ran ? $" (trimmed from {ran / 60} min)" : "";
        if (pay > 0) return Credit(pay, $"free lock-in, {done / 60} min{trimmed}");
        Store.AddLog("Ended a free lock-in" + trimmed);
        return 0;
    }

    /// <summary>What <see cref="SettleFocus"/> returns when a free lock-in hit the max: nothing paid until you review it.</summary>
    public const long NeedsReview = -2;

    /// <summary>A free lock-in that ran all the way to the max was almost certainly forgotten: park it unpaid.</summary>
    static void ParkFree()
    {
        var ran = FreeElapsedSec;
        ClearFocus();
        Store.S.FreeReviewSec += ran;
        Store.AddLog($"Free lock-in hit the {FreeMaxMs / 3_600_000}-hour max. Unpaid until you say how long you really worked");
    }

    /// <summary>Pays a parked free lock-in for the seconds you say were real.</summary>
    public static long ResolveFreeReview(long keepSec)
    {
        var ran = Store.S.FreeReviewSec;
        if (ran <= 0) return 0;
        var kept = Math.Clamp(keepSec, 0, ran);
        Store.S.FreeReviewSec = 0;
        Stat(d => d.Locked += kept);
        var pay = (long)(kept / Rules.Current().FreeRatio);
        if (pay > 0) return Credit(pay, $"free lock-in, {kept / 60} min (you said, out of {ran / 60})");
        Store.AddLog("Free lock-in that hit the max: you kept 0 min");
        return 0;
    }

    /// <summary>
    /// Takes time off your balance on purpose, to keep yourself honest (e.g. a lock-in you forgot to stop).
    /// Never below zero, and it doesn't count as doomscrolling. Returns seconds removed.
    /// </summary>
    public static long GiveBack(long sec)
    {
        var take = Math.Min(sec, Math.Max(0, Balance()));
        if (take <= 0) return 0;
        Store.S.EarnedSec -= take;
        Stat(d => d.Earned -= take);
        Store.AddLog($"−{FmtShort(take)} · gave time back (keeping it honest)");
        return take;
    }

    static void ClearFocus()
    {
        Store.S.FocusUntil = 0;
        Store.S.FocusPlannedSec = 0;
        Store.S.FocusFree = false;
    }

    /// <summary>
    /// The old idle check pushed lock-ins back whenever the mouse and keyboard sat still (e.g. working on paper).
    /// Runs once: puts a running lock-in back on its real schedule, and pays back free lock-ins it shortchanged.
    /// Rebuilt from the activity log ("Locked in…", "Lock-in paused: no activity for N min", "Lock-in resumed").
    /// </summary>
    public static void RefundIdlePausesOnce()
    {
        if (Store.S.IdleRefundDone) return;
        Store.S.IdleRefundDone = true;
        try
        {
            var log = Store.S.Log.OrderBy(l => l.Ts).ToList();
            bool inFree = false;
            long pausedAt = 0, idleMs = 0, lost = 0, lastStart = 0;
            var startRe = new System.Text.RegularExpressions.Regex(@"^Locked in for (\d+) min$");
            var pauseRe = new System.Text.RegularExpressions.Regex(@"^Lock-in paused: no activity for (\d+) min$");
            int lastMinutes = 0;
            void EndPause(long at)
            {
                if (pausedAt == 0) return;
                if (inFree) lost += Math.Max(0, at - pausedAt) + idleMs;
                pausedAt = 0;
            }
            foreach (var l in log)
            {
                var t = l.Text ?? "";
                var ms = startRe.Match(t);
                var mp = pauseRe.Match(t);
                if (t == "Started a free lock-in") { inFree = true; lastStart = l.Ts; lastMinutes = 0; pausedAt = 0; }
                else if (ms.Success) { inFree = false; lastStart = l.Ts; lastMinutes = int.Parse(ms.Groups[1].Value); pausedAt = 0; }
                else if (mp.Success) { pausedAt = l.Ts; idleMs = long.Parse(mp.Groups[1].Value) * 60_000; }
                else if (t == "Lock-in resumed") EndPause(l.Ts);
                else if (inFree && (t.Contains("free lock-in") || t == "Ended a free lock-in")) { EndPause(l.Ts); inFree = false; }
            }
            // A lock-in that's still running: put it back on its real schedule.
            if (LocalFocus && lastStart > 0)
            {
                if (Store.S.FocusFree && inFree)
                {
                    Store.S.FocusStart = lastStart;
                    Store.S.FocusUntil = lastStart + FreeMaxMs;
                    lost = 0;
                }
                else if (!Store.S.FocusFree && lastMinutes > 0)
                {
                    Store.S.FocusUntil = Math.Max(Store.Now + 1000, lastStart + lastMinutes * 60_000L);
                }
            }
            var pay = (long)(lost / 1000 / Rules.Current().FreeRatio);
            if (pay > 0) Credit(pay, "refund for lock-in time the idle check didn't count");
            Store.Save();
        }
        catch { }
    }

    public static bool LocalFocus => Store.S.FocusUntil > Store.Now;
    public static long RemoteFocusUntil => Remotes().Select(r => r.FocusUntil).DefaultIfEmpty(0).Max();
    public static bool FocusActive => LocalFocus || RemoteFocusUntil > Store.Now;

    public static long FocusRemainingSec =>
        Math.Max(0, (Math.Max(Store.S.FocusUntil, RemoteFocusUntil) - Store.Now) / 1000);

    public static void StartFocus(int minutes)
    {
        Store.S.FocusFree = false;
        Store.S.FocusStart = Store.Now;
        Store.S.FocusPlannedSec = minutes * 60L;
        Store.S.FocusUntil = Store.Now + minutes * 60_000L;
        Store.AddLog($"Locked in for {minutes} min");
    }

    /// <summary>Seconds you'd get for ending now: the time done, at the early-end rate.</summary>
    public static long EarlyEndPayout()
    {
        if (Store.S.FocusUntil == 0) return 0;
        var done = Math.Max(0, Store.S.FocusPlannedSec - Math.Max(0, (Store.S.FocusUntil - Store.Now) / 1000));
        var st = Rules.Current();
        return (long)(done / st.FocusRatio * st.EarlyEndPct / 100.0);
    }

    public static long CancelFocus()
    {
        if (Store.S.FocusFree) return StopFree();
        var pay = EarlyEndPayout();
        var doneSec = Math.Max(0, Store.S.FocusPlannedSec - Math.Max(0, (Store.S.FocusUntil - Store.Now) / 1000));
        Stat(d => d.Locked += doneSec);
        var doneMin = Math.Max(0, Store.S.FocusPlannedSec - Math.Max(0, (Store.S.FocusUntil - Store.Now) / 1000)) / 60;
        Store.S.FocusUntil = 0;
        Store.S.FocusPlannedSec = 0;
        if (pay > 0) Credit(pay, $"ended a lock-in early after {doneMin} min ({Rules.Current().EarlyEndPct}% rate)");
        else Store.AddLog("Ended a lock-in early");
        return pay;
    }

    /// <summary>Pays out a finished lock-in. Returns seconds earned or -1.</summary>
    public static long SettleFocus()
    {
        if (Store.S.FocusUntil == 0 || Store.Now < Store.S.FocusUntil) return -1;
        if (Store.S.FocusFree) { ParkFree(); return NeedsReview; }
        var planned = Store.S.FocusPlannedSec;
        Stat(d => d.Locked += planned);
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
            case "earlyEndPct": s.EarlyEndPct = int.Parse(v); break;
            case "freeRatio": s.FreeRatio = double.Parse(v, CultureInfo.InvariantCulture); break;
            case "paybackMult": s.PaybackMult = double.Parse(v, CultureInfo.InvariantCulture); break;
            case "dailyCapMin": s.DailyCapMin = int.Parse(v); break;
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

    public static int ProposeSettings(double focusRatio, int maxBankMin, int graceSec, bool protection, int engagingPct, int earlyEndPct)
    {
        var old = Current();
        int delayed = 0;
        if (earlyEndPct != old.EarlyEndPct && !Change("earlyEndPct", earlyEndPct.ToString(),
                earlyEndPct > old.EarlyEndPct, $"Early-end pay → {earlyEndPct}%")) delayed++;
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

    /// <summary>The newer rules. Same deal: stricter now, looser in 24h.</summary>
    public static int ProposeExtra(double freeRatio, double paybackMult, int dailyCapMin)
    {
        var old = Current();
        int delayed = 0;
        if (freeRatio != old.FreeRatio && !Change("freeRatio", freeRatio.ToString(CultureInfo.InvariantCulture),
                freeRatio < old.FreeRatio, $"Free lock-in ratio → {Ledger.FmtRatio(freeRatio)}:1")) delayed++;
        if (paybackMult != old.PaybackMult && !Change("paybackMult", paybackMult.ToString(CultureInfo.InvariantCulture),
                paybackMult < old.PaybackMult, $"Overtime payback → {paybackMult.ToString("0.##", CultureInfo.InvariantCulture)}×")) delayed++;
        if (dailyCapMin != old.DailyCapMin && !Change("dailyCapMin", dailyCapMin.ToString(),
                dailyCapMin > old.DailyCapMin, $"Daily doomscroll limit → {dailyCapMin} min")) delayed++;
        return delayed;
    }

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
                ["focusFree"] = Store.S.FocusFree,
                ["doomDay"] = Store.S.DoomDay,
                ["doomSec"] = Store.S.DoomSec,
                ["doomPrevDay"] = Store.S.DoomPrevDay,
                ["doomPrevSec"] = Store.S.DoomPrevSec,
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
