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
                Process.Start(new ProcessStartInfo(InstalledExe) { UseShellExecute = true });
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
            // Already running. A double-click opens the window; the minute-by-minute watchdog just leaves.
            if (!args.Contains("--watchdog") && EventWaitHandle.TryOpenExisting(ShowEventName, out var show)) show.Set();
            return;
        }

        Application.Run(new TrayApp());
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
            k.SetValue("Unrot", $"\"{exe}\"");
        }
        catch { }
        Schtasks($"/Create /F /TN \"{TaskName}\" /SC MINUTE /MO 1 /TR \"\\\"{exe}\\\" --watchdog\"");
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
