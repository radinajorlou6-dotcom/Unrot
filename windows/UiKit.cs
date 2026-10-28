using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Drawing.Text;
using System.Reflection;
using System.Runtime.InteropServices;

namespace Unrot;

/// <summary>Colors, fonts and icons, matching the phone app.</summary>
public static class Theme
{
    public static readonly Color Bg = Color.FromArgb(14, 12, 31);
    public static readonly Color Side = Color.FromArgb(20, 17, 42);
    public static readonly Color Card = Color.FromArgb(27, 24, 54);
    public static readonly Color Card2 = Color.FromArgb(40, 35, 75);
    public static readonly Color Stroke = Color.FromArgb(53, 48, 99);
    public static readonly Color Text = Color.FromArgb(245, 243, 255);
    public static readonly Color Muted = Color.FromArgb(169, 164, 208);
    public static readonly Color Accent = Color.FromArgb(61, 220, 151);
    public static readonly Color Violet = Color.FromArgb(139, 92, 246);
    public static readonly Color VioletDark = Color.FromArgb(90, 51, 196);
    public static readonly Color Gold = Color.FromArgb(255, 201, 77);
    public static readonly Color Fire = Color.FromArgb(255, 138, 61);
    public static readonly Color Blue = Color.FromArgb(79, 195, 247);
    public static readonly Color Warn = Color.FromArgb(255, 180, 84);
    public static readonly Color Danger = Color.FromArgb(255, 92, 122);

    public static Color Alpha(Color c, int a) => Color.FromArgb(a, c.R, c.G, c.B);

    /// <summary>Mixes [c] over the card color (WinForms can't draw see-through backgrounds well).</summary>
    public static Color Tint(Color c, Color over, float amount) => Color.FromArgb(
        (int)(over.R + (c.R - over.R) * amount), (int)(over.G + (c.G - over.G) * amount), (int)(over.B + (c.B - over.B) * amount));

    // ---------------------------------------------------------------- fonts (bundled)

    [DllImport("gdi32.dll")]
    static extern IntPtr AddFontMemResourceEx(IntPtr pbFont, uint cbFont, IntPtr pdv, ref uint pcFonts);

    static PrivateFontCollection _pfc;

    static void LoadFonts()
    {
        if (_pfc != null) return;
        _pfc = new PrivateFontCollection();
        foreach (var n in new[] { "Fredoka-SemiBold", "Nunito-Regular", "Nunito-SemiBold", "Nunito-ExtraBold" })
        {
            try
            {
                using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream($"fonts/{n}.ttf");
                if (s == null) continue;
                var bytes = new byte[s.Length];
                s.ReadExactly(bytes);
                // The memory has to stay alive for as long as the app runs, so it's never freed.
                var ptr = Marshal.AllocCoTaskMem(bytes.Length);
                Marshal.Copy(bytes, 0, ptr, bytes.Length);
                _pfc.AddMemoryFont(ptr, bytes.Length);
                uint count = 0;
                AddFontMemResourceEx(ptr, (uint)bytes.Length, IntPtr.Zero, ref count);
            }
            catch { }
        }
    }

    static readonly Dictionary<string, Font> _fonts = new();

    static Font F(string family, float size, string fallback = "Segoe UI", FontStyle fallbackStyle = FontStyle.Regular)
    {
        var key = family + size;
        if (_fonts.TryGetValue(key, out var f)) return f;
        LoadFonts();
        try
        {
            var fam = _pfc.Families.FirstOrDefault(x => x.Name == family);
            f = fam != null ? new Font(fam, size, FontStyle.Regular, GraphicsUnit.Point) : new Font(fallback, size, fallbackStyle);
        }
        catch { f = new Font(fallback, size, fallbackStyle); }
        _fonts[key] = f;
        return f;
    }

    public static Font Display(float size) => F("Fredoka SemiBold", size, "Segoe UI Semibold");
    public static Font Body(float size) => F("Nunito", size);
    public static Font Semi(float size) => F("Nunito SemiBold", size, "Segoe UI Semibold");
    public static Font Heavy(float size) => F("Nunito ExtraBold", size, "Segoe UI", FontStyle.Bold);

    // ---------------------------------------------------------------- icons (bundled white PNGs, tinted on demand)

    static readonly Dictionary<string, Image> _icons = new();

