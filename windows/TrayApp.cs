using System.Globalization;
using System.Drawing.Drawing2D;

namespace Unrot;

/// <summary>Lives in the system tray. Every second: charge game time, enforce the balance, sync.</summary>
public class TrayApp : ApplicationContext
{
    readonly NotifyIcon _icon;
    readonly System.Windows.Forms.Timer _timer = new() { Interval = 1000 };
    readonly EventWaitHandle _showEvent;
    readonly EventWaitHandle _updateEvent;
    MainForm _main;
    BlockForm _block;
    long _ticks;
    long _lastClosedAt;
    bool _syncing;
    double _spendAcc;
    long _lastPositivePlaying, _lastGameSeen;
    PillForm _pill;
    readonly HashSet<int> _warned = new();
    string _lastGame;
    int _lastGamePid;

    public TrayApp(bool showWindow = true)
    {
        Store.Load();
        Ledger.RefundIdlePausesOnce();
        try { File.Delete(Program.InstalledExe + ".old"); } catch { }
        _showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, Program.ShowEventName);
        _updateEvent = new EventWaitHandle(false, EventResetMode.AutoReset, Program.UpdateEventName);
        Autostart.Ensure();

        var menu = new ContextMenuStrip();
        menu.Items.Add("Open Unrot", null, (_, _) => ShowMain());
        menu.Items.Add("Lock in 25 min", null, (_, _) => StartFocus(25));
        menu.Items.Add("Lock in 60 min", null, (_, _) => StartFocus(60));
        menu.Items.Add("Free lock-in (stop anytime)", null, (_, _) => StartFree());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Stop Unrot…", null, (_, _) => StopUnrot());

        _icon = new NotifyIcon
        {
            Icon = Theme.MakeIcon(),
            Text = "Unrot",
            Visible = true,
            ContextMenuStrip = menu,
        };
        _icon.MouseClick += (_, e) => { if (e.Button == MouseButtons.Left) ShowMain(); };

        _timer.Tick += (_, _) => Step();
        _timer.Start();

