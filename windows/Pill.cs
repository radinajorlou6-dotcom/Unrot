using System.Drawing.Drawing2D;
using System.Media;
using System.Runtime.InteropServices;

namespace Unrot;

/// <summary>
/// A small always-on-top pill in the top-right corner of a screen: your time left over games and blocked sites,
/// or your lock-in timer. Click-through, never takes focus. Grows into a big banner for warnings.
/// Note: games in "exclusive fullscreen" draw over everything; use borderless/windowed to see it.
/// The warnings also play a sound, which works either way.
/// </summary>
public class PillForm : Form
{
    [DllImport("user32.dll")]
    static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);

    static readonly IntPtr HWND_TOPMOST = new(-1);
    const uint SWP_NOACTIVATE = 0x10, SWP_SHOWWINDOW = 0x40;

    string _text = "", _icon;
    Color _fg = Theme.Gold, _bg = Theme.Card;
    bool _flashing;
    string _flash;
    long _flashUntil;

    public PillForm()
    {
        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        TopMost = true;
        StartPosition = FormStartPosition.Manual;
        BackColor = Theme.Bg;
        Opacity = 0.94;
        Size = new Size(170, 42);
        DoubleBuffered = true;
    }

    protected override bool ShowWithoutActivation => true;

    protected override CreateParams CreateParams
    {
        get
        {
            var cp = base.CreateParams;
            // Tool window (no taskbar/alt-tab), click-through, no activate, topmost.
            cp.ExStyle |= 0x80 | 0x20 | 0x08000000 | 0x8;
            return cp;
        }
    }

    /// <summary>Shows a big banner for a few seconds, with a sound.</summary>
    public void Flash(string text, int seconds, bool loud = true)
    {
        _flash = text;
        _flashUntil = Store.Now + seconds * 1000L;
        if (loud) { try { SystemSounds.Exclamation.Play(); } catch { } }
    }

    /// <summary>Shows the pill on the screen that [hwnd] is on (the primary screen if none).</summary>
    public void ShowOn(IntPtr hwnd, string text, Color fg, Color bg, string icon = "hourglass_top", bool bottom = false)
    {
        var screen = hwnd != IntPtr.Zero ? Screen.FromHandle(hwnd) : Screen.PrimaryScreen;
        _flashing = _flashUntil > Store.Now;
        _text = _flashing ? _flash : text;
        _icon = _flashing ? "warning" : icon;
        _fg = _flashing ? Color.FromArgb(42, 26, 5) : fg;
        _bg = _flashing ? Theme.Warn : bg;
        var font = Theme.Display(_flashing ? 17 : 13);
        var textW = TextRenderer.MeasureText(_text, font).Width;
        var iconW = _icon != null ? (_flashing ? 30 : 24) : 0;
        var w = Math.Max(_flashing ? 420 : 120, textW + iconW + 36);
        var h = _flashing ? 68 : 42;
        // Over games: top-right of the screen. Lock-in timer: bottom-right, just above the taskbar,
        // so it doesn't sit on top of browser and window buttons while you work.
        var b = bottom ? screen.WorkingArea : screen.Bounds;
        var x = _flashing ? b.Left + (b.Width - w) / 2 : b.Right - w - (bottom ? 16 : 24);
        var y = bottom && !_flashing ? b.Bottom - h - 16 : b.Top + 24;
        if (!Visible) Show();
        SetWindowPos(Handle, HWND_TOPMOST, x, y, w, h, SWP_NOACTIVATE | SWP_SHOWWINDOW);
        Invalidate();
    }

    protected override void OnResize(EventArgs e)
    {
        base.OnResize(e);
        // Rounded window shape.
        using var p = Theme.Rounded(new RectangleF(0, 0, Width, Height), Height / 2f);
        Region = new Region(p);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.Clear(_bg);
        using (var pen = new Pen(Theme.Alpha(_fg, 90), 1.5f))
        using (var p = Theme.Rounded(new RectangleF(1, 1, Width - 2.5f, Height - 2.5f), (Height - 2) / 2f))
            g.DrawPath(pen, p);
        var font = Theme.Display(_flashing ? 17 : 13);
        var sz = TextRenderer.MeasureText(_text, font);
        var iconS = _flashing ? 26 : 20;
        var iconW = _icon != null ? iconS + 6 : 0;
        var x = (Width - sz.Width - iconW) / 2;
        if (_icon != null)
        {
            g.DrawImage(Theme.Icon(_icon, _fg, iconS), x, (Height - iconS) / 2, iconS, iconS);
            x += iconW;
        }
        TextRenderer.DrawText(g, _text, font, new Point(x, (Height - sz.Height) / 2), _fg, TextFormatFlags.NoPadding);
    }

    public void HideNow()
    {
        if (Visible) Hide();
    }
}
