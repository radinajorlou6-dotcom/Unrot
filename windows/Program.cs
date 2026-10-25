using System.Diagnostics;
using Microsoft.Win32;

namespace Unrot;

static class Program
{
    public const string MutexName = @"Local\UnrotDesktopMutex";
    public const string ShowEventName = @"Local\UnrotShow";
    public const string UpdateEventName = @"Local\UnrotUpdate";

    public static string InstallDir => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Unrot", "app");
    public static string InstalledExe => Path.Combine(InstallDir, "Unrot.exe");

    [STAThread]
    static void Main(string[] args)
    {
        ApplicationConfiguration.Initialize();
        CrashReporting.Install();
        var self = Environment.ProcessPath;

        // Running the downloaded file: install (or update) to a permanent place, then start from there.
        if (!Rules.Same(Path.GetFullPath(self), Path.GetFullPath(InstalledExe)))
        {
            try
            {
                Directory.CreateDirectory(InstallDir);
                try { File.Delete(InstalledExe + ".old"); } catch { }
                if (File.Exists(InstalledExe)) File.Move(InstalledExe, InstalledExe + ".old", true);
                File.Copy(self, InstalledExe, true);

                if (EventWaitHandle.TryOpenExisting(UpdateEventName, out var upd)) upd.Set();
                // Wait (up to 8s) for the old version to close.
                for (int i = 0; i < 40; i++)
                {
                    if (!Mutex.TryOpenExisting(MutexName, out Mutex old)) break;
                    old.Dispose();
                    Thread.Sleep(200);
                }
                Process.Start(new ProcessStartInfo(InstalledExe, "--show") { UseShellExecute = true });
            }
            catch (Exception e)
            {
                MessageBox.Show("Couldn't install Unrot: " + e.Message, "Unrot");
            }
            return;
        }

        using var mutex = new Mutex(true, MutexName, out bool first);
        if (!first)
        {
            // Already running. Opening it shows the window; the watchdog and startup launches just leave.
            if (!args.Contains("--watchdog") && !args.Contains("--background") &&
                EventWaitHandle.TryOpenExisting(ShowEventName, out var show)) show.Set();
            return;
        }

        // Starting with Windows or from the watchdog stays in the tray; anything else opens the window.
        var quiet = args.Contains("--watchdog") || args.Contains("--background");
        Application.Run(new TrayApp(showWindow: !quiet));
        GC.KeepAlive(mutex);
    }
}

/// <summary>Starts with Windows and comes back within a minute if it's closed in Task Manager.</summary>
public static class Autostart
{
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    const string TaskName = "Unrot Watchdog";

    public static void Ensure()
    {
        var exe = Program.InstalledExe;
        try
        {
            using var k = Registry.CurrentUser.CreateSubKey(RunKey);
            k.SetValue("Unrot", $"\"{exe}\" --background");
        }
        catch { }
        Shortcuts(exe);
        Schtasks($"/Create /F /TN \"{TaskName}\" /SC MINUTE /MO 1 /TR \"\\\"{exe}\\\" --watchdog\"");
    }

    /// <summary>Start menu and desktop shortcuts, so Unrot can be opened like any other app.</summary>
    static void Shortcuts(string exe)
    {
        foreach (var dir in new[]
                 {
                     Environment.GetFolderPath(Environment.SpecialFolder.Programs),
                     Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
                 })
        {
            try
            {
                var lnk = Path.Combine(dir, "Unrot.lnk");
                if (File.Exists(lnk)) continue;
                var type = Type.GetTypeFromProgID("WScript.Shell");
                if (type == null) return;
                dynamic shell = Activator.CreateInstance(type);
                dynamic sc = shell.CreateShortcut(lnk);
                sc.TargetPath = exe;
                sc.WorkingDirectory = Path.GetDirectoryName(exe);
                sc.Description = "Unrot: earn your screen time";
                sc.Save();
            }
            catch { }
        }
    }

    public static void Remove()
    {
        try
        {
            using var k = Registry.CurrentUser.OpenSubKey(RunKey, true);
            k?.DeleteValue("Unrot", false);
        }
        catch { }
        Schtasks($"/Delete /F /TN \"{TaskName}\"");
    }

    static void Schtasks(string args)
    {
        try
        {
            using var p = Process.Start(new ProcessStartInfo("schtasks.exe", args)
            {
                CreateNoWindow = true,
                UseShellExecute = false,
                WindowStyle = ProcessWindowStyle.Hidden,
            });
            p?.WaitForExit(5000);
        }
        catch { }
    }
}

/// <summary>If Unrot ever crashes, say so (and save the details) instead of silently vanishing.</summary>
public static class CrashReporting
{
    public static string FilePath => Path.Combine(Store.Dir, "crash.txt");

    public static void Install()
    {
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (_, e) => Report(e.Exception);
        AppDomain.CurrentDomain.UnhandledException += (_, e) => Report(e.ExceptionObject as Exception);
    }

    static void Report(Exception e)
    {
        try
        {
            Directory.CreateDirectory(Store.Dir);
            File.WriteAllText(FilePath, DateTime.Now + "\n" + e);
        }
        catch { }
        try
        {
            MessageBox.Show(
                "Unrot hit an error. Send this to Claude so it can be fixed:\n\n" + e?.GetType().Name + ": " + e?.Message +
                "\n\nFull details saved to:\n" + FilePath,
                "Unrot", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
        catch { }
    }
}
