using System.Globalization;

namespace Unrot;

/// <summary>The window you get from the tray icon.</summary>
public class MainForm : Form
{
    readonly TrayApp _app;
    readonly FlowLayoutPanel _panel;
    readonly System.Windows.Forms.Timer _live = new() { Interval = 1000 };
    Label _balance, _focusLine;
    const int W = 540;

    public MainForm(TrayApp app)
    {
        _app = app;
        Text = "Unrot";
        Icon = Theme.MakeIcon();
        Size = new Size(620, 860);
        MinimumSize = new Size(520, 500);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Theme.Bg;
        ForeColor = Theme.Text;
        Font = new Font("Segoe UI", 10);

        _panel = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            AutoScroll = true,
            Padding = new Padding(24, 20, 24, 20),
        };
        Controls.Add(_panel);

        _live.Tick += (_, _) => Live();
        _live.Start();
        // Closing the window just hides it; Unrot keeps running in the tray.
        FormClosing += (_, e) =>
        {
            if (e.CloseReason == CloseReason.UserClosing) { e.Cancel = true; Hide(); }
        };
        Rebuild();
    }

    static string BalanceText()
    {
        var b = Ledger.Balance();
        return b < 0 ? "-" + Ledger.Fmt(-b) : Ledger.Fmt(b);
    }

    void Live()
    {
        if (!Visible || _balance == null) return;
        _balance.Text = BalanceText();
        if (_focusLine != null)
        {
            if (!Ledger.FocusActive) Rebuild();
            else if (Store.S.FocusFree && Ledger.LocalFocus)
                _focusLine.Text = $"🔒 Free lock-in · {Ledger.Fmt(Ledger.FreeElapsedSec)} so far · +{Ledger.FmtShort(Ledger.FreePayout)}";
            else _focusLine.Text = $"🔒 Locked in · {Ledger.Fmt(Ledger.FocusRemainingSec)} left";
        }
    }

    Label L(string text, float size = 10, Color? color = null, bool bold = false) =>
        Theme.Label(text, size, color ?? Theme.Text, bold, W);

    void Add(Control c) => _panel.Controls.Add(c);

    void Section(string title)
    {
        var l = L(title, 14, Theme.Text, true);
        l.Margin = new Padding(0, 18, 0, 6);
        Add(l);
    }

    FlowLayoutPanel Row()
    {
        var r = new FlowLayoutPanel { AutoSize = true, FlowDirection = FlowDirection.LeftToRight, WrapContents = true, MaximumSize = new Size(W, 0) };
        Add(r);
        return r;
    }

    public void Rebuild()
    {
        if (IsDisposed) return;
        _panel.SuspendLayout();
        _panel.Controls.Clear();
        _focusLine = null;
        var st = Rules.Current();

        // ---- Balance
        Add(L("UNROT", 9, Theme.Muted, true));
        var balNow = Ledger.Balance();
        _balance = L(BalanceText(), 40, balNow < 0 ? Theme.Warn : Theme.Accent, true);
        _balance.Margin = new Padding(0);
        Add(_balance);
        Add(L(balNow > 0 ? "of game time (shared with your phone if synced)"
            : balNow < 0 ? $"You owe {Ledger.FmtShort(-balNow)} from overtime. What you earn next pays it back first."
            : "Out of game time. Lock in to earn more.", 10, balNow < 0 ? Theme.Warn : Theme.Muted));
        if (Ledger.OvertimeActive)
            Add(L($"⏱ Overtime: {Ledger.Fmt((Store.S.OvertimeUntil - Store.Now) / 1000)} left to finish the match", 11, Theme.Warn, true));
        var doom = Ledger.DoomToday();
        Add(L($"📱 Doomscrolled today: {doom / 60} of {st.DailyCapMin} min (social sites here + scroll apps on your phone)", 9,
            doom >= st.DailyCapMin * 60L ? Theme.Warn : Theme.Muted));

        // ---- Lock in
        Section("Lock in");
        if (Ledger.LocalFocus && Store.S.FocusFree)
        {
            _focusLine = L($"🔒 Free lock-in · {Ledger.Fmt(Ledger.FreeElapsedSec)} so far · +{Ledger.FmtShort(Ledger.FreePayout)}", 13, Theme.Accent, true);
            Add(_focusLine);
            Add(L($"Stop whenever; you're paid for every minute at {Ledger.FmtRatio(st.FreeRatio)}:1. 10 min without touching the mouse or keyboard pauses it.", 10, Theme.Muted));
            Row().Controls.Add(Theme.Button("Stop & collect", () =>
            {
                var got = Ledger.StopFree();
                Store.Save();
                _ = _app.SyncNow();
                MessageBox.Show($"+{Ledger.FmtShort(got)} 💪", "Unrot");
                Rebuild();
            }, true));
        }
        else if (Ledger.FocusActive)
        {
            _focusLine = L($"🔒 Locked in · {Ledger.Fmt(Ledger.FocusRemainingSec)} left", 13, Theme.Accent, true);
            Add(_focusLine);
            if (Ledger.LocalFocus)
            {
                Add(L($"Finish to earn {Ledger.FmtShort((long)(Store.S.FocusPlannedSec / st.FocusRatio))}.", 10, Theme.Muted));
                Row().Controls.Add(Theme.Button($"End early ({st.EarlyEndPct}% pay)", () =>
                {
                    var pay = Ledger.EarlyEndPayout();
                    var full = (long)(Store.S.FocusPlannedSec / st.FocusRatio);
                    if (MessageBox.Show($"You'll get {Ledger.FmtShort(pay)} for the time you've done ({st.EarlyEndPct}% rate) instead of {Ledger.FmtShort(full)} for finishing. End it?", "Unrot", MessageBoxButtons.YesNo) == DialogResult.Yes)
                    {
                        Ledger.CancelFocus();
                        Store.Save();
                        _ = _app.SyncNow();
                        Rebuild();
                    }
                }));
            }
            else Add(L(Ledger.RemoteFocusFree ? "Free lock-in running on your phone." : "Started on your phone.", 10, Theme.Muted));
        }
        else
        {
            Add(L($"Study or work. Every {Ledger.FmtRatio(st.FocusRatio)} min earns 1 min of game time, paid when the timer ends (end early and you get {st.EarlyEndPct}% for the time you did). Games (and your phone's blocked apps) are off until then.", 10, Theme.Muted));
            var r = Row();
            foreach (var m in new[] { 25, 45, 60, 90 })
                r.Controls.Add(Theme.Button($"{m} min  (+{Ledger.FmtShort((long)(m * 60 / st.FocusRatio))})", () => { _app.StartFocus(m); Rebuild(); }, m == 25));
            Row().Controls.Add(Theme.Button($"Free lock-in · stop anytime · {Ledger.FmtRatio(st.FreeRatio)}:1", () => { _app.StartFree(); Rebuild(); }));
            if (st.IdleMin > 0) Add(L($"Lock-ins pause after {st.IdleMin} min without mouse or keyboard.", 9, Theme.Muted));
        }

        // ---- Games
        Section("Games");
        Add(L($"Anything from Steam, Epic, Riot, Xbox, EA, Ubisoft, GOG, Rockstar or Roblox counts automatically, at {st.EngagingCostPct}% of the normal cost. Time counts while the game is showing on any screen (minimize it to stop the clock).", 10, Theme.Muted));
        var steamApp = Games.SteamRunningAppId();
        Add(L(steamApp != 0 ? $"Steam says a game is running right now (app {steamApp}). Full-screen windows count as that game." :
            $"Steam isn't running a game right now. {Games.IndexedCount} games found in your Steam/Epic libraries.", 9, Theme.Muted));
        var running = Games.Running();
        if (running.Count > 0)
        {
            Add(L("Running now:", 10, Theme.Text, true));
            foreach (var g in running.GroupBy(x => x.Path, StringComparer.OrdinalIgnoreCase).Select(x => x.First()))
            {
                var r = Row();
                r.Controls.Add(L(Path.GetFileName(g.Path), 10));
                r.Controls.Add(Theme.Button("Not a game", () =>
                {
                    var now = Rules.IgnoreGame(g.Path);
                    MessageBox.Show(now ? "Done." : "Unrot will stop counting it in 24 hours (the PIN skips the wait).", "Unrot");
                    Rebuild();
                }));
            }
        }
        foreach (var g in Store.S.ExtraGames.ToList())
        {
            var r = Row();
            r.Controls.Add(L("➕ " + Path.GetFileName(g), 10));
            r.Controls.Add(Theme.Button("Remove", () =>
            {
                var now = Rules.RemoveGame(g);
                MessageBox.Show(now ? "Removed." : "It'll stop counting in 24 hours (the PIN skips the wait).", "Unrot");
                Rebuild();
            }));
        }
        Row().Controls.Add(Theme.Button("Add a game (.exe)…", () =>
        {
            using var dlg = new OpenFileDialog { Filter = "Programs (*.exe)|*.exe", Title = "Pick the game's .exe" };
            if (dlg.ShowDialog() == DialogResult.OK) { Rules.AddGame(dlg.FileName); Rebuild(); }
        }));

        // ---- Sites
        Section("Blocked sites");
        Add(L($"Counted while the tab is showing on any screen in Vivaldi, Chrome, Edge, Brave, Firefox or Opera. Movie sites (½) cost {st.EngagingCostPct}%. When you're out of time, the tab closes.", 10, Theme.Muted));
        foreach (var site in st.Sites.ToList())
        {
            var r = Row();
            r.Controls.Add(L($"{site.Domain}{(site.Half ? $"  ({st.EngagingCostPct}%)" : "")}", 10));
            r.Controls.Add(Theme.Button(site.Half ? "Make full price" : $"Make {st.EngagingCostPct}%", () =>
            {
                var now = Rules.ToggleSiteHalf(site);
                if (!now) MessageBox.Show("That makes it cheaper, so it kicks in 24 hours from now.", "Unrot");
                Rebuild();
            }));
            r.Controls.Add(Theme.Button("Remove", () =>
            {
                var now = Rules.RemoveSite(site.Domain);
                MessageBox.Show(now ? "Removed." : "It'll be unblocked in 24 hours (the PIN skips the wait).", "Unrot");
                Rebuild();
            }));
        }
        Row().Controls.Add(Theme.Button("Block a site…", () =>
        {
            var d = Prompt.Ask("Block a site", "Website (e.g. twitch.tv):");
            if (string.IsNullOrWhiteSpace(d)) return;
            d = d.Trim().ToLowerInvariant().Replace("https://", "").Replace("http://", "").Replace("www.", "").Split('/')[0];
            var guess = d.Split('.')[0];
            guess = guess.Length > 0 ? char.ToUpper(guess[0]) + guess[1..] : d;
            var k = Prompt.Ask("Block a site", "A word that appears in that site's tab title:", initial: guess);
            if (string.IsNullOrWhiteSpace(k)) return;
            var movie = MessageBox.Show($"Is {d} a movie / streaming site?\n\nYes = costs {st.EngagingCostPct}%, No = full price.",
                "Block a site", MessageBoxButtons.YesNo, MessageBoxIcon.Question) == DialogResult.Yes;
            Rules.AddSite(d, k.Trim(), movie);
            Rebuild();
        }));

        // ---- Rules
        Section("Rules");
        Add(L(Pin.Unlocked ? "🔓 PIN unlocked: changes apply right away." :
            "Stricter changes apply now. Looser ones wait 24 hours.", 10, Pin.Unlocked ? Theme.Accent : Theme.Muted));
        var ratio = Num(st.FocusRatio, 0.1m, 100, 1);
        var bank = Num(st.MaxBankMin, 5, 1440, 0);
        var grace = Num(st.GraceSec, 5, 600, 0);
        var engaging = Num(st.EngagingCostPct, 10, 100, 0);
        var earlyEnd = Num(st.EarlyEndPct, 0, 100, 0);
        var prot = new CheckBox { Text = "Protection on", Checked = st.Protection, ForeColor = Theme.Text, AutoSize = true };
        Labeled("Games & movie sites cost (% of normal)", engaging);
        Labeled("Pay for ending a lock-in early (% of normal)", earlyEnd);
        Labeled("Minutes locked in per 1 min of game time", ratio);
        Labeled("Most game time you can save up (min)", bank);
        Labeled("Seconds to save before a game closes", grace);
        Add(prot);
        Row().Controls.Add(Theme.Button("Save rules", () =>
        {
            var delayed = Rules.ProposeSettings((double)ratio.Value, (int)bank.Value, (int)grace.Value, prot.Checked, (int)engaging.Value, (int)earlyEnd.Value);
            MessageBox.Show(delayed > 0 ? "Saved. Looser changes kick in 24 hours from now." : "Saved.", "Unrot");
            Rebuild();
        }, true));
        var freeRatio = Num(st.FreeRatio, 0.1m, 100, 1);
        var payback = Num(st.PaybackMult, 1m, 5, 1);
        var cap = Num(st.DailyCapMin, 15, 1440, 0);
        var idleMin = Num(st.IdleMin, 0, 120, 0);
        Labeled("Free lock-in: minutes per 1 min earned", freeRatio);
        Labeled($"Overtime payback multiplier (overtime is up to {Ledger.OvertimeMin} min)", payback);
        Labeled("Most doomscrolling per day (min, phone + laptop)", cap);
        Labeled("Pause a lock-in after this many idle minutes (0 = never)", idleMin);
        Row().Controls.Add(Theme.Button("Save these", () =>
        {
            var delayed = Rules.ProposeExtra((double)freeRatio.Value, (double)payback.Value, (int)cap.Value, (int)idleMin.Value);
            MessageBox.Show(delayed > 0 ? "Saved. Looser changes kick in 24 hours from now." : "Saved.", "Unrot");
            Rebuild();
        }, true));
        foreach (var p in Store.S.Pending.OrderBy(p => p.ApplyAt).ToList())
        {
            var mins = Math.Max(0, (p.ApplyAt - Store.Now) / 60000);
            var r = Row();
            r.Controls.Add(L($"⏳ {p.Label} · in {mins / 60}h {mins % 60}m", 10, Theme.Warn));
            r.Controls.Add(Theme.Button("Cancel", () => { Rules.Cancel(p.Key); Rebuild(); }));
        }

        // ---- PIN
        Section("Family PIN");
        if (!Pin.IsSet)
        {
            Add(L("Have a family member pick a PIN you don't know. It's needed to stop Unrot or to skip the 24-hour wait.", 10, Theme.Muted));
            Row().Controls.Add(Theme.Button("Set family PIN", SetPin));
        }
        else if (Pin.Unlocked)
        {
            Add(L($"🔓 Unlocked for about {(Store.S.AdminUntil - Store.Now) / 60000 + 1} more min.", 10, Theme.Accent));
            var r = Row();
            r.Controls.Add(Theme.Button("Change PIN", SetPin));
            r.Controls.Add(Theme.Button("Lock now", () => { Pin.Lock(); Rebuild(); }));
        }
        else
        {
            Row().Controls.Add(Theme.Button("Enter PIN", () =>
            {
                var pin = Prompt.Ask("Unrot", "Family PIN:", password: true);
                if (pin == null) return;
                MessageBox.Show(Pin.Check(pin) ? "Unlocked for 10 minutes." : "Wrong PIN.", "Unrot");
                Rebuild();
            }));
        }

        // ---- Sync
        Section("Sync with your phone");
        Add(L("Paste the same Firebase database URL and sync code as on your phone (steps are in the README).", 10, Theme.Muted));
        var url = new TextBox { Text = Store.S.DbUrl, Width = W - 10, BackColor = Theme.Card2, ForeColor = Theme.Text, BorderStyle = BorderStyle.FixedSingle, PlaceholderText = "https://….firebasedatabase.app" };
        var code = new TextBox { Text = Store.S.SyncCode, Width = W - 10, BackColor = Theme.Card2, ForeColor = Theme.Text, BorderStyle = BorderStyle.FixedSingle, PlaceholderText = "Sync code" };
        Labeled("Database URL", url);
        Labeled("Sync code", code);
        var sr = Row();
        sr.Controls.Add(Theme.Button("Save & sync now", async () =>
        {
            Store.S.DbUrl = url.Text.Trim();
            Store.S.SyncCode = code.Text.Trim();
            Store.Save();
            var ok = await Sync.RunAsync();
            MessageBox.Show(ok ? "Synced ✓" : "Sync failed: " + Store.S.LastSyncError, "Unrot");
            Rebuild();
        }, true));
        sr.Controls.Add(Theme.Button("New code", () =>
        {
            code.Text = Sync.NewCode();
        }));
        var status = !Sync.Configured ? "Not set up" :
            Store.S.LastSyncError.Length > 0 ? "⚠️ Last sync failed: " + Store.S.LastSyncError :
            Store.S.LastSyncTs == 0 ? "Not synced yet" :
            "Last synced " + DateTimeOffset.FromUnixTimeMilliseconds(Store.S.LastSyncTs).ToLocalTime().ToString("h:mm:ss tt", CultureInfo.CurrentCulture);
        Add(L(status, 9, Theme.Muted));
        foreach (var d in Ledger.Remotes())
            Add(L($"• {d.Name}: earned {Ledger.FmtShort(d.Earned)}, spent {Ledger.FmtShort(d.Spent)}", 9, Theme.Muted));

        // ---- Log
        if (Store.S.Log.Count > 0)
        {
            Section("Recent");
            foreach (var l in Store.S.Log.Take(10))
            {
                var when = DateTimeOffset.FromUnixTimeMilliseconds(l.Ts).ToLocalTime().ToString("ddd h:mm tt", CultureInfo.CurrentCulture);
                Add(L($"{l.Text}   ·   {when}", 9, Theme.Muted));
            }
        }

        Add(L($"Data: {Store.Dir}", 8, Theme.Muted));
        _panel.ResumeLayout();
    }

    NumericUpDown Num(double value, decimal min, decimal max, int decimals) => new()
    {
        Minimum = min,
        Maximum = max,
        DecimalPlaces = decimals,
        Increment = decimals > 0 ? 0.5m : 1,
        Value = Math.Clamp((decimal)value, min, max),
        Width = 120,
        BackColor = Theme.Card2,
        ForeColor = Theme.Text,
    };

    void Labeled(string label, Control c)
    {
        var l = L(label, 9, Theme.Muted);
        l.Margin = new Padding(0, 4, 0, 2);
        Add(l);
        c.Margin = new Padding(0, 0, 0, 8);
        Add(c);
    }

    void SetPin()
    {
        var a = Prompt.Ask("Family member: choose a PIN", "New PIN (4+ digits):", password: true);
        if (a == null) return;
        var b = Prompt.Ask("Family member: choose a PIN", "Repeat PIN:", password: true);
        if (b == null) return;
        if (a.Length < 4 || a != b) { MessageBox.Show("PINs didn't match or were too short.", "Unrot"); return; }
        Pin.Set(a);
        Store.AddLog("Family PIN set");
        MessageBox.Show("PIN saved. Keep it secret 😉", "Unrot");
        Rebuild();
    }

    protected override void OnShown(EventArgs e)
    {
        base.OnShown(e);
        Rebuild();
    }
}
