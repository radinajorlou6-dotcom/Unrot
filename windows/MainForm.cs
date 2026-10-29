using System.Drawing.Drawing2D;
using System.Globalization;

namespace Unrot;

/// <summary>The window you get from the tray icon: a sidebar with Home, Earn, Stats and Settings.</summary>
public class MainForm : Form
{
    enum Tab { Home, Earn, Stats, Settings }

    readonly TrayApp _app;
    readonly FlowLayoutPanel _panel;
    readonly System.Windows.Forms.Timer _live = new() { Interval = 1000 };
    readonly List<(NavItem Item, Tab Tab)> _nav = new();
    readonly Label _sideBalance;
    Tab _tab = Tab.Home;
    Ring _balanceRing, _focusRing;
    Control _focusPay;
    bool _focusWasActive;
    bool _focusWasPaused;
    const int CW = 660; // card width

    public MainForm(TrayApp app)
    {
        _app = app;
        Text = "Unrot";
        Icon = Theme.MakeIcon();
        Size = new Size(1000, 760);
        MinimumSize = new Size(980, 560);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Theme.Bg;
        ForeColor = Theme.Text;
        Font = Theme.Body(10);

        // ---- content (added first so it fills what the sidebar leaves)
        _panel = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            AutoScroll = true,
            Padding = new Padding(36, 26, 24, 26),
            BackColor = Theme.Bg,
        };
        Controls.Add(_panel);

