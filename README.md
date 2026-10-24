# Unrot

Scroll time you have to earn. Lock in, work out, or call family to earn minutes; TikTok, Instagram and co. (and games on your laptop) spend them. When you run out, Unrot doesn't just put up a wall. It puts something better in front of you: a one-tap call to family during the day, a voice note or brain dump at night.

## Ways to earn (all verified, nothing on your word)

| What | Default | Checked by |
|---|---|---|
| Lock in (study/work timer) | 3 min → 1 min | Unrot's own timer |
| Watch workouts (gym, soccer…) | 3 min → 1 min | Galaxy Watch → Health Connect |
| Family call, 5+ min | +10 min, once per person every 3h | Android call log |
| Steps | +2 min per 1,000 (max 30/day) | Galaxy Watch |
| Asleep by bedtime (6h+) | +15 min next morning | Galaxy Watch sleep tracking |
| New best run pace (2+ km) | +15 min | Watch GPS distance |
| Gym PR in FitNotes | +15 min each (max 45/day) | FitNotes export **plus** a watch workout that same day |

Every number is adjustable in Unrot → Rules (loosening waits 24h).

**FitNotes PRs:** after your workout, export from FitNotes as a CSV (spreadsheet) and share it to **"Unrot: check for PRs"**, or use **Check FitNotes for PRs** in Unrot and pick the file.

**Games & movies cost half.** Installed games and streaming apps (Netflix, Disney+, Prime Video, Crunchyroll…) are blocked at 50% cost, so procrastinating with something engaging is cheaper than doomscrolling. On the laptop, games and movie sites (Netflix, Twitch…) cost 50% too. Adjustable; the ½ toggle is in Edit blocked apps.

**Blocked sites.** youtube.com, instagram.com, x.com, tiktok.com, reddit.com and co. are blocked in Brave, Chrome and most phone browsers, and in Vivaldi/Chrome/Edge/Brave/Firefox on the laptop. They only cost time while the site is on screen. On the laptop, the tab closes when you run out.

**Lock in** lives at the top of the main Unrot screen.

**When you open a blocked app** you get a card with your balance and a family-call suggestion. "Continue" unlocks after 15 seconds (adjustable), and the card is skipped if you were in that app in the last 2 minutes. While you're in, a countdown pill stays on screen (drag it anywhere).

---

## 📱 Phone setup (about 10 minutes)

1. On your phone, open **Releases** in this repo (sign in to GitHub if it asks) and download **Unrot.apk**.
2. Open it. Android will warn about installing unknown apps: tap **Settings → Allow from this source**, go back, and **Install**.
3. Open Unrot and go down the **Setup** checklist. Each step has a button that takes you to the right screen.
   - **Blocker greyed out?** That's Android protecting you from sideloaded apps. Tap **App info** in Unrot → **⋮** (top right) → **Allow restricted settings**, then try again.
   - **Battery:** OnePlus kills background apps hard. Do the battery step, then also: App info → Battery → **Allow background activity**, and in Recents, pull down on Unrot's card to lock it.
4. **Watch workouts:** in **Samsung Health → ⋮ → Settings → Health Connect**, make sure syncing is on. Gym and soccer sessions from your Galaxy Watch then show up in Unrot automatically (walks don't count unless you turn that on).
5. Add family members (name + number), pick your blocked apps, and tap **Finish setup**.
6. When someone's around, hand them your phone to **set the family PIN**.

## 💻 Laptop setup (about 2 minutes)

1. Download **Unrot.exe** from Releases and run it.
2. Windows SmartScreen may say "Windows protected your PC". Click **More info → Run anyway** (it's unsigned because it's your own app).
3. It installs itself, starts with Windows, and sits in the system tray (bottom right, maybe behind the **^**). Click the green icon to open it.
4. Have your family member set the laptop's PIN too (it can be the same one).

Games from Steam, Epic, Riot, Xbox, EA, Ubisoft, GOG, Rockstar and Roblox count automatically. Anything else: **Add a game (.exe)**.

## 🔗 Sharing one balance between phone and laptop (about 5 minutes, optional)

Without this, each device has its own balance. With it, gym time on your phone pays for games on your laptop, and a lock-in on either device blocks both.

1. Go to **console.firebase.google.com** and sign in with Google.
2. **Create a project** (any name, like `unrot`). Turn off Google Analytics; you don't need it.
3. In the left menu: **Build → Realtime Database → Create database**. Pick the location closest to you, choose **Start in locked mode**, and click **Enable**.
4. Open the **Rules** tab, replace everything with this, and click **Publish**:

   ```json
   {
     "rules": {
       "unrot": {
         "$code": {
           ".read": true,
           ".write": true
         }
       }
     }
   }
   ```

5. On the **Data** tab, copy the URL at the top (looks like `https://unrot-xxxx-default-rtdb.firebaseio.com` or `…firebasedatabase.app`).
6. **Phone:** Unrot → Laptop sync → paste the URL → **New code**.
7. **Laptop:** Unrot → Sync with your phone → paste the same URL and type the same code → **Save & sync now**.

The sync code works like a password for your data, so don't post it anywhere. Only minute totals are shared, nothing personal.

---

## How the anti-cheat works

- **Looser changes wait 24 hours.** Lowering a ratio, unblocking an app, turning off bedtime, adding more free minutes: all of it is queued for a day. Stricter changes apply instantly. You can cancel a queued change any time.
- **Family PIN.** Someone else holds it. Entering it unlocks Unrot for 10 minutes, so changes apply immediately and protection can be turned off.
- **Self-protection (phone).** After setup, Unrot pushes you out of its own App info, uninstall, accessibility and device-admin screens unless the PIN was entered.
- **Watchdog (laptop).** If Unrot is closed in Task Manager, it's back within a minute. Stopping it properly needs the PIN.
- **Bedtime.** After 11pm, blocked apps first show a night screen: voice note to family, brain dump, or plan tomorrow. "Continue" only appears after 60 seconds.

## Updating

Every push to `main` builds a new release. Install the new APK or EXE over the old one and everything is kept.

## Project layout

- `android/`: the phone app (Kotlin, no external UI libraries).
- `windows/`: the laptop app (C# .NET 8, WinForms, system tray).
- `.github/workflows/build.yml`: builds both and publishes a release.
