using System.Globalization;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Unrot;

/// <summary>A whole-number rule (same list and meaning as on the phone). LooserIfHigher decides which way waits 24h.</summary>
public record NumRule(string Key, string Label, int Def, int Min, int Max, bool LooserIfHigher, string Unit)
{
    public string Fmt(int v) => Unit switch
    {
        "steps" => v.ToString("N0", CultureInfo.CurrentCulture) + " steps",
        "" => v.ToString(),
        "%" => v + "%",
        _ => $"{v} {Unit}",
    };
}

public static class NumRules
{
    public static readonly List<NumRule> All = new()
    {
        new("dailyCapMin", "Most doomscrolling per day (phone apps + social sites)", 120, 15, 1440, true, "min"),
        new("streakTargetMin", "Streak: stay under this much doomscrolling a day", 60, 5, 600, true, "min"),
        new("streak3Min", "Streak bonus at 3 days", 10, 0, 240, true, "min"),
        new("streak7Min", "Streak bonus at 7 days", 30, 0, 240, true, "min"),
        new("streak30Min", "Streak bonus at 30 days (and every 30 after)", 60, 0, 600, true, "min"),
        new("phoneDownPct", "Phone-down bonus (screen off all lock-in)", 20, 0, 100, true, "%"),
        new("engagingCostPct", "Games & movies cost (% of normal)", 50, 10, 100, false, "%"),
        new("earlyEndPct", "Pay for ending a lock-in early (% of normal)", 50, 0, 100, true, "%"),
        new("introWaitSec", "Wait before \"Continue\" on the opening card", 15, 0, 300, false, "s"),
        new("callRewardMin", "Minutes earned per family call", 10, 1, 60, true, "min"),
        new("callMinMin", "Shortest call that counts", 5, 1, 60, false, "min"),
        new("callCooldownH", "Hours before the same person pays again", 3, 0, 48, false, "h"),
        new("workoutEffortPct", "Workouts: effort that counts (% of the way from resting to max heart rate)", 30, 5, 80, false, "%"),
        new("maxHr", "Your max heart rate", 195, 140, 220, false, "bpm"),
        new("workoutRestMin", "Workouts: rest between sets that still counts", 3, 0, 15, true, "min"),
        new("stepsUnit", "Steps per step reward", 1000, 100, 20000, false, "steps"),
        new("stepsRewardMin", "Minutes per step reward", 2, 1, 60, true, "min"),
        new("stepsCapMin", "Most minutes from steps per day", 30, 0, 600, true, "min"),
        new("sleepBonusMin", "Bonus for being asleep by bedtime", 15, 0, 120, true, "min"),
        new("runPrBonusMin", "Bonus for beating your best run pace", 15, 0, 120, true, "min"),
        new("liftPrBonusMin", "Bonus per gym PR (FitNotes)", 15, 0, 120, true, "min"),
        new("liftPrCapMin", "Most gym-PR minutes per day", 45, 0, 600, true, "min"),
    };

    static readonly Dictionary<string, NumRule> Map = All.ToDictionary(r => r.Key);
    public static NumRule ByKey(string k) => Map[k];
    public static bool Has(string k) => Map.ContainsKey(k);
}

/// <summary>
/// Your rules, shared with the phone (same format the phone saves, so either device can change anything).
/// Ratios mean "minutes of real stuff per 1 minute of fun", so 3 = lock in 3 minutes, earn 1.
/// </summary>
public class Settings
{
    public const string ShortsKey = "yt:shorts";

    public static readonly string[] DefaultFlagged =
    {
        ShortsKey, "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.instagram.android", "com.google.android.youtube",
        "com.reddit.frontpage", "com.twitter.android", "com.snapchat.android", "com.facebook.katana",
    };

