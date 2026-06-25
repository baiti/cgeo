package cgeo.geocaching.addon;

import cgeo.geocaching.enumerations.LoadFlags;
import cgeo.geocaching.enumerations.WaypointType;
import cgeo.geocaching.location.Geopoint;
import cgeo.geocaching.models.Geocache;
import cgeo.geocaching.models.Waypoint;
import cgeo.geocaching.service.GeocacheChangedBroadcastReceiver;
import cgeo.geocaching.storage.DataStore;
import cgeo.geocaching.utils.Log;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.apache.commons.lang3.StringUtils;

/**
 * SPIKE — external "cache tool" add-on API (write side).
 *
 * <p>Proof-of-concept entry point that lets an external app (e.g. a coordinate
 * solver) push a solved stage coordinate into the cache cgeo already holds —
 * fully on-device / offline — and have the map/detail update live. It is the
 * irreducible core of a possible cgeo add-on API: it does not implement any new
 * storage, it only wires an external door onto machinery cgeo already has
 * ({@link DataStore} + {@link GeocacheChangedBroadcastReceiver}).</p>
 *
 * <p>Drive it from adb (cgeo running, cache GCxxxx already stored locally):</p>
 * <pre>
 *   adb shell am broadcast \
 *     -n cgeo.geocaching/cgeo.geocaching.addon.CacheToolApiReceiver \
 *     -a cgeo.geocaching.addon.WRITE_WAYPOINT \
 *     --es geocode GCxxxx --es name "Stage 2" --es wptType stage \
 *     --ef lat 49.12345 --ef lon 7.98765
 * </pre>
 * <p>Add {@code --ez asFinal true} to also set the cache's (user-modified) coordinates.</p>
 *
 * <p><b>SPIKE ONLY — do not merge as-is.</b> The receiver is exported WITHOUT
 * permission enforcement so the adb demo runs trivially. A real API MUST gate
 * this behind a custom permission (see the AndroidManifest spike comment),
 * validate/scope inputs, and only ever touch user-defined waypoints / user
 * coordinates — never owner data.</p>
 */
public class CacheToolApiReceiver extends BroadcastReceiver {

    public static final String ACTION_WRITE_WAYPOINT = "cgeo.geocaching.addon.WRITE_WAYPOINT";

    public static final String EXTRA_GEOCODE = "geocode";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_WPT_TYPE = "wptType";   // WaypointType id: "waypoint", "stage", "final", ...
    public static final String EXTRA_LAT = "lat";
    public static final String EXTRA_LON = "lon";
    public static final String EXTRA_AS_FINAL = "asFinal";   // also set the cache's user-modified coordinates

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || !ACTION_WRITE_WAYPOINT.equals(intent.getAction())) {
            return;
        }

        final String geocode = intent.getStringExtra(EXTRA_GEOCODE);
        final double lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN);
        final double lon = intent.getDoubleExtra(EXTRA_LON, Double.NaN);
        final String name = StringUtils.defaultIfBlank(intent.getStringExtra(EXTRA_NAME), "Stage");
        final String typeId = intent.getStringExtra(EXTRA_WPT_TYPE);
        final boolean asFinal = intent.getBooleanExtra(EXTRA_AS_FINAL, false);

        if (StringUtils.isBlank(geocode) || Double.isNaN(lat) || Double.isNaN(lon)) {
            Log.w("CacheToolApiReceiver: missing/invalid geocode/lat/lon");
            return;
        }

        // DB work off the main thread; keep the receiver alive until it finishes.
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                final Geocache cache = DataStore.loadCache(geocode, LoadFlags.LOAD_ALL_DB_ONLY);
                if (cache == null) {
                    Log.w("CacheToolApiReceiver: cache not stored locally: " + geocode);
                    return;
                }

                final Geopoint coords = new Geopoint(lat, lon);
                final WaypointType type = typeId != null ? WaypointType.findById(typeId) : WaypointType.WAYPOINT;

                final Waypoint wp = new Waypoint(name, type != null ? type : WaypointType.WAYPOINT, true);
                wp.setCoords(coords);
                cache.addOrChangeWaypoint(wp, true);   // saveToDatabase = true

                if (asFinal) {
                    cache.setCoords(coords);
                    cache.setUserModifiedCoords(true);
                    DataStore.saveUserModifiedCoords(cache);
                }

                // Live refresh — cgeo's in-process signal that repaints the open map / detail view.
                GeocacheChangedBroadcastReceiver.sendBroadcast(context, geocode);
                Log.i("CacheToolApiReceiver: wrote '" + name + "' to " + geocode + (asFinal ? " (+ final coords)" : ""));
            } catch (final Exception e) {
                Log.e("CacheToolApiReceiver: write failed", e);
            } finally {
                pending.finish();
            }
        }, "CacheToolApiReceiver").start();
    }
}
