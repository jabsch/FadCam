package com.fadcam.ui;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.fadcam.R;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * Base Fragment class that provides common functionality for all fragments,
 * including handling of back button press consistently.
 */
public abstract class BaseFragment extends Fragment {
    
    /**
     * Override this method to handle back presses in your fragment.
     * Return true if the back press was handled, false to let the activity handle it.
     */
    protected boolean onBackPressed() {
        return false;
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Add a callback for handling back presses
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), 
                new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                FLog.d("OverlayDebug","BaseFragment handleOnBackPressed fragment="+getClass().getSimpleName());
                try {
                    android.view.View overlay = requireActivity().findViewById(R.id.overlay_fragment_container);
                    boolean overlayVisible = overlay != null && overlay.getVisibility() == android.view.View.VISIBLE;
                    // Hidden tabs stay resumed, and a tab under a visible settings/trash overlay must not
                    // consume the press (e.g. clear a Records search instead of closing Settings)
                    boolean inOverlay = getId() == R.id.overlay_fragment_container;
                    if (isHidden() || (!inOverlay && overlayVisible)) {
                        passToNext(this);
                        return;
                    }
                    // First give the fragment a chance to handle the back press
                    if (onBackPressed()) {
                        return;
                    }
                    if (overlayVisible || !(requireActivity() instanceof com.fadcam.MainActivity)) {
                        // Let MainActivity's handler pop the overlay
                        passToNext(this);
                        return;
                    }
                    com.fadcam.MainActivity mainActivity = (com.fadcam.MainActivity) requireActivity();
                    if (mainActivity.getCurrentFragmentPosition() != 0) {
                        mainActivity.switchFragment(0, true);
                    } else {
                        // Home tab: MainActivity handles "press back again to exit"
                        passToNext(this);
                    }
                } catch (Exception e) {
                    passToNext(this);
                }
            }
        });
    }

    /**
     * Forwards this back press to the next callback, then re-enables {@code callback} so it keeps
     * working on later presses (a permanent setEnabled(false) silently broke back handling).
     */
    private void passToNext(OnBackPressedCallback callback) {
        if (getActivity() == null) return;
        callback.setEnabled(false);
        try {
            getActivity().getOnBackPressedDispatcher().onBackPressed();
        } finally {
            callback.setEnabled(true);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Ensure overlay container visibility updates if this was last fragment
        if(getActivity() instanceof com.fadcam.MainActivity){
            FLog.d("OverlayDebug","BaseFragment onDestroyView fragment="+getClass().getSimpleName()+" backStackBefore="+getActivity().getSupportFragmentManager().getBackStackEntryCount());
            ((com.fadcam.MainActivity) getActivity()).hideOverlayIfNoFragments();
        }
    }
} 