    [JsonPropertyName("workoutRatio")] public double WorkoutRatio { get; set; } = 3;
    [JsonPropertyName("focusRatio")] public double FocusRatio { get; set; } = 3;
    [JsonPropertyName("freeRatio")] public double FreeRatio { get; set; } = 5;
    [JsonPropertyName("callRatio")] public double CallRatio { get; set; } = 3;
    [JsonPropertyName("dailyBaseMin")] public int DailyBaseMin { get; set; } = 10;
    [JsonPropertyName("maxBankMin")] public int MaxBankMin { get; set; } = 180;
    [JsonPropertyName("checkInMin")] public int CheckInMin { get; set; } = 10;
    [JsonPropertyName("bedStart")] public int BedStart { get; set; } = 23 * 60;
    [JsonPropertyName("bedEnd")] public int BedEnd { get; set; } = 7 * 60;
    [JsonPropertyName("bedWaitSec")] public int BedWaitSec { get; set; } = 60;
    [JsonPropertyName("countWalks")] public bool CountWalks { get; set; }
    [JsonPropertyName("protection")] public bool Protection { get; set; } = true;
    /// <summary>Blocked phone apps (package names, plus "yt:shorts").</summary>
    [JsonPropertyName("flagged")] public List<string> Flagged { get; set; } = DefaultFlagged.ToList();
    [JsonPropertyName("nums")] public Dictionary<string, int> Nums { get; set; } = new();
    /// <summary>Blocked things that cost the cheaper "engaging" rate: phone apps, and sites as "site:domain".</summary>
    [JsonPropertyName("halfRate")] public List<string> HalfRate { get; set; } = new();
    [JsonPropertyName("sites")] public List<string> SiteDomains { get; set; } = BlockedSite.Defaults().Select(x => x.Domain).ToList();
    [JsonPropertyName("graceSec")] public int GraceSec { get; set; } = 30;
    [JsonPropertyName("paybackMult")] public double PaybackMult { get; set; } = 1;
    /// <summary>What to look for in a tab title for each site (alternatives separated by |).</summary>
    [JsonPropertyName("siteKeywords")] public Dictionary<string, string> SiteKeywords { get; set; } = new();
    [JsonPropertyName("laptopExtraGames")] public List<string> LaptopExtraGames { get; set; } = new();
    [JsonPropertyName("laptopIgnoredGames")] public List<string> LaptopIgnoredGames { get; set; } = new();
    /// <summary>Anything a newer phone version adds, kept as-is.</summary>
    [JsonExtensionData] public Dictionary<string, JsonElement> Extra { get; set; }

    public int N(string key) => Nums.TryGetValue(key, out var v) ? v : NumRules.ByKey(key).Def;
    [JsonIgnore] public int EngagingCostPct => N("engagingCostPct");
    [JsonIgnore] public int EarlyEndPct => N("earlyEndPct");
    [JsonIgnore] public int DailyCapMin => N("dailyCapMin");
    [JsonIgnore] public bool BedtimeOn => BedStart != BedEnd;

    public bool InBedtime(int m) =>
        BedtimeOn && (BedStart < BedEnd ? m >= BedStart && m < BedEnd : m >= BedStart || m < BedEnd);

    /// <summary>Blocked sites with what to look for in tab titles.</summary>
    [JsonIgnore]
    public List<BlockedSite> Sites => SiteDomains
        .Select(d => new BlockedSite { Domain = d, Keywords = KeywordsFor(d), Half = HalfRate.Contains("site:" + d) })
        .ToList();

    static readonly Dictionary<string, string> KnownKeywords =
        BlockedSite.Defaults().ToDictionary(x => x.Domain, x => x.Keywords, StringComparer.OrdinalIgnoreCase);

    public string KeywordsFor(string domain)
    {
        if (SiteKeywords.TryGetValue(domain, out var k) && !string.IsNullOrWhiteSpace(k)) return k;
        if (KnownKeywords.TryGetValue(domain, out var known)) return known;
        var b = domain.Split('.')[0];
        return b.Length > 0 ? char.ToUpper(b[0]) + b[1..] : domain;
    }

    public Settings Clone() => JsonSerializer.Deserialize<Settings>(JsonSerializer.Serialize(this));