        if (!Store.S.Welcomed)
        {
            Store.S.Welcomed = true;
            Store.AddLog("Unrot installed on this laptop");
            showWindow = true;
        }
        if (showWindow) ShowMain();
    }

    public void ShowMain()
    {
        if (_main == null || _main.IsDisposed) _main = new MainForm(this);
        _main.Show();
        if (_main.WindowState == FormWindowState.Minimized) _main.WindowState = FormWindowState.Normal;
        // Bring it in front of whatever's open (Windows otherwise may leave it behind other windows).
        _main.TopMost = true;
        _main.Activate();
        _main.TopMost = false;
    }

    public void StartFocus(int minutes)
    {
        if (Ledger.FocusActive) { ShowMain(); return; }
        Ledger.StartFocus(minutes);
        Store.Save();
        _ = SyncNow();
        Balloon("Locked in 🔒", $"{minutes} min. Games and your phone's blocked apps are off until it ends.");
        _main?.Rebuild();
    }

    public void StartFree()
    {
        if (Ledger.FocusActive) { ShowMain(); return; }
        Ledger.StartFree();
        Store.Save();
        _ = SyncNow();
        Balloon("Free lock-in started 🔒", "Stop it from Unrot whenever you're done. You're paid for every minute.");
        _main?.Rebuild();
    }

    public async Task SyncNow()
    {
        if (_syncing || !Sync.Configured) return;
        _syncing = true;
        try { await Sync.RunAsync(); } finally { _syncing = false; }
    }

    void Balloon(string title, string text)
    {
        _icon.BalloonTipTitle = title;
        _icon.BalloonTipText = text;
        _icon.ShowBalloonTip(5000);
    }

    void Step()
    {
        try { StepInner(); } catch { /* one bad tick shouldn't stop Unrot */ }
    }

    void StepInner()
    {
        _ticks++;
        if (_showEvent.WaitOne(0)) ShowMain();
        if (_updateEvent.WaitOne(0))
        {
            // A newer Unrot.exe was just installed and wants to take over.
            Store.Save();
            _icon.Visible = false;
            ExitThread();
            return;
        }

        Rules.Current();
        var earned = Ledger.SettleFocus();
        if (earned == Ledger.NeedsReview)
        {
            Balloon($"Free lock-in hit {Ledger.FreeMaxMs / 3_600_000} hours",
                "Forgot it was on? Open Unrot and say how long you really worked to get paid.");
            Store.Save();
            _ = SyncNow();
            _main?.Rebuild();
        }
        else if (earned >= 0)
        {
            Balloon("Lock-in complete 🎉", $"+{Ledger.FmtShort(earned)} of time.");
            _ = SyncNow();
            _main?.Rebuild();
        }

        var st = Rules.Current();
        Ledger.Rollover();

        Games.RefreshIndexIfStale();
        var fg = Games.ForegroundGame();
        var fgName = fg == null ? null : Path.GetFileName(fg.Value.Path);
        if (fgName != _lastGame)
        {
            if (fgName != null) Store.AddLog("Playing " + fgName);
            _lastGame = fgName;
        }
        // Everything visible counts, but each kind only once: doomscrolling (any number of social windows) 1x,
        // games/movies the engaging rate. Shared with the phone: together they never pass 1 + that rate (1.5x).
        var sites = Games.VisibleSites();
        (IntPtr Hwnd, BlockedSite Site)? social = sites.Where(x => !x.Site.Half).Select(x => ((IntPtr, BlockedSite)?)x).FirstOrDefault();
        (IntPtr Hwnd, BlockedSite Site)? movie = sites.Where(x => x.Site.Half).Select(x => ((IntPtr, BlockedSite)?)x).FirstOrDefault();
        var site = social ?? movie;
        var blocking = _block != null && !_block.IsDisposed;
        var showing = fg != null || site != null;
        if (showing && !blocking)
        {
            var pct = st.EngagingCostPct / 100.0;
            double engaging = fg != null || movie != null ? pct : 0;
            if (fg != null && Ledger.OvertimeActive) engaging *= st.PaybackMult;
            double rate = engaging + (social != null ? 1.0 : 0);
            var phone = Ledger.PhoneRate;
            rate = Math.Min(rate, Math.Max(0, 1 + pct - phone));
            _spendAcc += rate;
            var whole = (long)_spendAcc;
            if (whole > 0) { Ledger.Spend(whole); _spendAcc -= whole; }
            // The phone already counts doomscrolling toward the daily limit if it's doing it too.
            if (social != null && phone < 1) Ledger.AddDoom(1);
            Ledger.Stat(d =>
            {
                if (fg != null) d.Game++;
                if (social != null) d.Doom++;
                else if (movie != null) d.Movie++;
            });
            if (fg != null && Ledger.Balance() > 0) _lastPositivePlaying = Store.Now;
        }
        if (fg != null) _lastGameSeen = Store.Now;

        // Overtime ends after 20 min, or once the game's been gone for 2 min.
        if (Store.S.OvertimeUntil != 0 && (Store.Now >= Store.S.OvertimeUntil || Store.Now - _lastGameSeen > 120_000))
        {
            Ledger.EndOvertime();
            _main?.Rebuild();
        }

        if (fg != null) _lastGamePid = fg.Value.Pid;
        Enforce(fg != null, site);
        UpdatePill(st, fg != null, site);

        if (_ticks % 5 == 0)
        {
            Store.Save();
            var bal = Math.Max(0, Ledger.Balance());
            var t = Ledger.FocusActive ? $"Unrot · locked in, {Ledger.Fmt(Ledger.FocusRemainingSec)} left" : $"Unrot · {Ledger.Fmt(bal)} left";
            _icon.Text = t.Length > 63 ? t[..63] : t;
        }
        if (_ticks % 30 == 0) _ = SyncNow();
    }

    /// <summary>Time you can actually use on what's showing: balance, and the daily limit for social sites.</summary>
    static long Available(Settings st, bool game, (IntPtr Hwnd, BlockedSite Site)? site)
    {
        var bal = Ledger.Balance();
        if (game || site == null || site.Value.Site.Half) return bal;
        return Math.Min(bal, st.DailyCapMin * 60L - Ledger.DoomToday());
    }

    /// <summary>The corner countdown, plus big warnings at 15, 5 and 2 minutes (and 30 seconds).</summary>
    void UpdatePill(Settings st, bool game, (IntPtr Hwnd, BlockedSite Site)? site)
    {
        if (_block != null && !_block.IsDisposed)
        {
            _pill?.HideNow();
            return;
        }
        // During a lock-in (here or on the phone): the lock-in timer, on whatever screen you're using.
        if (Ledger.FocusActive)
        {
            _pill ??= new PillForm();
            var screenOf = Games.ForegroundWindow();
            if (Store.S.FocusFree && Ledger.LocalFocus)
                _pill.ShowOn(screenOf, $"{Ledger.Fmt(Ledger.FreeElapsedSec)}  ·  +{Ledger.FmtShort(Ledger.FreePayout)}", Theme.Accent, Theme.Card, "lock", bottom: true);
            else
                _pill.ShowOn(screenOf, $"{Ledger.Fmt(Ledger.FocusRemainingSec)} left", Theme.Text, Theme.Tint(Theme.Violet, Theme.Card, 0.45f), "lock", bottom: true);
            return;
        }
        if (!game && site == null)
        {
            _pill?.HideNow();
            return;
        }
        _pill ??= new PillForm();
        var hwnd = game ? Games.LastGameHwnd : site.Value.Hwnd;
        if (Ledger.OvertimeActive && game)
        {
            var left = Math.Max(0, (Store.S.OvertimeUntil - Store.Now) / 1000);
            foreach (var t in new[] { 300, 60 })
            {
                if (left <= t && _warned.Add(-t)) _pill.Flash($"Overtime: {t / 60} min left. Wrap up the match", 8);
            }
            _pill.ShowOn(hwnd, $"Overtime {Ledger.Fmt(left)}", Color.FromArgb(42, 26, 5), Theme.Warn, "timer");
            return;
        }
        var avail = Available(st, game, site);
        foreach (var t in new[] { 900, 300, 120, 30 })
        {
            if (avail > t + 60) _warned.Remove(t);
            if (avail > 0 && avail <= t && _warned.Add(t))
            {
                var what = t >= 60 ? $"{t / 60} min left" : "30 seconds left";
                var tip = game ? (t >= 300 ? "Don't start a new match" : "Finish up or find a save point") : "Wrap it up";
                _pill.Flash($"{what} · {tip}", t >= 300 ? 6 : 10);
                Balloon(what, tip);
            }
        }
        var (fgc, bgc) = avail <= 30 ? (Color.FromArgb(42, 6, 16), Theme.Danger)
            : avail <= 120 ? (Color.FromArgb(42, 26, 5), Theme.Warn)
            : (Theme.Gold, Theme.Card);
        var half = game || site.Value.Site.Half;
        _pill.ShowOn(hwnd, Ledger.Fmt(Math.Max(0, avail)) + (half ? $"  ·  {st.EngagingCostPct}%" : ""), fgc, bgc, game ? "sports_esports" : "hourglass_top");
    }

    void Enforce(bool gameInFront, (IntPtr Hwnd, BlockedSite Site)? site)
    {
        var st = Rules.Current();
        var focus = Ledger.FocusActive;
        var bal = Ledger.Balance();
        var capHit = site != null && !site.Value.Site.Half && Ledger.DoomToday() >= st.DailyCapMin * 60L;
        // Overtime only covers the game, not a site next to it.
        var blockGame = gameInFront && !(!focus && (bal > 0 || Ledger.OvertimeActive));
        var blockSite = site != null && !(!focus && bal > 0 && !capHit);

        if (!blockGame && !blockSite)
        {
            if (_block != null && !_block.IsDisposed && !_block.Finished) _block.Close();
            return;
        }
        if (_block != null && !_block.IsDisposed) return;

        if (!blockGame)
        {
            var s = site.Value;
            var cap = st.DailyCapMin >= 60 ? $"{st.DailyCapMin / 60}h{(st.DailyCapMin % 60 > 0 ? $" {st.DailyCapMin % 60}m" : "")}" : $"{st.DailyCapMin} min";
            _block = new BlockForm(
                focus ? "You're locked in 🔒" : capHit ? "📵 Daily doomscroll limit" : "Out of time ⏳",
                capHit && !focus
                    ? $"That's {cap} of doomscrolling today (phone + laptop). {s.Site.Domain} is done until midnight; games and movie sites still work. This tab closes in a moment."
                    : $"{s.Site.Domain} is blocked right now. This tab closes in a moment.",
                Store.Now - _lastClosedAt < 10 * 60_000 ? 3 : 10,
                onExpire: () =>
                {
                    Games.CloseTab(s.Hwnd);
                    _lastClosedAt = Store.Now;
                    Store.AddLog($"Closed a {s.Site.Domain} tab" + (focus ? " during a lock-in" : capHit ? ", daily limit" : ", out of time"));
                },
                onLockIn: () => ShowMain());
            _block.Show();
            _block.Activate();
            return;
        }
        // If you relaunch right after a game was closed, there's barely any grace period.
        var grace = Store.Now - _lastClosedAt < 10 * 60_000 ? 5 : Math.Max(5, st.GraceSec);
        var title = focus ? "You're locked in 🔒" : "Out of time ⏳";
        // Ran out mid-game (not starting a game with no time, and not already in debt): offer overtime.
        var canOvertime = !focus && Store.S.OvertimeUntil == 0 && Store.Now - _lastPositivePlaying < 90_000;
        var body = focus
            ? $"{Ledger.Fmt(Ledger.FocusRemainingSec)} left in your lock-in. Games close in a moment."
            : canOvertime
                ? $"Mid-match? Take up to {Ledger.OvertimeMin} min of overtime to finish it. It's borrowed: your balance goes negative and you pay it back{(st.PaybackMult != 1 ? $" at {st.PaybackMult.ToString("0.##", CultureInfo.InvariantCulture)}×" : "")} from what you earn next."
                : $"Every {Ledger.FmtRatio(st.FocusRatio)} min locked in earns 1 min of game time. Save now, your game closes in a moment.";
        _block = new BlockForm(title, body, canOvertime ? Math.Max(grace, 20) : grace,
            onExpire: () =>
            {
                var n = Games.CloseAll(_lastGamePid);
                _lastClosedAt = Store.Now;
                Store.AddLog(focus ? $"Closed {n} game(s) during a lock-in" : $"Closed {n} game(s), out of time");
            },
            onLockIn: () => ShowMain(),
            extra: canOvertime ? ($"Finish my match (+{Ledger.OvertimeMin} min)", () =>
            {
                Ledger.StartOvertime();
                _lastGameSeen = Store.Now;
                Store.Save();
                _main?.Rebuild();
            }) : null);
        _block.Show();
        _block.Activate();
    }

    void StopUnrot()
    {
        if (Pin.IsSet && !Pin.Unlocked)
        {
            var pin = Prompt.Ask("Stop Unrot", "Family PIN:", password: true);
            if (pin == null) return;
            if (!Pin.Check(pin)) { MessageBox.Show("Wrong PIN.", "Unrot"); return; }
        }
        else if (!Pin.IsSet)
        {
            if (MessageBox.Show(
                    "No family PIN is set, so anyone can stop Unrot right now.\n\nStop it and remove it from startup?",
                    "Stop Unrot", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
        }
        Store.AddLog("Unrot was stopped on this laptop");
        Autostart.Remove();
        _icon.Visible = false;
        ExitThread();
    }

    protected override void ExitThreadCore()
    {
        Store.Save();
        _icon.Visible = false;
        base.ExitThreadCore();
    }
}

/// <summary>The "save your game" countdown, then the games close.</summary>
public class BlockForm : Form
{
    readonly System.Windows.Forms.Timer _t = new() { Interval = 1000 };
    readonly Label _count;
    int _left;
    public bool Finished { get; private set; }

    public BlockForm(string title, string body, int seconds, Action onExpire, Action onLockIn,
        (string Label, Action Act)? extra = null)
    {
        _left = seconds;
        FormBorderStyle = FormBorderStyle.None;
        TopMost = true;
        ShowInTaskbar = false;
        StartPosition = FormStartPosition.CenterScreen;
        Size = new Size(560, 330);
        BackColor = Theme.Card;
        ForeColor = Theme.Text;
        Padding = new Padding(28);

        var panel = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false };
        panel.Controls.Add(Theme.Label(title, 20, Theme.Text, true, 500));
        panel.Controls.Add(Theme.Label(body, 11, Theme.Muted, false, 500));
        _count = Theme.Label("", 26, Theme.Warn, true, 500);
        panel.Controls.Add(_count);
        var row = new FlowLayoutPanel { AutoSize = true, FlowDirection = FlowDirection.LeftToRight };
        row.Controls.Add(Theme.Button("Lock in now", () => { onLockIn(); }, true, 160));
        row.Controls.Add(Theme.Button("Close game now", () => { _left = 0; Tick(onExpire); }, false, 160));
        panel.Controls.Add(row);
        if (extra != null)
        {
            var ex = extra.Value;
            Size = new Size(560, 400);
            panel.Controls.Add(Theme.Button(ex.Label, () =>
            {
                Finished = true;
                ex.Act();
                Close();
            }, true, 330));
        }
        Controls.Add(panel);

        _t.Tick += (_, _) => Tick(onExpire);
        _count.Text = $"Closing in {_left}s";
        _t.Start();
        FormClosing += (_, e) =>
        {
            if (!Finished && e.CloseReason == CloseReason.UserClosing && _left > 0 && !_allowClose) e.Cancel = true;
        };
    }

    bool _allowClose;

    public new void Close()
    {
        _allowClose = true;
        _t.Stop();
        base.Close();
    }

    void Tick(Action onExpire)
    {
        if (Finished) return;
        if (_left > 0)
        {
            _count.Text = $"Closing in {_left}s";
            _left--;
            return;
        }
        Finished = true;
        _t.Stop();
        onExpire();
        _count.Text = "Games closed. Go earn some time 💪";
        var done = new System.Windows.Forms.Timer { Interval = 4000 };
        done.Tick += (_, _) => { done.Stop(); Close(); };
        done.Start();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        using var pen = new Pen(Theme.Violet, 2);
        e.Graphics.DrawRectangle(pen, 1, 1, Width - 3, Height - 3);
    }
}