        // ---- sidebar
        var side = new Panel { Dock = DockStyle.Left, Width = 200, BackColor = Theme.Side };
        var nav = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            BackColor = Theme.Side,
            Padding = new Padding(0, 22, 0, 0),
        };
        var logo = new FlowLayoutPanel { AutoSize = true, FlowDirection = FlowDirection.LeftToRight, Margin = new Padding(22, 0, 0, 22), BackColor = Theme.Side };
        logo.Controls.Add(new PictureBox { Image = Theme.Icon("hourglass_top", Theme.Gold, 26), Size = new Size(26, 26), Margin = new Padding(0, 4, 8, 0) });
        logo.Controls.Add(new Label { Text = "Unrot", Font = Theme.Display(20), ForeColor = Theme.Text, AutoSize = true, BackColor = Theme.Side });
        nav.Controls.Add(logo);
        foreach (var (t, label, icon) in new[] { (Tab.Home, "Home", "home"), (Tab.Earn, "Earn", "bolt"), (Tab.Stats, "Stats", "bar_chart"), (Tab.Settings, "Settings", "settings") })
        {
            var item = new NavItem(label, icon, () => SwitchTab(t));
            _nav.Add((item, t));
            nav.Controls.Add(item);
        }
        side.Controls.Add(nav);
        _sideBalance = new Label
        {
            Dock = DockStyle.Bottom, Height = 70, TextAlign = ContentAlignment.MiddleCenter,
            Font = Theme.Display(16), ForeColor = Theme.Gold, BackColor = Theme.Side,
        };
        side.Controls.Add(_sideBalance);
        Controls.Add(side);

        _live.Tick += (_, _) => Live();
        _live.Start();
        // Closing the window just hides it; Unrot keeps running in the tray.
        FormClosing += (_, e) =>
        {
            if (e.CloseReason == CloseReason.UserClosing) { e.Cancel = true; Hide(); }
        };
        Rebuild();
    }

    void SwitchTab(Tab t)
    {
        _tab = t;
        Rebuild();
        _panel.AutoScrollPosition = new Point(0, 0);
    }

    static string BalanceText()
    {
        var b = Ledger.Balance();
        return b < 0 ? "-" + Ledger.Fmt(-b) : Ledger.Fmt(b);
    }

    static Color BalanceColor()
    {
        var b = Ledger.Balance();
        return b <= 0 ? Theme.Danger : b <= 120 ? Theme.Warn : Theme.Gold;
    }

    static float BalanceFraction() => Math.Clamp(Ledger.Balance() / (Rules.Current().MaxBankMin * 60f), 0f, 1f);

    void Live()
    {
        if (!Visible) return;
        _sideBalance.Text = "⏳ " + BalanceText();
        _sideBalance.ForeColor = BalanceColor();
        if (_balanceRing != null)
        {
            _balanceRing.Big = BalanceText();
            _balanceRing.Color = BalanceColor();
            _balanceRing.Fraction = BalanceFraction();
            _balanceRing.Invalidate();
        }
        var sess = Ledger.Current;
        var active = sess != null;
        if (active != _focusWasActive || (sess?.Paused ?? false) != _focusWasPaused) { Rebuild(); return; }
        if (_focusRing != null && sess != null)
        {
            _focusRing.Big = ClockText(sess);
            _focusRing.Fraction = RingFraction(sess);
            if (sess.Free && _focusPay != null) _focusPay.Text = $"+{Ledger.FmtShort(sess.PayNow())} so far";
            _focusRing.Invalidate();
        }
    }

    // ================================================================== building blocks

    Label L(string text, float size = 10, Color? color = null, bool bold = false, int width = CW - 40) =>
        Theme.Label(text, size, color ?? Theme.Text, bold, width);

    void Add(Control c) => _panel.Controls.Add(c);

    void PageTitle(string title, string sub = null)
    {
        var t = L(title, 24, Theme.Text, true, CW);
        t.Margin = new Padding(0, 0, 0, sub == null ? 16 : 2);
        Add(t);
        if (sub != null)
        {
            var s = L(sub, 10.5f, Theme.Muted, false, CW);
            s.Margin = new Padding(0, 0, 0, 16);
            Add(s);
        }
    }

    Card NewCard(string title = null, string icon = null, Color? color = null, Control trailing = null)
    {
        var c = new Card(CW);
        if (title != null)
        {
            var r = Row(c);
            r.Margin = new Padding(0, 0, 0, 12);
            if (icon != null) r.Controls.Add(new IconBadge(icon, color ?? Theme.Violet, 34) { Margin = new Padding(0, 0, 10, 0) });
            var t = L(title, 15, Theme.Text, true);
            t.Margin = new Padding(0, 4, 12, 0);
            r.Controls.Add(t);
            if (trailing != null) { trailing.Margin = new Padding(0, 6, 0, 0); r.Controls.Add(trailing); }
        }
        Add(c);
        return c;
    }

    static FlowLayoutPanel Row(Control parent, int width = 0)
    {
        var r = new FlowLayoutPanel
        {
            AutoSize = true,
            FlowDirection = FlowDirection.LeftToRight,
            WrapContents = true,
            BackColor = Color.Transparent,
            Margin = new Padding(0, 0, 0, 6),
        };
        if (width > 0) r.MaximumSize = new Size(width, 0);
        parent.Controls.Add(r);
        return r;
    }

    static FlowLayoutPanel Column(Control parent, int width)
    {
        var c = new FlowLayoutPanel
        {
            AutoSize = true,
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            BackColor = Color.Transparent,
            MaximumSize = new Size(width, 0),
            Margin = new Padding(0),
        };
        parent.Controls.Add(c);
        return c;
    }

    static GameButton Btn(string text, Action onClick, GameButton.Kind kind = GameButton.Kind.Secondary, string icon = null, int width = 0) =>
        new(text, kind, onClick, icon, width);

    /// <summary>A label + value line that opens a small editor, used for rules.</summary>
    NumericUpDown Num(double value, decimal min, decimal max, int decimals) => new()
    {
        Minimum = min,
        Maximum = max,
        DecimalPlaces = decimals,
        Increment = decimals > 0 ? 0.5m : 1,
        Value = Math.Clamp((decimal)value, min, max),
        Width = 110,
        BackColor = Theme.Card2,
        ForeColor = Theme.Text,
        BorderStyle = BorderStyle.None,
        Font = Theme.Heavy(11),
        Margin = new Padding(0, 0, 0, 0),
    };

    void RuleLine(Card c, string label, Control input)
    {
        var r = Row(c, c.Inner);
        r.WrapContents = false;
        var l = L(label, 10, Theme.Text, false, c.Inner - 130);
        l.Width = c.Inner - 130;
        l.AutoSize = false;
        l.Height = 28;
        l.TextAlign = ContentAlignment.MiddleLeft;
        l.Margin = new Padding(0, 0, 10, 0);
        r.Controls.Add(l);
        input.Margin = new Padding(0, 3, 0, 0);
        r.Controls.Add(input);
    }

    void Header(Card c, string text)
    {
        var l = L(text.ToUpperInvariant(), 9, Theme.Violet, true);
        l.Margin = new Padding(0, 10, 0, 6);
        c.Controls.Add(l);
    }

    // ================================================================== tabs

    public void Rebuild()
    {
        if (IsDisposed) return;
        _focusWasActive = Ledger.FocusActive;
        _focusWasPaused = Ledger.Current?.Paused ?? false;
        _balanceRing = null; _focusRing = null; _focusPay = null;
        foreach (var (item, t) in _nav) { item.Selected = t == _tab; item.Invalidate(); }
        _panel.SuspendLayout();
        foreach (Control c in _panel.Controls.Cast<Control>().ToList()) c.Dispose();
        _panel.Controls.Clear();
        try
        {
            switch (_tab)
            {
                case Tab.Home: HomeTab(); break;
                case Tab.Earn: EarnTab(); break;
                case Tab.Stats: StatsTab(); break;
                default: SettingsTab(); break;
            }
        }
        catch (Exception e)
        {
            Add(L("Something broke on this screen 😬 Send this to Claude:", 12, Theme.Warn, true, CW));
            Add(L(e.ToString(), 9, Theme.Muted, false, CW));
        }
        _panel.ResumeLayout();
        Live();
    }

    // ---------------------------------------------------------------- HOME

    void HomeTab()
    {
        var st = Rules.Current();
        PageTitle("Home", DateTime.Now.ToString("dddd, MMMM d", CultureInfo.CurrentCulture));

        FreeReviewCard();

        // Hero: balance ring + what's going on.
        var hero = NewCard();
        var hr = Row(hero, hero.Inner);
        hr.WrapContents = false;
        _balanceRing = new Ring(200) { Big = BalanceText(), Small = "game & scroll time", Color = BalanceColor(), Fraction = BalanceFraction(), Thickness = 15 };
        _balanceRing.Margin = new Padding(0, 0, 24, 6);
        hr.Controls.Add(_balanceRing);
        var info = Column(hr, hero.Inner - 230);
        var bal = Ledger.Balance();
        info.Controls.Add(L(bal > 0 ? "Shared with your phone if synced." :
            bal < 0 ? $"You owe {Ledger.FmtShort(-bal)} from overtime. What you earn next pays it back first." :
            "Out of time. Lock in to earn more.", 11, bal <= 0 ? Theme.Warn : Theme.Muted, false, hero.Inner - 230));
        if (bal > 0)
        {
            var gb = Btn("Give time back", GiveBack, GameButton.Kind.Ghost, "favorite");
            gb.Margin = new Padding(0, 6, 0, 4);
            info.Controls.Add(gb);
        }
        if (Ledger.OvertimeActive)
        {
            info.Controls.Add(new Chip($"Overtime: {Ledger.Fmt((Store.S.OvertimeUntil - Store.Now) / 1000)} left", Theme.Warn) { Margin = new Padding(0, 4, 0, 10) });
        }
        var doom = Ledger.DoomToday();
        var cap = st.DailyCapMin * 60L;
        var dl = L("Doomscrolling today", 10.5f, Theme.Text, true);
        dl.Margin = new Padding(0, 14, 0, 2);
        info.Controls.Add(dl);
        info.Controls.Add(new Bar(hero.Inner - 240, 12) { Fraction = doom / (float)cap, Color = doom >= cap ? Theme.Danger : doom > cap / 2 ? Theme.Warn : Theme.Accent });
        info.Controls.Add(L($"{doom / 60} of {st.DailyCapMin} min (social sites here + scroll apps on your phone)", 9, Theme.Muted, false, hero.Inner - 230));
        var fg = Games.ForegroundGame();
        var steam = Games.SteamRunningAppId();
        var playing = L(fg != null ? $"🎮 Playing {Path.GetFileNameWithoutExtension(fg.Value.Path)} at {st.EngagingCostPct}% cost"
            : steam != 0 ? "🎮 Steam says a game is running" : "No game showing right now", 10, fg != null ? Theme.Accent : Theme.Muted, false, hero.Inner - 230);
        playing.Margin = new Padding(0, 12, 0, 0);
        info.Controls.Add(playing);

        LockInCard(st);
    }

    /// <summary>Remove any amount of your balance, to undo time you didn't really earn.</summary>
    void GiveBack()
    {
        var bal = Ledger.Balance();
        if (bal <= 0) return;
        var maxMin = (int)Math.Max(1, bal / 60);
        var m = Prompt.Slider("Give time back",
            "Earned something you didn't really earn, like a lock-in you forgot to stop? Take it off here. Can't be undone; doesn't count toward your daily limit.",
            maxMin, 0, "Remove it", v => v == 0 ? "Slide to choose" : $"−{Prompt.MinText(v)}");
        if (m is not > 0) return;
        var took = Ledger.GiveBack(m >= maxMin ? bal : m.Value * 60L);
        Store.Save();
        _ = _app.SyncNow();
        MessageBox.Show($"Removed {Ledger.FmtShort(took)}. Respect. 🫡", "Unrot");
        Rebuild();
    }

    /// <summary>A free lock-in that hit the max sits here, unpaid, until you say how long was real.</summary>
    void FreeReviewCard()
    {
        var ran = Store.S.FreeReviewSec;
        if (ran <= 0) return;
        var c = NewCard("Your free lock-in ran out", "hourglass_top", Theme.Warn);
        c.Controls.Add(L($"It hit the {Ledger.FreeMaxMs / 3_600_000}-hour max, so it probably kept going after you stopped. Say how long you really worked to get paid.",
            10.5f, Theme.Muted, false, c.Inner));
        var b = Btn("How long did I work?", () =>
        {
            var keep = Prompt.Worked("Free lock-in", $"It ran for {Prompt.MinText(ran / 60)} in total.", ran, "Get paid");
            if (keep == null) return;
            var got = Ledger.ResolveFreeReview(keep.Value);
            Store.Save();
            _ = _app.SyncNow();
            MessageBox.Show(got > 0 ? $"+{Ledger.FmtShort(got)} 💪" : "Nothing added", "Unrot");
            Rebuild();
        }, GameButton.Kind.Primary, "timer");
        b.Margin = new Padding(0, 10, 0, 0);
        c.Controls.Add(b);
    }

    static string ClockText(Ledger.Session s) => Ledger.Fmt(s.Free ? s.ElapsedSec : s.RemainingSec);

    static float RingFraction(Ledger.Session s) =>
        s.Free ? s.ElapsedSec % 3600 / 3600f : s.PlannedSec > 0 ? 1f - s.RemainingSec / (float)s.PlannedSec : 0f;

    /// <summary>Pause/resume. On the phone's lock-in it's sent there and shows up here within a few seconds.</summary>
    async void TogglePause(Ledger.Session sess)
    {
        if (sess.Local)
        {
            if (sess.Paused) Ledger.Resume(); else Ledger.Pause();
            Store.Save();
            _ = _app.SyncNow();
            Rebuild();
            return;
        }
        var action = sess.Paused ? "resume" : "pause";
        try { await Sync.SendCommandAsync(sess, Ledger.Command(sess, action)); }
        catch { MessageBox.Show("Couldn't reach sync. Check your internet and try again.", "Unrot"); return; }
        _app.Toast($"Sent to your {sess.DeviceName}", $"It'll {action} within a few seconds.");
        foreach (var wait in new[] { 6000, 8000, 12000 })
        {
            await Task.Delay(wait);
            await _app.SyncNow();
            if (IsDisposed) return;
            Rebuild();
        }
    }

    /// <summary>Finishes the lock-in (paying keepSec of a free one). A phone lock-in is paid here and stopped there.</summary>
    async void Finish(Ledger.Session sess, long keepSec)
    {
        long got;
        if (sess.Local)
        {
            got = sess.Free ? Ledger.StopFree(keepSec) : Ledger.CancelFocus();
        }
        else
        {
            try { await Sync.SendCommandAsync(sess, Ledger.Command(sess, "end", sess.PayNow(keepSec))); }
            catch { MessageBox.Show("Couldn't reach sync. Check your internet and try again.", "Unrot"); return; }
            got = Ledger.FinishRemote(sess, keepSec);
        }
        Store.Save();
        _ = _app.SyncNow();
        MessageBox.Show(got > 0 ? $"+{Ledger.FmtShort(got)} 💪" + (sess.Local ? "" : $" (your {sess.DeviceName} unlocks within a few seconds)") : "Lock-in ended", "Unrot");
        if (!IsDisposed) Rebuild();
    }

    void LockInCard(Settings st)
    {
        var c = NewCard("Lock in", "lock", Theme.Violet, new Chip($"{Ledger.FmtRatio(st.FocusRatio)} : 1", Theme.Violet));
        var sess = Ledger.Current;
        if (sess != null)
        {
            var r = Row(c, c.Inner);
            r.WrapContents = false;
            _focusRing = new Ring(170)
            {
                Big = ClockText(sess),
                Small = sess.Paused ? "paused" : sess.Free ? "so far" : "left",
                Color = sess.Paused ? Theme.Warn : sess.Free ? Theme.Accent : Theme.Violet,
                Fraction = RingFraction(sess),
                Thickness = 12,
            };
            _focusRing.Margin = new Padding(0, 0, 24, 6);
            r.Controls.Add(_focusRing);
            var col = Column(r, c.Inner - 200);
            if (!sess.Local) col.Controls.Add(new Chip($"Started on your {sess.DeviceName}", Theme.Blue) { Margin = new Padding(0, 6, 0, 0) });
            if (sess.Free)
            {
                _focusPay = new Chip($"+{Ledger.FmtShort(sess.PayNow())} so far", Theme.Accent) { Margin = new Padding(0, 10, 0, 10) };
                col.Controls.Add(_focusPay);
            }
            else
            {
                col.Controls.Add(new Chip($"+{Ledger.FmtShort(sess.FullPay)} when it ends", Theme.Accent) { Margin = new Padding(0, 10, 0, 10) });
            }
            col.Controls.Add(L(
                sess.Paused ? "Paused: the timer's frozen, but games and blocked sites stay off."
                : sess.Free ? $"Free lock-in at {Ledger.FmtRatio(sess.Ratio)}:1. Stop whenever; you're paid for every minute."
                : "Games, blocked sites (and your phone's blocked apps) are off until it ends.",
                10, Theme.Muted, false, c.Inner - 200));
            var btns = Row(col, c.Inner - 200);
            btns.Margin = new Padding(0, 8, 0, 0);
            var pb = Btn(sess.Paused ? "Resume" : "Pause", () => TogglePause(sess), GameButton.Kind.Secondary, sess.Paused ? "play_arrow" : "pause");
            pb.Margin = new Padding(0, 0, 10, 0);
            btns.Controls.Add(pb);
            if (sess.Free)
            {
                btns.Controls.Add(Btn("Stop & collect", () =>
                {
                    var ran = sess.ElapsedSec;
                    var keep = Prompt.Worked("Stop & collect",
                        $"It's been running for {Prompt.MinText(ran / 60)}. If you forgot to stop it, slide it down to the time you really worked.",
                        ran, "Collect", sess.Ratio);
                    if (keep != null) Finish(sess, keep.Value);
                }, GameButton.Kind.Success, "check_circle"));
            }
            else
            {
                btns.Controls.Add(Btn($"End early ({sess.EarlyPct}% pay)", () =>
                {
                    if (MessageBox.Show($"You'll get {Ledger.FmtShort(sess.PayNow())} for the time you've done ({sess.EarlyPct}% rate) instead of {Ledger.FmtShort(sess.FullPay)} for finishing. End it?",
                            "Unrot", MessageBoxButtons.YesNo) == DialogResult.Yes)
                        Finish(sess, long.MaxValue);
                }, GameButton.Kind.Danger, "stop"));
            }
            return;
        }
        c.Controls.Add(L("Games and blocked sites are off until the timer ends. Study, work, anything real.", 10, Theme.Muted, false, c.Inner));
        var tiles = Row(c, c.Inner);
        tiles.Margin = new Padding(0, 6, 0, 4);
        var tw = (c.Inner - 44) / 4;
        foreach (var m in new[] { 25, 45, 60, 90 })
        {
            tiles.Controls.Add(Btn($"{m} min · +{(int)Math.Round(m / st.FocusRatio)}m",
                () => { _app.StartFocus(m); Rebuild(); }, m == 25 ? GameButton.Kind.Primary : GameButton.Kind.Secondary, null, tw));
        }
        var custom = Btn("Custom length", () =>
        {
            var ratio = Rules.Current().FocusRatio;
            var m = Prompt.Slider("Custom lock-in", "Any length from 25 minutes up to 8 hours. Arrow keys move it a minute at a time.",
                480, 120, "Lock in", v => $"{Prompt.MinText(v)} → +{Ledger.FmtShort((long)(v * 60L / ratio))}", minMin: 25);
            if (m == null) return;
            _app.StartFocus(m.Value);
            Rebuild();
        }, GameButton.Kind.Secondary, "tune", c.Inner);
        custom.Margin = new Padding(0, 4, 0, 8);
        c.Controls.Add(custom);
        c.Controls.Add(Btn($"Free lock-in · stop anytime · {Ledger.FmtRatio(st.FreeRatio)}:1", () => { _app.StartFree(); Rebuild(); },
            GameButton.Kind.Success, "play_arrow", c.Inner));
        c.Controls.Add(L($"End a timed one early for {st.EarlyEndPct}% pay. Keep your phone screen off for a bonus.", 9, Theme.Muted, false, c.Inner));
    }

    // ---------------------------------------------------------------- EARN

    void EarnTab()
    {
        var st = Rules.Current();
        PageTitle("Earn time", "Every quest is checked by a timer, your watch or your call log. No honor system.");
        LockInCard(st);

        var c = NewCard("Quests on your phone", "bolt", Theme.Gold);
        c.Controls.Add(L("Tracked by the Unrot phone app; with sync on, the time shows up here too. These are the default rewards; your phone's Earn tab shows your current ones.", 10, Theme.Muted, false, c.Inner));
        void Quest(string icon, Color color, string name, string desc, string reward)
        {
            var r = Row(c, c.Inner);
            r.WrapContents = false;
            r.Margin = new Padding(0, 6, 0, 6);
            r.Controls.Add(new IconBadge(icon, color, 36) { Margin = new Padding(0, 0, 12, 0) });
            var col = Column(r, c.Inner - 170);
            col.Width = c.Inner - 170;
            var n = L(name, 11, Theme.Text, true, c.Inner - 170);
            n.Margin = new Padding(0, 0, 0, 0);
            col.Controls.Add(n);
            col.Controls.Add(L(desc, 9, Theme.Muted, false, c.Inner - 170));
            r.Controls.Add(new Chip(reward, color) { Margin = new Padding(8, 6, 0, 0) });
        }
        Quest("call", Theme.Accent, "Call family", "A 5+ min call, once per person every 3h", "+10 min");
        Quest("fitness_center", Theme.Fire, "Work out", "Gym, soccer, anything your watch logs", "3 min → 1");
        Quest("directions_walk", Theme.Blue, "Steps", "Up to 30 min a day", "+2 / 1,000");
        Quest("bedtime", Color.FromArgb(124, 156, 255), "Sleep on time", "Asleep by bedtime, 6h+", "+15 min");
        Quest("directions_run", Theme.Gold, "Run PR", "Beat your best pace on a 2+ km run", "+15 min");
        Quest("trophy", Theme.Gold, "Gym PR", "A new best in FitNotes, verified by your watch", "+15 min");
        Quest("local_fire_department", Theme.Fire, "Streak", "Stay under your doomscroll target each day", "+10/30/60");
        Quest("mobile_off", Theme.Blue, "Phone down", "Phone screen off for a whole lock-in", "+20%");

        var o = NewCard("Overtime", "timer", Theme.Warn);
        o.Controls.Add(L($"Run out mid-match? The out-of-time box offers up to {Ledger.OvertimeMin} min to finish it. It's borrowed: your balance goes negative and you pay it back" +
            (st.PaybackMult != 1 ? $" at {st.PaybackMult.ToString("0.##", CultureInfo.InvariantCulture)}×" : "") +
            " from what you earn next. Only offered if you ran out while playing, and not while you're still in debt.", 10, Theme.Muted, false, o.Inner));
    }

    // ---------------------------------------------------------------- STATS

    void StatsTab()
    {
        var st = Rules.Current();
        PageTitle("Stats", "This laptop, the last 7 days.");
        var days = Enumerable.Range(0, 7).Select(i => DateTime.Now.Date.AddDays(i - 6)).ToList();
        var stats = days.Select(d => Ledger.DayOf(d.ToString("yyyy-MM-dd"))).ToList();
        var labels = days.Select(d => d.DayOfWeek.ToString()[..1]).ToArray();
        var today = stats[^1];

        // Today tiles
        var tiles = Row(_panel, CW + 20);
        tiles.Margin = new Padding(0, 0, 0, 4);
        void Tile(string icon, Color color, string value, string label)
        {
            var t = new Card((CW - 36) / 4) { Padding = new Padding(16, 14, 12, 8), Margin = new Padding(0, 0, 12, 14) };
            t.Controls.Add(new PictureBox { Image = Theme.Icon(icon, color, 22), Size = new Size(22, 22), Margin = new Padding(0, 0, 0, 6), BackColor = Color.Transparent });
            var v = L(value, 18, Theme.Text, true, t.Inner);
            v.Margin = new Padding(0, 0, 0, 0);
            t.Controls.Add(v);
            t.Controls.Add(L(label, 9, Theme.Muted, false, t.Inner));
            tiles.Controls.Add(t);
        }
        static string Hm(long sec) => sec >= 3600 ? $"{sec / 3600}h {sec % 3600 / 60}m" : $"{sec / 60}m";
        Tile("sports_esports", Theme.Fire, Hm(today.Game), "gaming today");
        Tile("apps", Theme.Danger, Hm(today.Doom), "social sites today");
        Tile("lock", Theme.Violet, Hm(today.Locked), "locked in today");
        Tile("hourglass_top", Theme.Gold, Hm(today.Earned), "earned today");

        Card ChartCard(string title, string icon, Color color, float[] values, float? target, Func<float, Color> colorFor, string note)
        {
            var c = NewCard(title, icon, color);
            c.Controls.Add(new Chart(c.Inner, 150) { Values = values, Labels = labels, Target = target, ColorFor = colorFor });
            c.Controls.Add(L(note, 9, Theme.Muted, false, c.Inner));
            return c;
        }
        ChartCard("Gaming", "sports_esports", Theme.Fire, stats.Select(s => s.Game / 60f).ToArray(), null, _ => Theme.Fire,
            $"Minutes per day. Games cost {st.EngagingCostPct}% of the normal rate.");
        ChartCard("Locked in", "timer", Theme.Violet, stats.Select(s => s.Locked / 60f).ToArray(), null, _ => Theme.Violet,
            "Minutes of lock-ins finished (or ended early) on this laptop.");
        var cap = st.DailyCapMin;
        ChartCard("Social sites", "public", Theme.Danger, stats.Select(s => s.Doom / 60f).ToArray(), cap, v => v >= cap ? Theme.Danger : Theme.Accent,
            $"Minutes per day on this laptop. Dashed line = your {cap} min daily limit (shared with your phone).");

        if (Store.S.Log.Count > 0)
        {
            var c = NewCard("Recent", "schedule", Theme.Blue);
            foreach (var l in Store.S.Log.Take(14))
            {
                var when = DateTimeOffset.FromUnixTimeMilliseconds(l.Ts).ToLocalTime().ToString("ddd h:mm tt", CultureInfo.CurrentCulture);
                var r = Row(c, c.Inner);
                r.WrapContents = false;
                var good = l.Text.StartsWith("+");
                r.Controls.Add(new Dot(good ? Theme.Accent : Theme.Stroke) { Margin = new Padding(0, 6, 10, 0) });
                var col = Column(r, c.Inner - 30);
                var t = L(l.Text, 10, good ? Theme.Text : Theme.Muted, false, c.Inner - 30);
                t.Margin = new Padding(0);
                col.Controls.Add(t);
                col.Controls.Add(L(when, 8.5f, Theme.Muted, false, c.Inner - 30));
            }
        }
    }

    // ---------------------------------------------------------------- SETTINGS

    void SettingsTab()
    {
        var st = Rules.Current();
        PageTitle("Settings");

        // ---- Games
        var g = NewCard("Games", "sports_esports", Theme.Fire);
        g.Controls.Add(L($"Anything from Steam, Epic, Riot, Xbox, EA, Ubisoft, GOG, Rockstar or Roblox counts automatically, at {st.EngagingCostPct}% cost. Time counts while the game is showing on any screen; minimize it to stop the clock.", 10, Theme.Muted, false, g.Inner));
        var steamApp = Games.SteamRunningAppId();
        g.Controls.Add(new Chip(steamApp != 0 ? $"Steam: a game is running (app {steamApp})" : $"{Games.IndexedCount} games found in your libraries", Theme.Fire) { Margin = new Padding(0, 0, 0, 10) });
        var running = Games.Running();
        foreach (var x in running.GroupBy(x => x.Path, StringComparer.OrdinalIgnoreCase).Select(x => x.First()))
        {
            var r = Row(g, g.Inner);
            var nl = L("Running: " + Path.GetFileName(x.Path), 10, Theme.Text, true);
            nl.Margin = new Padding(0, 8, 10, 0);
            r.Controls.Add(nl);
            r.Controls.Add(Btn("Not a game", () =>
            {
                var now = Rules.IgnoreGame(x.Path);
                MessageBox.Show(now ? "Done." : "Unrot will stop counting it in 24 hours (the PIN skips the wait).", "Unrot");
                Rebuild();
            }, GameButton.Kind.Ghost));
        }
        foreach (var x in Store.S.ExtraGames.ToList())
        {
            var r = Row(g, g.Inner);
            var nl = L("Added: " + Path.GetFileName(x), 10);
            nl.Margin = new Padding(0, 8, 10, 0);
            r.Controls.Add(nl);
            r.Controls.Add(Btn("Remove", () =>
            {
                var now = Rules.RemoveGame(x);
                MessageBox.Show(now ? "Removed." : "It'll stop counting in 24 hours (the PIN skips the wait).", "Unrot");
                Rebuild();
            }, GameButton.Kind.Ghost));
        }
        g.Controls.Add(Btn("Add a game (.exe)…", () =>
        {
            using var dlg = new OpenFileDialog { Filter = "Programs (*.exe)|*.exe", Title = "Pick the game's .exe" };
            if (dlg.ShowDialog() == DialogResult.OK) { Rules.AddGame(dlg.FileName); Rebuild(); }
        }, GameButton.Kind.Secondary, "add"));

        // ---- Sites
        var s = NewCard("Blocked sites", "public", Theme.Warn);
        s.Controls.Add(L($"Counted while the tab is showing on any screen in Vivaldi, Chrome, Edge, Brave, Firefox or Opera. Movie sites cost {st.EngagingCostPct}%. Out of time = the tab closes.", 10, Theme.Muted, false, s.Inner));
        foreach (var site in st.Sites.OrderBy(x => !x.Half).ThenBy(x => x.Domain).ToList())
        {
            var r = Row(s, s.Inner);
            r.WrapContents = false;
            r.Controls.Add(new PictureBox { Image = Theme.Icon(site.Half ? "movie" : "public", site.Half ? Theme.Accent : Theme.Muted, 18), Size = new Size(18, 18), Margin = new Padding(0, 8, 8, 0), BackColor = Color.Transparent });
            var dl = L(site.Domain, 10.5f, Theme.Text, false, 260);
            dl.AutoSize = false;
            dl.Size = new Size(260, 30);
            dl.TextAlign = ContentAlignment.MiddleLeft;
            r.Controls.Add(dl);
            r.Controls.Add(new Chip(site.Half ? $"{st.EngagingCostPct}%" : "Full", site.Half ? Theme.Accent : Theme.Danger) { Margin = new Padding(0, 6, 10, 0) });
            r.Controls.Add(Btn(site.Half ? "Make full price" : $"Make {st.EngagingCostPct}%", () =>
            {
                var now = Rules.ToggleSiteHalf(site);
                if (!now) MessageBox.Show("That makes it cheaper, so it kicks in 24 hours from now.", "Unrot");
                Rebuild();
            }, GameButton.Kind.Ghost));
            r.Controls.Add(Btn("Remove", () =>
            {
                var now = Rules.RemoveSite(site.Domain);
                MessageBox.Show(now ? "Removed." : "It'll be unblocked in 24 hours (the PIN skips the wait).", "Unrot");
                Rebuild();
            }, GameButton.Kind.Ghost));
        }
        s.Controls.Add(Btn("Block a site…", () =>
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
        }, GameButton.Kind.Secondary, "add"));

        // ---- Rules
        var rc = NewCard("Rules", "tune", Theme.Violet);
        rc.Controls.Add(L(Pin.Unlocked ? "PIN unlocked: changes apply right away." :
            "Stricter changes apply now. Looser ones wait 24 hours.", 10, Pin.Unlocked ? Theme.Accent : Theme.Muted, false, rc.Inner));
        foreach (var p in Store.S.Pending.OrderBy(p => p.ApplyAt).ToList())
        {
            var mins = Math.Max(0, (p.ApplyAt - Store.Now) / 60000);
            var r = Row(rc, rc.Inner);
            var pl = L($"⏳ {p.Label} · in {mins / 60}h {mins % 60}m", 10, Theme.Warn);
            pl.Margin = new Padding(0, 8, 10, 0);
            r.Controls.Add(pl);
            r.Controls.Add(Btn("Cancel", () => { Rules.Cancel(p.Key); Rebuild(); }, GameButton.Kind.Ghost));
        }
        var ratio = Num(st.FocusRatio, 0.1m, 100, 1);
        var freeRatio = Num(st.FreeRatio, 0.1m, 100, 1);
        var earlyEnd = Num(st.EarlyEndPct, 0, 100, 0);
        var bank = Num(st.MaxBankMin, 5, 1440, 0);
        var cap = Num(st.DailyCapMin, 15, 1440, 0);
        var engaging = Num(st.EngagingCostPct, 10, 100, 0);
        var grace = Num(st.GraceSec, 5, 600, 0);
        var payback = Num(st.PaybackMult, 1m, 5, 1);
        var prot = new CheckBox { Text = "Protect Unrot (stopping it needs the PIN)", Checked = st.Protection, ForeColor = Theme.Text, AutoSize = true, Font = Theme.Semi(10), BackColor = Color.Transparent };
        Header(rc, "Earning");
        RuleLine(rc, "Minutes locked in per 1 min earned", ratio);
        RuleLine(rc, "Free lock-in: minutes per 1 min earned", freeRatio);
        RuleLine(rc, "Pay for ending a lock-in early (%)", earlyEnd);
        Header(rc, "Limits");
        RuleLine(rc, "Most time you can save up (min)", bank);
        RuleLine(rc, "Most doomscrolling per day (min, phone + laptop)", cap);
        RuleLine(rc, "Games & movie sites cost (% of normal)", engaging);
        Header(rc, "Games");
        RuleLine(rc, "Seconds to save before a game closes", grace);
        RuleLine(rc, $"Overtime payback multiplier (overtime is up to {Ledger.OvertimeMin} min)", payback);
        prot.Margin = new Padding(0, 10, 0, 10);
        rc.Controls.Add(prot);
        rc.Controls.Add(Btn("Save rules", () =>
        {
            var delayed = Rules.ProposeSettings((double)ratio.Value, (int)bank.Value, (int)grace.Value, prot.Checked, (int)engaging.Value, (int)earlyEnd.Value)
                + Rules.ProposeExtra((double)freeRatio.Value, (double)payback.Value, (int)cap.Value);
            MessageBox.Show(delayed > 0 ? "Saved. Looser changes kick in 24 hours from now." : "Saved.", "Unrot");
            Rebuild();
        }, GameButton.Kind.Primary, "check_circle"));

        // ---- PIN
        var pc = NewCard("Family PIN", "key", Theme.Gold);
        if (!Pin.IsSet)
        {
            pc.Controls.Add(L("Have a family member pick a PIN you don't know. It's needed to stop Unrot or to skip the 24-hour wait.", 10, Theme.Muted, false, pc.Inner));
            pc.Controls.Add(Btn("Set family PIN", SetPin, GameButton.Kind.Secondary, "key"));
        }
        else if (Pin.Unlocked)
        {
            pc.Controls.Add(L($"Unlocked for about {(Store.S.AdminUntil - Store.Now) / 60000 + 1} more min.", 10.5f, Theme.Accent, true, pc.Inner));
            var r = Row(pc, pc.Inner);
            r.Controls.Add(Btn("Change PIN", SetPin));
            r.Controls.Add(Btn("Lock now", () => { Pin.Lock(); Rebuild(); }, GameButton.Kind.Ghost));
        }
        else
        {
            pc.Controls.Add(L("Locked. Your family member can unlock it for 10 minutes.", 10, Theme.Muted, false, pc.Inner));
            pc.Controls.Add(Btn("Enter PIN", () =>
            {
                var pin = Prompt.Ask("Unrot", "Family PIN:", password: true);
                if (pin == null) return;
                MessageBox.Show(Pin.Check(pin) ? "Unlocked for 10 minutes." : "Wrong PIN.", "Unrot");
                Rebuild();
            }, GameButton.Kind.Secondary, "key"));
        }

        // ---- Sync
        var sc = NewCard("Sync with your phone", "sync", Theme.Blue);
        sc.Controls.Add(L("Paste the same Firebase database URL and sync code as on your phone (steps are in the README).", 10, Theme.Muted, false, sc.Inner));
        TextBox Box(string text, string placeholder) => new()
        {
            Text = text, Width = sc.Inner, BackColor = Theme.Card2, ForeColor = Theme.Text, BorderStyle = BorderStyle.FixedSingle,
            PlaceholderText = placeholder, Font = Theme.Semi(10.5f), Margin = new Padding(0, 0, 0, 10),
        };
        var url = Box(Store.S.DbUrl, "https://….firebasedatabase.app");
        var code = Box(Store.S.SyncCode, "Sync code");
        sc.Controls.Add(url);
        sc.Controls.Add(code);
        var sr = Row(sc, sc.Inner);
        sr.Controls.Add(Btn("Save & sync now", async () =>
        {
            Store.S.DbUrl = url.Text.Trim();
            Store.S.SyncCode = code.Text.Trim();
            Store.Save();
            var ok = await Sync.RunAsync();
            MessageBox.Show(ok ? "Synced ✓" : "Sync failed: " + Store.S.LastSyncError, "Unrot");
            Rebuild();
        }, GameButton.Kind.Primary, "sync"));
        sr.Controls.Add(Btn("New code", () => { code.Text = Sync.NewCode(); }));
        var status = !Sync.Configured ? "Not set up" :
            Store.S.LastSyncError.Length > 0 ? "Last sync failed: " + Store.S.LastSyncError :
            Store.S.LastSyncTs == 0 ? "Not synced yet" :
            "Last synced " + DateTimeOffset.FromUnixTimeMilliseconds(Store.S.LastSyncTs).ToLocalTime().ToString("h:mm:ss tt", CultureInfo.CurrentCulture);
        sc.Controls.Add(L(status, 9, Store.S.LastSyncError.Length > 0 ? Theme.Warn : Theme.Muted, false, sc.Inner));
        foreach (var d in Ledger.Remotes())
            sc.Controls.Add(L($"• {d.Name}: earned {Ledger.FmtShort(d.Earned)}, spent {Ledger.FmtShort(d.Spent)}", 9, Theme.Muted, false, sc.Inner));

        Add(L($"Data: {Store.Dir}", 8, Theme.Muted, false, CW));
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

/// <summary>An icon in a soft tinted circle.</summary>
public class IconBadge : Control
{
    readonly string _icon;
    readonly Color _color;

    public IconBadge(string icon, Color color, int size)
    {
        _icon = icon;
        _color = color;
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer |
                 ControlStyles.SupportsTransparentBackColor, true);
        BackColor = Color.Transparent;
        Size = new Size(size, size);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        using var b = new SolidBrush(Theme.Tint(_color, Theme.Card, 0.2f));
        e.Graphics.FillEllipse(b, 0, 0, Width - 1, Height - 1);
        var s = Width / 2;
        e.Graphics.DrawImage(Theme.Icon(_icon, _color, s), (Width - s) / 2, (Height - s) / 2, s, s);
    }
}