    /// <summary>Builds the shared rules from what older laptop versions saved.</summary>
    public static Settings FromLegacy(LegacySettings l, List<string> extra, List<string> ignored)
    {
        var s = new Settings
        {
            FocusRatio = l.FocusRatio,
            FreeRatio = l.FreeRatio,
            MaxBankMin = l.MaxBankMin,
            GraceSec = l.GraceSec,
            Protection = l.Protection,
            PaybackMult = l.PaybackMult,
            SiteDomains = (l.Sites ?? BlockedSite.Defaults()).Select(x => x.Domain).Distinct(StringComparer.OrdinalIgnoreCase).ToList(),
            HalfRate = (l.Sites ?? new()).Where(x => x.Half).Select(x => "site:" + x.Domain).ToList(),
            SiteKeywords = (l.Sites ?? new()).Where(x => !string.IsNullOrWhiteSpace(x.Keywords))
                .GroupBy(x => x.Domain, StringComparer.OrdinalIgnoreCase).ToDictionary(g => g.Key, g => g.First().Keywords),
            LaptopExtraGames = (extra ?? new()).ToList(),
            LaptopIgnoredGames = (ignored ?? new()).ToList(),
        };
        s.Nums["engagingCostPct"] = l.EngagingCostPct;
        s.Nums["earlyEndPct"] = l.EarlyEndPct;
        s.Nums["dailyCapMin"] = l.DailyCapMin;
        return s;
    }

    /// <summary>Old laptop pending-change keys → the shared ones.</summary>
    public static string LegacyKey(string k) => k switch
    {
        "engagingCostPct" or "earlyEndPct" or "dailyCapMin" => "num:" + k,
        _ when k.StartsWith("halfsite:") => "half:site:" + k["halfsite:".Length..],
        _ => k,
    };

    /// <summary>
    /// The first time the phone and laptop share rules: each side keeps the rules that are its own
    /// (phone apps, workouts, bedtime… / laptop games, save warning…), and for rules they both use
    /// (ratios, sites, daily limit…) the stricter of the two wins, so syncing can never loosen anything.
    /// </summary>
    public static Settings MergeFirst(Settings phone, Settings laptop)
    {
        var m = phone.Clone();
        foreach (var k in new[] { "engagingCostPct", "earlyEndPct", "dailyCapMin" })
        {
            var r = NumRules.ByKey(k);
            int a = phone.N(k), b = laptop.N(k);
            m.Nums[k] = r.LooserIfHigher ? Math.Min(a, b) : Math.Max(a, b);
        }
        m.FocusRatio = Math.Max(phone.FocusRatio, laptop.FocusRatio);
        m.FreeRatio = Math.Max(phone.FreeRatio, laptop.FreeRatio);
        m.MaxBankMin = Math.Min(phone.MaxBankMin, laptop.MaxBankMin);
        m.Protection = phone.Protection || laptop.Protection;
        var sites = phone.SiteDomains.Concat(laptop.SiteDomains).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        var half = sites.Where(d =>
        {
            var k = "site:" + d;
            bool inP = phone.SiteDomains.Contains(d, StringComparer.OrdinalIgnoreCase);
            bool inL = laptop.SiteDomains.Contains(d, StringComparer.OrdinalIgnoreCase);
            return inP && inL ? phone.HalfRate.Contains(k) && laptop.HalfRate.Contains(k)
                : inP ? phone.HalfRate.Contains(k) : laptop.HalfRate.Contains(k);
        }).Select(d => "site:" + d);
        m.SiteDomains = sites;
        m.HalfRate = phone.HalfRate.Where(x => !x.StartsWith("site:")).Concat(half).ToList();
        m.GraceSec = laptop.GraceSec;
        m.PaybackMult = laptop.PaybackMult;
        m.SiteKeywords = new(laptop.SiteKeywords);
        m.LaptopExtraGames = laptop.LaptopExtraGames.ToList();
        m.LaptopIgnoredGames = laptop.LaptopIgnoredGames.ToList();
        return m;
    }
}