    public static Image Icon(string name, Color color, int size = 20)
    {
        var key = $"{name}|{color.ToArgb()}|{size}";
        if (_icons.TryGetValue(key, out var img)) return img;
        Bitmap result = new(size, size);
        try
        {
            using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream($"icons/{name}.png");
            if (s != null)
            {
                using var src = Image.FromStream(s);
                using var g = Graphics.FromImage(result);
                g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                g.SmoothingMode = SmoothingMode.AntiAlias;
                var cm = new ColorMatrix(new[]
                {
                    new float[] { 0, 0, 0, 0, 0 },
                    new float[] { 0, 0, 0, 0, 0 },
                    new float[] { 0, 0, 0, 0, 0 },
                    new float[] { 0, 0, 0, color.A / 255f, 0 },
                    new float[] { color.R / 255f, color.G / 255f, color.B / 255f, 0, 1 },
                });
                using var ia = new ImageAttributes();
                ia.SetColorMatrix(cm);
                g.DrawImage(src, new Rectangle(0, 0, size, size), 0, 0, src.Width, src.Height, GraphicsUnit.Pixel, ia);
            }
        }
        catch { }
        _icons[key] = result;
        return result;
    }

    // ---------------------------------------------------------------- factories (also used by the block window)

    public static Label Label(string text, float size, Color color, bool bold = false, int width = 520) => new()
    {
        Text = text,
        Font = bold ? (size >= 14 ? Display(size) : Heavy(size)) : Body(size),
        ForeColor = color,
        BackColor = Color.Transparent,
        AutoSize = true,
        MaximumSize = new Size(width, 0),
        Margin = new Padding(0, 0, 0, 8),
    };

    public static GameButton Button(string text, Action onClick, bool primary = false, int width = 0) =>
        new(text, primary ? GameButton.Kind.Primary : GameButton.Kind.Secondary, onClick, width: width);

    public static GraphicsPath Rounded(RectangleF r, float radius)
    {
        var d = Math.Min(radius * 2, Math.Min(r.Width, r.Height));
        var p = new GraphicsPath();
        if (d <= 0) { p.AddRectangle(r); return p; }
        p.AddArc(r.X, r.Y, d, d, 180, 90);
        p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }

    /// <summary>A green hourglass dot for the tray and window icon.</summary>
    public static System.Drawing.Icon MakeIcon()
    {
        using var bmp = new Bitmap(32, 32);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);
            using var bg = new SolidBrush(Violet);
            g.FillEllipse(bg, 1, 1, 30, 30);
            using var gold = new SolidBrush(Gold);
            g.FillPolygon(gold, new[] { new Point(10, 8), new Point(22, 8), new Point(16, 16) });
            g.FillPolygon(gold, new[] { new Point(16, 16), new Point(22, 24), new Point(10, 24) });
        }
        return System.Drawing.Icon.FromHandle(bmp.GetHicon());
    }
}

/// <summary>A chunky rounded button with an optional icon, drawn by hand (anti-aliased).</summary>
public class GameButton : Control
{
    public enum Kind { Primary, Secondary, Success, Danger, Ghost }

    readonly Kind _kind;
    readonly string _icon;
    bool _hover, _down;

    public GameButton(string text, Kind kind, Action onClick, string icon = null, int width = 0)
    {
        Text = text;
        _kind = kind;
        _icon = icon;
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer |
                 ControlStyles.SupportsTransparentBackColor | ControlStyles.ResizeRedraw, true);
        BackColor = Color.Transparent;
        Font = Theme.Heavy(10.5f);
        Cursor = Cursors.Hand;
        Height = kind == Kind.Ghost ? 34 : 42;
        Margin = new Padding(0, 0, 10, 10);
        var textW = TextRenderer.MeasureText(text, Font).Width;
        Width = width > 0 ? width : textW + (icon != null ? 30 : 0) + 34;
        Click += (_, _) => onClick();
        MouseEnter += (_, _) => { _hover = true; Invalidate(); };
        MouseLeave += (_, _) => { _hover = false; _down = false; Invalidate(); };
        MouseDown += (_, _) => { _down = true; Invalidate(); };
        MouseUp += (_, _) => { _down = false; Invalidate(); };
    }

    (Color face, Color edge, Color fg) Colors() => _kind switch
    {
        Kind.Primary => (Theme.Violet, Theme.VioletDark, Color.White),
        Kind.Success => (Theme.Accent, Color.FromArgb(30, 158, 102), Color.FromArgb(5, 39, 26)),
        Kind.Danger => (Color.FromArgb(58, 23, 48), Color.FromArgb(36, 13, 29), Theme.Danger),
        Kind.Ghost => (Color.Transparent, Color.Transparent, Theme.Muted),
        _ => (Theme.Card2, Color.FromArgb(21, 18, 41), Theme.Text),
    };

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        var (face, edge, fg) = Colors();
        var edgeH = _kind == Kind.Ghost ? 0 : 4;
        var press = _down ? edgeH : 0;
        if (_kind == Kind.Ghost)
        {
            using var pen = new Pen(_hover ? Theme.Muted : Theme.Stroke, 1);
            using var p = Theme.Rounded(new RectangleF(0.5f, 0.5f, Width - 1, Height - 1), 10);
            g.DrawPath(pen, p);
        }
        else
        {
            using (var p = Theme.Rounded(new RectangleF(0, edgeH, Width, Height - edgeH), 12))
            using (var b = new SolidBrush(edge)) g.FillPath(b, p);
            var f = _hover && !_down ? ControlPaint.Light(face, 0.12f) : face;
            using (var p = Theme.Rounded(new RectangleF(0, press, Width, Height - edgeH), 12))
            using (var b = new SolidBrush(f)) g.FillPath(b, p);
        }
        var textSize = TextRenderer.MeasureText(Text, Font);
        var iconW = _icon != null ? 22 : 0;
        var x = (Width - textSize.Width - iconW) / 2;
        var y = press + (Height - edgeH - textSize.Height) / 2;
        if (_icon != null)
        {
            g.DrawImage(Theme.Icon(_icon, fg, 18), x, press + (Height - edgeH - 18) / 2, 18, 18);
            x += iconW;
        }
        TextRenderer.DrawText(g, Text, Font, new Point(x, y), fg, TextFormatFlags.NoPadding);
    }
}

