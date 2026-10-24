package local.tidalpink;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.util.DisplayMetrics;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Everything that depends on the TV: pixel measurements, timings, colour and ring thickness, read from a JSON
 * file in assets/profiles/. The profile whose "match" names this TV's device or model is used; with none, the
 * first profile, its pixel values scaled from its measuredWidthPx to this screen's width.
 * Tidal's own view ids and screens stay in the code: they follow the Tidal version, not the TV.
 */
final class TvProfile {

    private static final String DIR = "profiles";

    final String name;
    final int color;
    final float ringDp, cornerDp;
    final long playerIdleMs, enterQuietMs, fromTidalMinMs, thumbQuietMs, preshowMs, undoToggleMs, holdLeftMs;
    final int thumbInset, thumbWidth, thumbGrow;
    final List<ShapeSpec> shapes = new ArrayList<>();

    /** One button's visible shape inside its view, in screen pixels (already scaled). */
    static final class ShapeSpec {
        String screen, id, kind;
        int[] inset = new int[4], innerInset = new int[4];
        int corner, innerCorner, stroke;
    }

    private TvProfile(JSONObject json, float scale) throws JSONException {
        name = json.optString("name", "unnamed");
        color = Color.parseColor(json.optString("color", "#FF10F0"));
        ringDp = (float) json.optDouble("ringDp", 6);
        cornerDp = (float) json.optDouble("cornerDp", 12);
        JSONObject t = json.optJSONObject("timing");
        if (t == null) t = new JSONObject();
        playerIdleMs = t.optLong("playerIdleMs", 4300);
        enterQuietMs = t.optLong("enterQuietMs", 400);
        fromTidalMinMs = t.optLong("fromTidalMinMs", 1000);
        thumbQuietMs = t.optLong("thumbQuietMs", 300);
        preshowMs = t.optLong("preshowMs", 250);
        undoToggleMs = t.optLong("undoToggleMs", 0);
        holdLeftMs = t.optLong("holdLeftMs", 450);
        JSONObject th = json.optJSONObject("thumb");
        if (th == null) th = new JSONObject();
        thumbInset = px(th.optInt("insetPx", 32), scale);
        thumbWidth = px(th.optInt("widthPx", 12), scale);
        thumbGrow = px(th.optInt("growPx", 4), scale);
        JSONArray list = json.optJSONArray("shapes");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject s = list.getJSONObject(i);
            ShapeSpec spec = new ShapeSpec();
            spec.screen = s.optString("screen", "any");
            spec.id = s.getString("id");
            spec.kind = s.optString("kind", "rounded");
            spec.inset = insets(s.optJSONArray("insetPx"), scale);
            spec.innerInset = insets(s.optJSONArray("innerInsetPx"), scale);
            spec.corner = px(s.optInt("cornerPx", 0), scale);
            spec.innerCorner = px(s.optInt("innerCornerPx", 0), scale);
            spec.stroke = px(s.optInt("strokePx", 0), scale);
            shapes.add(spec);
        }
    }

    private static int px(int measured, float scale) {
        return Math.round(measured * scale);
    }

    private static int[] insets(JSONArray a, float scale) throws JSONException {
        int[] out = new int[4];
        for (int i = 0; a != null && i < 4 && i < a.length(); i++) out[i] = px(a.getInt(i), scale);
        return out;
    }

    /** The profile for this TV: one that names its device or model, else the first, scaled to this screen. */
    static TvProfile load(Context context) {
        try {
            String[] files = context.getAssets().list(DIR);
            JSONObject first = null;
            if (files != null) {
                java.util.Arrays.sort(files);
                for (String file : files) {
                    if (!file.endsWith(".json")) continue;
                    JSONObject json = read(context, DIR + "/" + file);
                    if (first == null) first = json;
                    if (matches(json.optJSONObject("match"))) return new TvProfile(json, 1f);
                }
            }
            if (first != null) {
                DisplayMetrics metrics = context.getResources().getDisplayMetrics();
                int width = Math.max(metrics.widthPixels, metrics.heightPixels);
                return new TvProfile(first, width / (float) first.optInt("measuredWidthPx", 1920));
            }
            return new TvProfile(new JSONObject(), 1f);
        } catch (Exception e) {
            try {
                return new TvProfile(new JSONObject(), 1f);
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    private static boolean matches(JSONObject match) {
        if (match == null) return false;
        return listed(match.optJSONArray("device"), Build.DEVICE) || listed(match.optJSONArray("model"), Build.MODEL);
    }

    private static boolean listed(JSONArray values, String actual) {
        for (int i = 0; values != null && i < values.length(); i++) {
            if (values.optString(i).equalsIgnoreCase(actual)) return true;
        }
        return false;
    }

    private static JSONObject read(Context context, String path) throws Exception {
        try (InputStream in = context.getAssets().open(path)) {
            byte[] bytes = new byte[in.available()];
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) break;
                read += n;
            }
            return new JSONObject(new String(bytes, 0, read, StandardCharsets.UTF_8));
        }
    }
}