/// <summary>
/// Stricter changes apply instantly. Looser changes wait 24 hours, unless the PIN holder has unlocked
/// Unrot in the last 10 minutes. Rules are shared with the phone through sync.
/// </summary>
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
                Apply(Store.S.Shared, p.Key, p.Value);
                Store.S.Pending.Remove(p);
                Store.AddLog("Rule change kicked in: " + p.Label);
            }
            Touch();
            Store.Save();
        }
        return Store.S.Shared;
    }

    static void Touch() => Store.S.RulesUpdated = Store.Now;

    public static bool Same(string a, string b) => string.Equals(a, b, StringComparison.OrdinalIgnoreCase);

    static void Apply(Settings s, string key, string v)
    {
        double D() => double.Parse(v, CultureInfo.InvariantCulture);
        int I() => (int)Math.Round(double.Parse(v, CultureInfo.InvariantCulture));
        switch (key)
        {
            case "workoutRatio": s.WorkoutRatio = D(); break;
            case "focusRatio": s.FocusRatio = D(); break;
            case "freeRatio": s.FreeRatio = D(); break;
            case "callRatio": s.CallRatio = D(); break;
            case "dailyBaseMin": s.DailyBaseMin = I(); break;
            case "maxBankMin": s.MaxBankMin = I(); break;
            case "checkInMin": s.CheckInMin = I(); break;
            case "bedWaitSec": s.BedWaitSec = I(); break;
            case "graceSec": s.GraceSec = I(); break;
            case "paybackMult": s.PaybackMult = D(); break;
            case "countWalks": s.CountWalks = bool.Parse(v); break;
            case "protection": s.Protection = bool.Parse(v); break;
            case "bedtime":
                var parts = v.Split('-');
                s.BedStart = int.Parse(parts[0]);
                s.BedEnd = int.Parse(parts[1]);
                break;
            default:
                if (key.StartsWith("num:")) s.Nums[key[4..]] = I();
                else if (key.StartsWith("unflag:")) s.Flagged.Remove(key["unflag:".Length..]);
                else if (key.StartsWith("half:")) { var k = key["half:".Length..]; if (!s.HalfRate.Contains(k)) s.HalfRate.Add(k); }
                else if (key.StartsWith("unsite:"))
                {
                    var d = key["unsite:".Length..];
                    s.SiteDomains.RemoveAll(x => Same(x, d));
                    s.HalfRate.Remove("site:" + d);
                }
                else if (key.StartsWith("unextra:")) s.LaptopExtraGames.RemoveAll(g => Same(g, key["unextra:".Length..]));
                else if (key.StartsWith("ignore:"))
                {
                    var g = key["ignore:".Length..];
                    if (!s.LaptopIgnoredGames.Any(x => Same(x, g))) s.LaptopIgnoredGames.Add(g);
                    s.LaptopExtraGames.RemoveAll(x => Same(x, g));
                }
                break;
        }
    }

    static HashSet<int> BedMinutes(int start, int end)
    {
        var tmp = new Settings { BedStart = start, BedEnd = end };
        return Enumerable.Range(0, 1440).Where(tmp.InBedtime).ToHashSet();
    }

    static string F(double d) => d.ToString(CultureInfo.InvariantCulture);

    /// <summary>Applies whatever's stricter in <paramref name="next"/> now and queues the rest. Returns how many wait 24h.</summary>
    public static int Propose(Settings next, Func<string, string> labelFor = null)
    {
        labelFor ??= k => k.StartsWith("site:") ? k[5..] : k == Settings.ShortsKey ? "YouTube Shorts" : PhoneApps.Label(k);
        var old = Current();
        var cur = old.Clone();
        var unlocked = Pin.Unlocked;
        int delayed = 0;

        void Handle(string key, string ov, string nv, bool looser, string label)
        {
            if (ov == nv) return;
            Store.S.Pending.RemoveAll(p => p.Key == key);
            if (looser && !unlocked)
            {
                Store.S.Pending.Add(new PendingChange { Key = key, Value = nv, ApplyAt = Store.Now + DelayMs, Label = label });
                delayed++;
            }
            else Apply(cur, key, nv);
        }

        Handle("workoutRatio", F(old.WorkoutRatio), F(next.WorkoutRatio), next.WorkoutRatio < old.WorkoutRatio, $"Workout ratio → {Ledger.FmtRatio(next.WorkoutRatio)}:1");
        Handle("focusRatio", F(old.FocusRatio), F(next.FocusRatio), next.FocusRatio < old.FocusRatio, $"Lock-in ratio → {Ledger.FmtRatio(next.FocusRatio)}:1");
        Handle("freeRatio", F(old.FreeRatio), F(next.FreeRatio), next.FreeRatio < old.FreeRatio, $"Free lock-in ratio → {Ledger.FmtRatio(next.FreeRatio)}:1");
        foreach (var r in NumRules.All)
        {
            int o = old.N(r.Key), v = next.N(r.Key);
            Handle("num:" + r.Key, o.ToString(), v.ToString(), r.LooserIfHigher ? v > o : v < o, $"{r.Label} → {r.Fmt(v)}");
        }
        Handle("dailyBaseMin", old.DailyBaseMin.ToString(), next.DailyBaseMin.ToString(), next.DailyBaseMin > old.DailyBaseMin, $"Free daily minutes → {next.DailyBaseMin}");
        Handle("maxBankMin", old.MaxBankMin.ToString(), next.MaxBankMin.ToString(), next.MaxBankMin > old.MaxBankMin, $"Max saved time → {next.MaxBankMin} min");
        var checkLooser = next.CheckInMin == 0 ? old.CheckInMin != 0 : old.CheckInMin != 0 && next.CheckInMin > old.CheckInMin;
        Handle("checkInMin", old.CheckInMin.ToString(), next.CheckInMin.ToString(), checkLooser,
            next.CheckInMin == 0 ? "Scroll check-ins → off" : $"Scroll check-in → every {next.CheckInMin} min");
        var bedLooser = !BedMinutes(next.BedStart, next.BedEnd).IsSupersetOf(BedMinutes(old.BedStart, old.BedEnd));
        Handle("bedtime", $"{old.BedStart}-{old.BedEnd}", $"{next.BedStart}-{next.BedEnd}", bedLooser,
            next.BedtimeOn ? $"Bedtime → {Clock(next.BedStart)}–{Clock(next.BedEnd)}" : "Bedtime → off");
        Handle("bedWaitSec", old.BedWaitSec.ToString(), next.BedWaitSec.ToString(), next.BedWaitSec < old.BedWaitSec, $"Bedtime wait → {next.BedWaitSec}s");
        Handle("countWalks", old.CountWalks ? "true" : "false", next.CountWalks ? "true" : "false", next.CountWalks && !old.CountWalks,
            $"Count walks → {(next.CountWalks ? "on" : "off")}");
        Handle("protection", old.Protection ? "true" : "false", next.Protection ? "true" : "false", !next.Protection,
            $"Protection → {(next.Protection ? "on" : "off")}");
        Handle("graceSec", old.GraceSec.ToString(), next.GraceSec.ToString(), next.GraceSec > old.GraceSec, $"Laptop save-your-game warning → {next.GraceSec}s");
        Handle("paybackMult", F(old.PaybackMult), F(next.PaybackMult), next.PaybackMult < old.PaybackMult,
            $"Laptop overtime payback → {next.PaybackMult.ToString("0.##", CultureInfo.InvariantCulture)}×");
        cur.SiteKeywords = new(next.SiteKeywords);

        // Blocking a new phone app is instant; unblocking one waits.
        foreach (var p in next.Flagged.Except(old.Flagged).ToList())
        {
            cur.Flagged.Add(p);
            Store.S.Pending.RemoveAll(x => x.Key == "unflag:" + p);
        }
        foreach (var p in old.Flagged.Except(next.Flagged).ToList())
            Handle("unflag:" + p, "0", "1", true, "Unblock " + labelFor(p));

        // Making something full price is instant. Making an already-blocked thing cheaper waits.
        foreach (var k in old.HalfRate.Except(next.HalfRate).ToList())
        {
            cur.HalfRate.Remove(k);
            Store.S.Pending.RemoveAll(x => x.Key == "half:" + k);
        }
        foreach (var k in next.HalfRate.Except(old.HalfRate).ToList())
        {
            var wasFull = old.Flagged.Contains(k) || (k.StartsWith("site:") && old.SiteDomains.Any(d => Same(d, k[5..])));
            Handle("half:" + k, "0", "1", wasFull, $"{labelFor(k)} costs {next.EngagingCostPct}%");
        }

        // Sites: same idea.
        foreach (var d in next.SiteDomains.Where(d => !old.SiteDomains.Any(o => Same(o, d))).ToList())
        {
            cur.SiteDomains.Add(d);
            Store.S.Pending.RemoveAll(x => x.Key == "unsite:" + d);
        }
        foreach (var d in old.SiteDomains.Where(d => !next.SiteDomains.Any(n => Same(n, d))).ToList())
            Handle("unsite:" + d, "0", "1", true, "Unblock " + d);

        // Laptop games: adding (or un-ignoring) is instant; removing or ignoring waits.
        foreach (var g in next.LaptopExtraGames.Where(g => !old.LaptopExtraGames.Any(o => Same(o, g))).ToList())
        {
            cur.LaptopExtraGames.Add(g);
            cur.LaptopIgnoredGames.RemoveAll(x => Same(x, g));
            Store.S.Pending.RemoveAll(x => x.Key == "unextra:" + g || x.Key == "ignore:" + g);
        }
        foreach (var g in old.LaptopExtraGames.Where(g => !next.LaptopExtraGames.Any(n => Same(n, g)) && !next.LaptopIgnoredGames.Any(n => Same(n, g))).ToList())
            Handle("unextra:" + g, "0", "1", true, "Stop counting " + Path.GetFileName(g));
        foreach (var g in old.LaptopIgnoredGames.Where(g => !next.LaptopIgnoredGames.Any(n => Same(n, g))).ToList())
            cur.LaptopIgnoredGames.RemoveAll(x => Same(x, g));
        foreach (var g in next.LaptopIgnoredGames.Where(g => !old.LaptopIgnoredGames.Any(o => Same(o, g))).ToList())
            Handle("ignore:" + g, "0", "1", true, "Not a game: " + Path.GetFileName(g));

        Store.S.Shared = cur;
        Touch();
        Store.Save();
        return delayed;
    }

    public static string Clock(int m)
    {
        var h = m / 60 % 24;
        var mm = m % 60;
        var h12 = h % 12 == 0 ? 12 : h % 12;
        var ampm = h < 12 ? "am" : "pm";
        return mm == 0 ? $"{h12}{ampm}" : $"{h12}:{mm:00}{ampm}";
    }

    // ---- the small helpers the screens use (all go through Propose)

    public static void AddGame(string path)
    {
        var n = Current().Clone();
        if (!n.LaptopExtraGames.Any(g => Same(g, path))) n.LaptopExtraGames.Add(path);
        n.LaptopIgnoredGames.RemoveAll(g => Same(g, path));
        Propose(n);
        Store.AddLog("Now counting " + Path.GetFileName(path) + " as a game");
    }

    public static bool RemoveGame(string path)
    {
        var n = Current().Clone();
        n.LaptopExtraGames.RemoveAll(g => Same(g, path));
        return Propose(n) == 0;
    }

    public static bool IgnoreGame(string path)
    {
        var n = Current().Clone();
        if (!n.LaptopIgnoredGames.Any(g => Same(g, path))) n.LaptopIgnoredGames.Add(path);
        return Propose(n) == 0;
    }

    public static void UnignoreGame(string path)
    {
        var n = Current().Clone();
        n.LaptopIgnoredGames.RemoveAll(g => Same(g, path));
        Propose(n);
    }

    public static void AddSite(string domain, string keywords, bool half)
    {
        var n = Current().Clone();
        if (!n.SiteDomains.Any(d => Same(d, domain))) n.SiteDomains.Add(domain);
        if (!string.IsNullOrWhiteSpace(keywords)) n.SiteKeywords[domain] = keywords;
        if (half && !n.HalfRate.Contains("site:" + domain)) n.HalfRate.Add("site:" + domain);
        Propose(n);
        Store.AddLog("Now blocking " + domain);
    }

    public static bool RemoveSite(string domain)
    {
        var n = Current().Clone();
        n.SiteDomains.RemoveAll(d => Same(d, domain));
        return Propose(n) == 0;
    }

    /// <summary>Full price is instant; making a site cheaper waits.</summary>
    public static bool ToggleSiteHalf(BlockedSite site)
    {
        var n = Current().Clone();
        var k = "site:" + site.Domain;
        if (!n.HalfRate.Remove(k)) n.HalfRate.Add(k);
        return Propose(n) == 0;
    }

    public static void Cancel(string key)
    {
        Store.S.Pending.RemoveAll(p => p.Key == key);
        Touch();
        Store.Save();
    }

    // ---- shared with the phone

    static readonly JsonSerializerOptions Camel = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase, PropertyNameCaseInsensitive = true };

    /// <summary>Everything both devices share: the rules, changes waiting to kick in, and the family PIN.</summary>
    public static string SharedDoc()
    {
        var st = Current();
        return JsonSerializer.Serialize(new Dictionary<string, object>
        {
            ["settings"] = st,
            ["pending"] = Store.S.Pending,
            ["updated"] = Store.S.RulesUpdated,
            ["pinSalt"] = Store.S.PinSalt,
            ["pinHash"] = Store.S.PinHash,
        }, Camel);
    }

    static Settings ReadSettings(JsonElement doc) =>
        doc.TryGetProperty("settings", out var s) && s.ValueKind == JsonValueKind.Object
            ? JsonSerializer.Deserialize<Settings>(s.GetRawText()) : null;

    static List<PendingChange> ReadPending(JsonElement doc) =>
        doc.TryGetProperty("pending", out var p) && p.ValueKind == JsonValueKind.Array
            ? JsonSerializer.Deserialize<List<PendingChange>>(p.GetRawText(), Camel) ?? new() : new();

    static void AdoptPin(JsonElement doc)
    {
        var hash = Ledger.Str(doc, "pinHash", "");
        if (hash.Length > 0 && hash != Store.S.PinHash)
        {
            Store.S.PinHash = hash;
            Store.S.PinSalt = Ledger.Str(doc, "pinSalt", "");
        }
    }

    /// <summary>Takes the newer rules another device saved, as they are (their waits were applied there).</summary>
    public static void Adopt(JsonElement doc)
    {
        var st = ReadSettings(doc);
        if (st == null) return;
        Normalize(st);
        Store.S.Shared = st;
        Store.S.Pending = ReadPending(doc);
        Store.S.RulesUpdated = Ledger.Num(doc, "updated");
        AdoptPin(doc);
        Store.Save();
    }

    /// <summary>The first time rules meet the phone's: see <see cref="Settings.MergeFirst"/>.</summary>
    public static void MergeFirst(JsonElement doc)
    {
        var theirs = ReadSettings(doc) ?? new Settings();
        Normalize(theirs);
        var mine = Current();
        Store.S.Shared = Settings.MergeFirst(theirs, mine);
        foreach (var p in ReadPending(doc))
            if (!Store.S.Pending.Any(x => x.Key == p.Key)) Store.S.Pending.Add(p);
        if (Store.S.PinHash.Length == 0) AdoptPin(doc);
        Touch();
        Store.AddLog("Rules now shared with your phone (the stricter of each kept)");
        Store.Save();
    }

    public static void Normalize(Settings s)
    {
        s.Flagged ??= new();
        s.Nums ??= new();
        s.HalfRate ??= new();
        s.SiteDomains ??= new();
        s.SiteKeywords ??= new();
        s.LaptopExtraGames ??= new();
        s.LaptopIgnoredGames ??= new();
    }
}

/// <summary>The apps installed on your phone (sent by the phone), for picking blocked apps here.</summary>
public static class PhoneApps
{
    public record App(string Pkg, string Label, bool Game);

    public static List<App> All()
    {
        try
        {
            using var doc = JsonDocument.Parse(string.IsNullOrWhiteSpace(Store.S.PhoneAppsJson) ? "{}" : Store.S.PhoneAppsJson);
            if (doc.RootElement.ValueKind != JsonValueKind.Object) return new();
            return doc.RootElement.EnumerateObject()
                .Select(p => new App(p.Name.Replace(',', '.'), Ledger.Str(p.Value, "label", p.Name),
                    p.Value.TryGetProperty("game", out var g) && g.ValueKind == JsonValueKind.True))
                .ToList();
        }
        catch { return new(); }
    }

    public static string Label(string pkg) =>
        pkg == Settings.ShortsKey ? "YouTube Shorts" : All().FirstOrDefault(a => a.Pkg == pkg)?.Label ?? pkg;
}
