# sf3-farm — duel farm for SF3 farmed accounts (Android + Windows)

One engine, two shells: an Android app (full UI, overnight foreground service)
and a Windows CLI. Both drive `brawler_start` / `brawler_finish` wins (or
losses) across any number of accounts from the farm CSV, with relogin +
stuck-duel recovery and live leaderboard ranks.

## Quick start (Windows CLI)

```
./gradlew :cli:installDist
./cli/build/install/sf3farm-cli/bin/sf3farm-cli --csv scale_accounts.csv --region Mumbai --wins 100
```

Flags: `--csv PATH` (our CSV format: name,guid,sysid,host,...),
`--region Mumbai|Tokyo|US|EU|all`, `--wins N` (0 = unlimited),
`--mode win|loss`, `--board-id 322`, `--board-every-s 5`.

## Android app

Open in Android Studio (or wait for CI `sf3farm-debug-apk` artifact):
import CSV (all accounts pre-checked) -> pick region -> wins per account ->
START. Progress per account + leaderboard ranks update live; the run lives in
a foreground service. Progress (wins per guid) persists across restarts.

## Engine rules (proven against the live server)

- One TCP session per account; never parallelize duels on ONE account
  (`50003 BrawlerAlreadyStarted`). Parallelism unit = account.
- Every start pairs with a finish in the same flow. On (re)start, an open
  duel (get_player f13) is WIN-closed first — this also powers the restart
  failsafe (finish what the dead run left open).
- Any transport error -> reconnect + relogin, resume (counters are
  server-side; nothing is lost). Backoff on repeated failures.
- Duel throughput caps ~7/s per source IP (server matchmaking pacing);
  overlapping accounts keeps the pipe full but cannot exceed it.
- Board: `get_leaderboards{f1:322}` from any same-region session; rows are
  `FWLeaderboardRow{pid, name, power, rating, faction}`; matching is
  case-insensitive. Board snapshots lag live play by minutes — verify, don't
  chase.
