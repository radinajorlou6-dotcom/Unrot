using System.Media;
using System.Runtime.InteropServices;

namespace Unrot;

/// <summary>
/// A small always-on-top countdown in the corner of whatever screen the game or site is on.
/// Click-through, never takes focus. Grows into a big banner for warnings (15, 5 and 2 minutes left).
/// Note: games in "exclusive fullscreen" mode draw over everything; use borderless/windowed to see it.
/// The warnings also play a sound, which works either way.
/// </summary>
public class PillForm : Form
{
    [DllImport("user32.dll")]
    static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);

    static readonly IntPtr HWND_TOPMOST = new(-1);
    const uint SWP_NOACTIVATE = 0x10, SWP_SHOWWINDOW = 0x40;

    readonly Label _label;
    string _flash;
    long _flashUntil;

    public PillForm()
    {
        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        TopMost = true;
        StartPosition = FormStartPosition.Manual;
        BackColor = Theme.Card;
        Opacity = 0.92;
        Size = new Size(170, 42);
        _label = new Label
        {
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleCenter,
            Font = Theme.Display(13),
            ForeColor = Theme.Accent,
        };
        Controls.Add(_label);
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

    public void ShowOn(IntPtr hwnd, string text, Color fg, Color bg)
    {
        var screen = hwnd != IntPtr.Zero ? Screen.FromHandle(hwnd) : Screen.PrimaryScreen;
        var flashing = _flashUntil > Store.Now;
        _label.Text = flashing ? _flash : text;
        _label.Font = Theme.Display(flashing ? 18 : 13);
        _label.ForeColor = flashing ? Color.FromArgb(31, 20, 5) : fg;
        BackColor = flashing ? Theme.Warn : bg;
        var w = flashing ? 560 : 170;
        var h = flashing ? 72 : 42;
        var b = screen.Bounds;
        var x = flashing ? b.Left + (b.Width - w) / 2 : b.Right - w - 24;
        var y = b.Top + 24;
        if (!Visible) Show();
        SetWindowPos(Handle, HWND_TOPMOST, x, y, w, h, SWP_NOACTIVATE | SWP_SHOWWINDOW);
    }

    public void HideNow()
    {
        if (Visible) Hide();
    }
}