/// <summary>A small rounded label, e.g. "+10 min".</summary>
public class Chip : Control
{
    readonly Color _color;

    public Chip(string text, Color color)
    {
        _color = color;
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer |
                 ControlStyles.SupportsTransparentBackColor, true);
        BackColor = Color.Transparent;
        Font = Theme.Heavy(9);
        Text = text;
        TextChanged += (_, _) => Fit();
        Fit();
    }

    void Fit()
    {
        var sz = TextRenderer.MeasureText(Text ?? "", Font);
        Size = new Size(sz.Width + 20, sz.Height + 8);
        Invalidate();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        using var p = Theme.Rounded(new RectangleF(0, 0, Width - 1, Height - 1), Height / 2f);
        using var b = new SolidBrush(Theme.Tint(_color, Theme.Card, 0.18f));
        e.Graphics.FillPath(b, p);
        var sz = TextRenderer.MeasureText(Text, Font);
        TextRenderer.DrawText(e.Graphics, Text, Font, new Point((Width - sz.Width) / 2, (Height - sz.Height) / 2), _color, TextFormatFlags.NoPadding);
    }
}

/// <summary>A small colored dot (activity list).</summary>
public class Dot : Control
{
    readonly Color _color;

    public Dot(Color color)
    {
        _color = color;
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.SupportsTransparentBackColor, true);
        BackColor = Color.Transparent;
        Size = new Size(8, 8);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        using var b = new SolidBrush(_color);
        e.Graphics.FillEllipse(b, 0, 0, 7, 7);
    }
}
