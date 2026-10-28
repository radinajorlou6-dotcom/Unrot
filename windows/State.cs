using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Unrot;

public class Settings
{
    /// <summary>Minutes locked in per 1 minute of game time.</summary>
    public double FocusRatio { get; set; } = 3;
    public int MaxBankMin { get; set; } = 180;
    /// <summary>Seconds you get to save before a game is closed.</summary>
    public int GraceSec { get; set; } = 30;
    public bool Protection { get; set; } = true;
    /// <summary>Games and movie sites cost this % of normal (procrastinating, but engaging).</summary>
    public int EngagingCostPct { get; set; } = 50;
    /// <summary>Ending a lock-in early pays this % of the normal rate for the time done.</summary>
    public int EarlyEndPct { get; set; } = 50;
    /// <summary>Free-form lock-in (stop whenever): minutes per 1 minute earned.</summary>
    public double FreeRatio { get; set; } = 5;
    /// <summary>Overtime minutes cost this many times normal (paid back from future earnings).</summary>
    public double PaybackMult { get; set; } = 1;
    /// <summary>Most doomscrolling (social sites here + full-price apps on the phone) per day.</summary>
    public int DailyCapMin { get; set; } = 120;
    /// <summary>A lock-in pauses after this many minutes with no mouse/keyboard (0 = never).</summary>
    public int IdleMin { get; set; } = 10;
    public List<BlockedSite> Sites { get; set; } = BlockedSite.Defaults();
}

/// <summary>A site blocked in the browser. Matched by the tab title, since that's what Windows can see.</summary>
public class BlockedSite
{
    public string Domain { get; set; }
    /// <summary>Text that appears in the tab title, alternatives separated by |</summary>
    public string Keywords { get; set; }
    /// <summary>Movies/streaming: costs EngagingCostPct instead of full price.</summary>
    public bool Half { get; set; }

    public static List<BlockedSite> Defaults() => new()
    {
        new() { Domain = "youtube.com", Keywords = "YouTube" },
        new() { Domain = "instagram.com", Keywords = "Instagram" },
        new() { Domain = "x.com", Keywords = " / X|on X:" },
        new() { Domain = "twitter.com", Keywords = "Twitter" },
        new() { Domain = "tiktok.com", Keywords = "TikTok" },
        new() { Domain = "reddit.com", Keywords = "Reddit|: r/" },
        new() { Domain = "facebook.com", Keywords = "Facebook" },
        new() { Domain = "snapchat.com", Keywords = "Snapchat" },
        new() { Domain = "cinejoy.pk", Keywords = "Cinejoy", Half = true },
        new() { Domain = "netflix.com", Keywords = "Netflix", Half = true },
        new() { Domain = "disneyplus.com", Keywords = "Disney+", Half = true },
        new() { Domain = "primevideo.com", Keywords = "Prime Video", Half = true },
        new() { Domain = "crunchyroll.com", Keywords = "Crunchyroll", Half = true },
        new() { Domain = "twitch.tv", Keywords = "Twitch", Half = true },
    };
}

public class PendingChange
{
    public string Key { get; set; }
    public string Value { get; set; }
    public long ApplyAt { get; set; }
    public string Label { get; set; }
}

public class LogLine
{
    public string Text { get; set; }
    public long Ts { get; set; }
}

public class AppState
{
    public string DeviceId { get; set; } = "laptop-" + Guid.NewGuid().ToString("N")[..8];
    public long EarnedSec { get; set; }
    public long SpentSec { get; set; }
    public long FocusUntil { get; set; }
    public long FocusPlannedSec { get; set; }
    public Settings Settings { get; set; } = new();
    public List<PendingChange> Pending { get; set; } = new();
    public string PinHash { get; set; } = "";
    public string PinSalt { get; set; } = "";
    public long AdminUntil { get; set; }
    public List<string> ExtraGames { get; set; } = new();
    public List<string> IgnoredGames { get; set; } = new();
    public string DbUrl { get; set; } = "";
    public string SyncCode { get; set; } = "";
    public string RemoteJson { get; set; } = "{}";
    public long LastSyncTs { get; set; }
    public string LastSyncError { get; set; } = "";
    public bool Welcomed { get; set; }
    public bool MovieSitesAdded { get; set; }
    public bool FocusFree { get; set; }
    public long FocusStart { get; set; }
    public string DoomDay { get; set; } = "";
    public long DoomSec { get; set; }
    public string DoomPrevDay { get; set; } = "";
    public long DoomPrevSec { get; set; }
    public long OvertimeUntil { get; set; }
    public long OvertimeSpentStart { get; set; }
    public List<LogLine> Log { get; set; } = new();
}

public static class Store
{
    public static readonly string Dir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Unrot");
    static string FilePath => Path.Combine(Dir, "state.json");
    static readonly JsonSerializerOptions Opts = new() { WriteIndented = true };

    public static AppState S { get; private set; } = new();

    public static long Now => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    public static void Load()
    {
        try
        {
            if (File.Exists(FilePath))
                S = JsonSerializer.Deserialize<AppState>(File.ReadAllText(FilePath), Opts) ?? new AppState();
        }
        catch
        {
            // A corrupted file shouldn't brick the app; start fresh but keep a copy.
            try { File.Copy(FilePath, FilePath + ".broken", true); } catch { }
            S = new AppState();
        }
        S.Settings ??= new Settings();
        S.Settings.Sites ??= BlockedSite.Defaults();
        if (!S.MovieSitesAdded)
        {
            // One-time: add movie sites that came after the first version.
            foreach (var d in BlockedSite.Defaults().Where(x => x.Half))
                if (!S.Settings.Sites.Any(x => string.Equals(x.Domain, d.Domain, StringComparison.OrdinalIgnoreCase)))
                    S.Settings.Sites.Add(d);
            S.MovieSitesAdded = true;
        }
        S.Pending ??= new();
        S.ExtraGames ??= new();
        S.IgnoredGames ??= new();
        S.Log ??= new();
        Save();
    }

    public static void Save()
    {
        try
        {
            Directory.CreateDirectory(Dir);
            var tmp = FilePath + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(S, Opts));
            File.Move(tmp, FilePath, true);
        }
        catch { }
    }

    public static void AddLog(string text)
    {
        S.Log.Insert(0, new LogLine { Text = text, Ts = Now });
        if (S.Log.Count > 40) S.Log.RemoveRange(40, S.Log.Count - 40);
        Save();
    }
}

public static class Pin
{
    public const long UnlockMs = 10 * 60 * 1000;

    public static bool IsSet => Store.S.PinHash.Length > 0;
    public static bool Unlocked => Store.S.AdminUntil > Store.Now;

    public static void Set(string pin)
    {
        Store.S.PinSalt = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
        Store.S.PinHash = Hash(Store.S.PinSalt, pin);
        Store.S.AdminUntil = 0;
        Store.Save();
    }

    public static bool Check(string pin)
    {
        if (!IsSet) return false;
        var ok = Hash(Store.S.PinSalt, pin) == Store.S.PinHash;
        if (ok)
        {
            Store.S.AdminUntil = Store.Now + UnlockMs;
            Store.Save();
        }
        return ok;
    }

    public static void Lock()
    {
        Store.S.AdminUntil = 0;
        Store.Save();
    }

    static string Hash(string salt, string pin) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(salt + pin))).ToLowerInvariant();
}