/// <summary>A rounded card: paints itself, lays children out top to bottom.</summary>
public class Card : FlowLayoutPanel
{
    public Color Fill { get; set; } = Theme.Card;
    public Color Border { get; set; } = Theme.Stroke;

    public Card(int width)
    {
        FlowDirection = FlowDirection.TopDown;
        WrapContents = false;
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        Width = width;
        MinimumSize = new Size(width, 0);
        MaximumSize = new Size(width, 0);
        Padding = new Padding(20, 18, 20, 12);
        Margin = new Padding(0, 0, 0, 14);
        BackColor = Theme.Card;
        DoubleBuffered = true;
        SetStyle(ControlStyles.ResizeRedraw, true);
    }

    public int Inner => Width - Padding.Horizontal;

    /// <summary>The nearest real (non-transparent) background behind this card.</summary>
    Color Behind()
    {
        for (var p = Parent; p != null; p = p.Parent)
            if (p.BackColor.A == 255) return p.BackColor;
        return Theme.Bg;
    }

    protected override void OnPaintBackground(PaintEventArgs e)
    {
        e.Graphics.Clear(Behind());
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        using var p = Theme.Rounded(new RectangleF(0.5f, 0.5f, Width - 1, Height - 1), 18);
        using var b = new SolidBrush(Fill);
        e.Graphics.FillPath(b, p);
        using var pen = new Pen(Border, 1);
        e.Graphics.DrawPath(pen, p);
    }
}

/// <summary>Circular progress ring with a big number in the middle.</summary>
public class Ring : Control
{
    public float Fraction { get; set; }
    public Color Color { get; set; } = Theme.Gold;
    public string Big { get; set; } = "";
    public string Small { get; set; } = "";
    public int Thickness { get; set; } = 14;

    public Ring(int size)
    {
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer |
                 ControlStyles.SupportsTransparentBackColor, true);
        BackColor = Color.Transparent;
        Size = new Size(size, size);
        Margin = new Padding(0, 0, 0, 10);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
        float t = Thickness;
        var r = new RectangleF(t, t, Width - 2 * t, Height - 2 * t);
        using (var track = new Pen(Theme.Card2, t)) g.DrawEllipse(track, r);
        var f = Math.Clamp(Fraction, 0f, 1f);
        if (f > 0)
        {
            using var glow = new Pen(Theme.Alpha(Color, 70), t * 1.7f) { StartCap = LineCap.Round, EndCap = LineCap.Round };
            g.DrawArc(glow, r, -90, 360 * f);
            using var pen = new Pen(Color, t) { StartCap = LineCap.Round, EndCap = LineCap.Round };
            g.DrawArc(pen, r, -90, 360 * f);
        }
        var bigFont = Theme.Display(Math.Max(16, Width / 7f));
        var smallFont = Theme.Semi(10);
        var bs = g.MeasureString(Big, bigFont);
        var ss = g.MeasureString(Small, smallFont);
        var top = (Height - bs.Height - ss.Height) / 2;
        using (var b = new SolidBrush(Color)) g.DrawString(Big, bigFont, b, (Width - bs.Width) / 2, top);
        using (var b = new SolidBrush(Theme.Muted)) g.DrawString(Small, smallFont, b, (Width - ss.Width) / 2, top + bs.Height);
    }
}