/// <summary>Tiny input box.</summary>
public static class Prompt
{
    /// <summary>
    /// A minutes slider with a live line above it. Returns the chosen minutes, or null if cancelled.
    /// Arrow keys move it one minute at a time.
    /// </summary>
    public static int? Slider(string title, string message, int maxMin, int startMin, string confirm, Func<int, string> describe)
    {
        using var f = new Form
        {
            Text = title,
            ClientSize = new Size(460, 220),
            StartPosition = FormStartPosition.CenterScreen,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            MaximizeBox = false,
            MinimizeBox = false,
            BackColor = Theme.Card,
            ForeColor = Theme.Text,
            TopMost = true,
        };
        var msg = new Label { Text = message, Left = 18, Top = 14, Width = 424, Height = 56, ForeColor = Theme.Muted, Font = Theme.Body(10) };
        var value = new Label
        {
            Left = 18, Top = 74, Width = 424, Height = 36, ForeColor = Theme.Gold, Font = Theme.Display(17),
            TextAlign = ContentAlignment.MiddleCenter, Text = describe(startMin),
        };
        var bar = new TrackBar
        {
            Left = 14, Top = 114, Width = 432, Minimum = 0, Maximum = Math.Max(1, maxMin),
            TickStyle = TickStyle.None, SmallChange = 1, LargeChange = Math.Max(1, maxMin / 12), BackColor = Theme.Card,
        };
        bar.Value = Math.Clamp(startMin, 0, bar.Maximum);
        bar.ValueChanged += (_, _) => value.Text = describe(bar.Value);
        var ok = new Button { Text = confirm, Left = 236, Top = 170, Width = 120, Height = 32, DialogResult = DialogResult.OK, FlatStyle = FlatStyle.Flat, BackColor = Theme.Violet, ForeColor = Color.White };
        var cancel = new Button { Text = "Cancel", Left = 362, Top = 170, Width = 80, Height = 32, DialogResult = DialogResult.Cancel, FlatStyle = FlatStyle.Flat };
        f.Controls.AddRange(new Control[] { msg, value, bar, ok, cancel });
        f.AcceptButton = ok;
        f.CancelButton = cancel;
        f.Shown += (_, _) => bar.Focus();
        return f.ShowDialog() == DialogResult.OK ? bar.Value : null;
    }

