package com.fadcam.services;

import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import com.fadcam.R;
import com.fadcam.RecordingShortcutDispatcher;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.utils.RecordingPreset;

/**
 * Quick Settings tile that cycles the video presets (1080p 30, 1080p max,
 * 4K max, 1080p Auto fps). Each tap applies the next one; the subtitle shows
 * the current preset, or "Custom" after a hand-picked resolution or fps.
 */
public class RecordingPresetTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        refreshTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(this);
        RecordingPreset next = RecordingPreset.next(RecordingPreset.current(prefs));
        next.apply(this);
        refreshTile();
        if (RecordingShortcutDispatcher.hasActiveSession(this)) {
            Toast.makeText(this, getString(R.string.preset_applies_next_recording,
                    getString(next.labelRes)), Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        RecordingPreset preset = RecordingPreset.current(SharedPreferencesManager.getInstance(this));
        String value = preset != null ? getString(preset.labelRes) : getString(R.string.preset_custom);
        tile.setState(preset != null ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setLabel(getString(R.string.preset_tile_label));
            tile.setSubtitle(value);
        } else {
            tile.setLabel(value);
        }
        tile.updateTile();
    }
}