/// <summary>A rounded progress bar.</summary>
public class Bar : Control
{
    public float Fraction { get; set; }
    public Color Color { get; set; } = Theme.Violet;

    public Bar(int width, int height = 10)
    {
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer |
                 ControlStyles.SupportsTransparentBackColor, true);
        BackColor = Color.Transparent;
        Size = new Size(width, height);
        Margin = new Padding(0, 2, 0, 10);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        using (var p = Theme.Rounded(new RectangleF(0, 0, Width, Height), Height / 2f))
        using (var b = new SolidBrush(Theme.Card2)) g.FillPath(b, p);
        var f = Math.Clamp(Fraction, 0f, 1f);
        if (f <= 0) return;
        using (var p = Theme.Rounded(new RectangleF(0, 0, Math.Max(Height, Width * f), Height), Height / 2f))
        using (var b = new SolidBrush(Color)) g.FillPath(b, p);
    }
}

/// <summary>Seven bars with day labels and an optional dashed target line.</summary>
public class Chart : Control
{
    public float[] Values { get; set; } = Array.Empty<float>();
    public string[] Labels { get; set; } = Array.Empty<string>();
    public float? Target { get; set; }
    public Func<float, Color> ColorFor { get; set; } = _ => Theme.Violet;

    public Chart(int width, int height)
    {
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer |
                 ControlStyles.SupportsTransparentBackColor, true);
        BackColor = Color.Transparent;
        Size = new Size(width, height);
        Margin = new Padding(0, 4, 0, 8);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        if (Values.Length == 0) return;
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        var labelH = 20f;
        var chartH = Height - labelH;
        var max = Math.Max(Values.Max(), (Target ?? 0) * 1.15f);
        if (max < 1) max = 1;
        var slot = (float)Width / Values.Length;
        var barW = slot * 0.5f;
        var font = Theme.Semi(9);
        for (int i = 0; i < Values.Length; i++)
        {
            var cx = slot * i + slot / 2;
            using (var p = Theme.Rounded(new RectangleF(cx - barW / 2, 0, barW, chartH), barW / 2.6f))
            using (var b = new SolidBrush(Theme.Card2)) g.FillPath(b, p);
            if (Values[i] > 0)
            {
                var h = Math.Max(barW * 0.6f, chartH * (Values[i] / max));
                using var p = Theme.Rounded(new RectangleF(cx - barW / 2, chartH - h, barW, h), barW / 2.6f);
                using var b = new SolidBrush(ColorFor(Values[i]));
                g.FillPath(b, p);
            }
            if (i < Labels.Length)
            {
                var sz = g.MeasureString(Labels[i], font);
                using var tb = new SolidBrush(Theme.Muted);
                g.DrawString(Labels[i], font, tb, cx - sz.Width / 2, Height - sz.Height);
            }
        }
        if (Target is float t)
        {
            var y = chartH - chartH * (t / max);
            using var pen = new Pen(Theme.Alpha(Theme.Text, 130), 1) { DashStyle = DashStyle.Dash };
            g.DrawLine(pen, 0, y, Width, y);
        }
    }
}

/// <summary>One item in the sidebar.</summary>
public class NavItem : Control
{
    readonly string _icon;
    public bool Selected { get; set; }
    bool _hover;

    public NavItem(string text, string icon, Action onClick)
    {
        Text = text;
        _icon = icon;
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer, true);
        BackColor = Theme.Side;
        Size = new Size(176, 46);
        Margin = new Padding(12, 2, 12, 2);
        Cursor = Cursors.Hand;
        Font = Theme.Heavy(11);
        Click += (_, _) => onClick();
        MouseEnter += (_, _) => { _hover = true; Invalidate(); };
        MouseLeave += (_, _) => { _hover = false; Invalidate(); };
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.Clear(Theme.Side);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        if (Selected || _hover)
        {
            using var p = Theme.Rounded(new RectangleF(0, 0, Width, Height), 14);
            using var b = new SolidBrush(Selected ? Theme.Tint(Theme.Violet, Theme.Side, 0.28f) : Theme.Tint(Theme.Card2, Theme.Side, 0.6f));
            g.FillPath(b, p);
        }
        var fg = Selected ? Theme.Text : Theme.Muted;
        g.DrawImage(Theme.Icon(_icon, Selected ? Theme.Violet : Theme.Muted, 22), 14, (Height - 22) / 2, 22, 22);
        var sz = TextRenderer.MeasureText(Text, Font);
        TextRenderer.DrawText(g, Text, Font, new Point(48, (Height - sz.Height) / 2), fg, TextFormatFlags.NoPadding);
    }
}