    public static string MinText(long min) =>
        min >= 60 && min % 60 == 0 ? $"{min / 60} h" : min >= 60 ? $"{min / 60} h {min % 60} min" : $"{min} min";

    /// <summary>"How long did you really work?" for a free lock-in that ran <paramref name="ranSec"/>. Returns seconds to keep, or null.</summary>
    public static long? Worked(string title, string message, long ranSec, string confirm)
    {
        var ranMin = (int)(ranSec / 60);
        var ratio = Rules.Current().FreeRatio;
        var m = Slider(title, message, ranMin, ranMin, confirm,
            v => $"Worked {MinText(v)} → +{Ledger.FmtShort((long)(v * 60L / ratio))}");
        if (m == null) return null;
        return m >= ranMin ? ranSec : m.Value * 60L;
    }

    public static string Ask(string title, string label, bool password = false, string initial = "")
    {
        using var f = new Form
        {
            Text = title,
            Size = new Size(420, 190),
            StartPosition = FormStartPosition.CenterScreen,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            MaximizeBox = false,
            MinimizeBox = false,
            BackColor = Theme.Card,
            ForeColor = Theme.Text,
            TopMost = true,
        };
        var lbl = new Label { Text = label, Left = 16, Top = 16, Width = 370, ForeColor = Theme.Text };
        var box = new TextBox
        {
            Left = 16, Top = 44, Width = 370, Text = initial,
            UseSystemPasswordChar = password, BackColor = Theme.Card2, ForeColor = Theme.Text,
            BorderStyle = BorderStyle.FixedSingle,
        };
        var ok = new Button { Text = "OK", Left = 230, Top = 90, Width = 75, DialogResult = DialogResult.OK, FlatStyle = FlatStyle.Flat };
        var cancel = new Button { Text = "Cancel", Left = 311, Top = 90, Width = 75, DialogResult = DialogResult.Cancel, FlatStyle = FlatStyle.Flat };
        f.Controls.AddRange(new Control[] { lbl, box, ok, cancel });
        f.AcceptButton = ok;
        f.CancelButton = cancel;
        return f.ShowDialog() == DialogResult.OK ? box.Text : null;
    }
}
