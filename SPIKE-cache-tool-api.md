# SPIKE: external "cache tool" add-on API (write side)

**Status:** throwaway spike to gauge magnitude + run the airplane-mode demo. **Not for merge.**

## What it proves

That an external app (e.g. a coordinate solver) can push a solved stage coordinate
into the cache c:geo already holds — **on-device, fully offline** — and have the
map/detail update **live**. This is the field workflow that today only works by
detouring through the authoritative site over the network (and therefore breaks
in a dead zone).

## Why it's small

It adds **no new storage** — it wires one external door onto machinery c:geo
already has:

| Need | Existing c:geo API reused |
|------|---------------------------|
| load the cache | `DataStore.loadCache(geocode, LoadFlags.LOAD_ALL_DB_ONLY)` |
| add/update a waypoint | `Geocache.addOrChangeWaypoint(wp, true)` |
| set corrected coords | `Geocache.setUserModifiedCoords(true)` + `DataStore.saveUserModifiedCoords(cache)` |
| live map/detail refresh | `GeocacheChangedBroadcastReceiver.sendBroadcast(ctx, geocode)` (in-process LocalBroadcast) |

The only new code is the entry point itself:
`main/src/main/java/cgeo/geocaching/addon/CacheToolApiReceiver.java`
plus its manifest registration.

## Run the demo

1. Build/run this branch; in c:geo **store** a cache (e.g. `GC2YV9A`) and open its
   **map** (or detail view).
2. Fire a waypoint write from adb (no second app needed):

   ```sh
   adb shell am broadcast \
     -n cgeo.geocaching/cgeo.geocaching.addon.CacheToolApiReceiver \
     -a cgeo.geocaching.addon.WRITE_WAYPOINT \
     --es geocode GC2YV9A --es name "Stage 2" --es wptType stage \
     --ef lat 49.12345 --ef lon 7.98765
   ```

   The waypoint appears on the open map immediately.

3. Write the final and move the cache coordinate too:

   ```sh
   adb shell am broadcast \
     -n cgeo.geocaching/cgeo.geocaching.addon.CacheToolApiReceiver \
     -a cgeo.geocaching.addon.WRITE_WAYPOINT \
     --es geocode GC2YV9A --es name "Final" --es wptType final \
     --ef lat 49.13000 --ef lon 7.99000 --ez asFinal true
   ```

4. **The point:** put the phone in **airplane mode** and repeat. It still works —
   no network, instant. That is the thing Share/GPX and the GC.com round-trip
   cannot do.

## What a real API would add (the actual cost is here, not in the code volume)

- **Permission**: a custom `cgeo.geocaching.permission.CACHE_TOOL_API`, enforced on
  the component (the spike is intentionally unguarded for adb).
- **Stable contract**: a documented payload (intent extras or AIDL/ContentProvider),
  versioned; ideally a small parcelable instead of loose extras.
- **Outbound handoff**: a "send to cache tool" item in the cache detail menu that
  launches registered add-ons (Locus-style action + `PackageManager` discovery),
  passing the geocode.
- **Scoping/validation**: only ever write user-defined waypoints / user-modified
  coords; never clobber owner data; validate coordinates and geocode ownership.
- **Read side** (optional v2): expose cache + waypoints to the tool.

## Magnitude verdict

Engineering is **small-to-moderate** (the data layer is 100% reuse). The real
gating cost is **contract design + security review of an exported write path**,
not lines of code.
