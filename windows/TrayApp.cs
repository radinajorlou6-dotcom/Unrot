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
    bool _warned2, _warned30, _syncing;

    public TrayApp()
    {
        Store.Load();
        try { File.Delete(Program.InstalledExe + ".old"); } catch { }
        _showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, Program.ShowEventName);
        _updateEvent = new EventWaitHandle(false, EventResetMode.AutoReset, Program.UpdateEventName);
        Autostart.Ensure();

        var menu = new ContextMenuStrip();
        menu.Items.Add("Open Unrot", null, (_, _) => ShowMain());
        menu.Items.Add("Lock in 25 min", null, (_, _) => StartFocus(25));
        menu.Items.Add("Lock in 60 min", null, (_, _) => StartFocus(60));
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
            ShowMain();
        }
        else
        {
            Balloon("Unrot is running", $"{Ledger.Fmt(Math.Max(0, Ledger.Balance()))} of game time left.");
        }
    }

    public void ShowMain()
    {
        if (_main == null || _main.IsDisposed) _main = new MainForm(this);
        _main.Show();
        if (_main.WindowState == FormWindowState.Minimized) _main.WindowState = FormWindowState.Normal;
        _main.Activate();
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
        if (earned >= 0)
        {
            Balloon("Lock-in complete 🎉", $"+{Ledger.FmtShort(earned)} of game time.");
            _ = SyncNow();
            _main?.Rebuild();
        }

        var fg = Games.ForegroundGame();
        if (fg != null && (_block == null || _block.IsDisposed))
        {
            Ledger.Spend(1);
            var bal = Ledger.Balance();
            if (bal is > 0 and <= 120 && !_warned2) { _warned2 = true; Balloon("2 minutes of game time left", "Find a place to save."); }
            if (bal is > 0 and <= 30 && !_warned30) { _warned30 = true; Balloon("30 seconds left", "Save now."); }
            if (bal > 180) { _warned2 = false; _warned30 = false; }
        }

        Enforce(fg != null);

        if (_ticks % 5 == 0)
        {
            Store.Save();
            var bal = Math.Max(0, Ledger.Balance());
            var t = Ledger.FocusActive ? $"Unrot · locked in, {Ledger.Fmt(Ledger.FocusRemainingSec)} left" : $"Unrot · {Ledger.Fmt(bal)} of game time";
            _icon.Text = t.Length > 63 ? t[..63] : t;
        }
        if (_ticks % 30 == 0) _ = SyncNow();
    }

    void Enforce(bool gameInFront)
    {
        var focus = Ledger.FocusActive;
        var bal = Ledger.Balance();
        var allowed = !focus && bal > 0;

        if (allowed)
        {
            if (_block != null && !_block.IsDisposed && !_block.Finished) _block.Close();
            return;
        }
        if (!gameInFront) return;
        if (_block != null && !_block.IsDisposed) return;

        var st = Rules.Current();
        // If you relaunch right after a game was closed, there's barely any grace period.
        var grace = Store.Now - _lastClosedAt < 10 * 60_000 ? 5 : Math.Max(5, st.GraceSec);
        var title = focus ? "You're locked in 🔒" : "Out of game time ⏳";
        var body = focus
            ? $"{Ledger.Fmt(Ledger.FocusRemainingSec)} left in your lock-in. Games close in a moment."
            : $"Every {Ledger.FmtRatio(st.FocusRatio)} min locked in earns 1 min of game time. Save now, your game closes in a moment.";
        _block = new BlockForm(title, body, grace,
            onExpire: () =>
            {
                var n = Games.CloseAll();
                _lastClosedAt = Store.Now;
                Store.AddLog(focus ? $"Closed {n} game(s) during a lock-in" : $"Closed {n} game(s), out of time");
            },
            onLockIn: () => ShowMain());
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

    public BlockForm(string title, string body, int seconds, Action onExpire, Action onLockIn)
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
        using var pen = new Pen(Theme.Accent, 2);
        e.Graphics.DrawRectangle(pen, 1, 1, Width - 3, Height - 3);
    }
}

public static class Theme
{
    public static readonly Color Bg = Color.FromArgb(15, 17, 21);
    public static readonly Color Card = Color.FromArgb(26, 30, 38);
    public static readonly Color Card2 = Color.FromArgb(35, 40, 51);
    public static readonly Color Text = Color.FromArgb(236, 239, 244);
    public static readonly Color Muted = Color.FromArgb(154, 163, 178);
    public static readonly Color Accent = Color.FromArgb(124, 217, 146);
    public static readonly Color Warn = Color.FromArgb(255, 180, 84);

    public static Label Label(string text, float size, Color color, bool bold = false, int width = 520) => new()
    {
        Text = text,
        Font = new Font("Segoe UI", size, bold ? FontStyle.Bold : FontStyle.Regular),
        ForeColor = color,
        AutoSize = true,
        MaximumSize = new Size(width, 0),
        Margin = new Padding(0, 0, 0, 8),
    };

    public static Button Button(string text, Action onClick, bool primary = false, int width = 0)
    {
        var b = new Button
        {
            Text = text,
            FlatStyle = FlatStyle.Flat,
            BackColor = primary ? Accent : Card2,
            ForeColor = primary ? Color.FromArgb(11, 26, 16) : Text,
            Font = new Font("Segoe UI", 10, primary ? FontStyle.Bold : FontStyle.Regular),
            AutoSize = width == 0,
            Height = 38,
            Padding = new Padding(10, 4, 10, 4),
            Margin = new Padding(0, 0, 8, 8),
            Cursor = Cursors.Hand,
        };
        if (width > 0) b.Width = width;
        b.FlatAppearance.BorderSize = 0;
        b.Click += (_, _) => onClick();
        return b;
    }

    /// <summary>A green hourglass-ish dot for the tray.</summary>
    public static Icon MakeIcon()
    {
        using var bmp = new Bitmap(32, 32);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);
            using var bg = new SolidBrush(Accent);
            g.FillEllipse(bg, 1, 1, 30, 30);
            using var dark = new SolidBrush(Color.FromArgb(18, 22, 29));
            g.FillPolygon(dark, new[] { new Point(10, 8), new Point(22, 8), new Point(16, 16) });
            g.FillPolygon(dark, new[] { new Point(16, 16), new Point(22, 24), new Point(10, 24) });
        }
        return Icon.FromHandle(bmp.GetHicon());
    }
}

/// <summary>Tiny input box.</summary>
public static class Prompt
{
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
