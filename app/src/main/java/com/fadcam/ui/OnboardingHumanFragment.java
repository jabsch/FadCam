package com.fadcam.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.UnderlineSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.PorterDuff;
import com.google.android.material.button.MaterialButton;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.airbnb.lottie.LottieAnimationView;
import com.fadcam.R;

public class OnboardingHumanFragment extends Fragment {
    private static final String STATE_CHECKED_1 = "human_checked_1";
    private static final String STATE_CHECKED_2 = "human_checked_2";
    private static final String STATE_CHECKED_3 = "human_checked_3";
    private boolean checked1 = false;
    private boolean checked2 = false;
    private boolean checked3 = false;
    private MaterialButton continueButton;
    private ImageView icon1, icon2, icon3;
    private TextView label1, label2, label3;
    private TextView titleText, descText;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.onboarding_human_slide, container, false);
        // Rotation recreates the fragment, which unticked every box and disabled the button
        if (savedInstanceState != null) {
            checked1 = savedInstanceState.getBoolean(STATE_CHECKED_1, checked1);
            checked2 = savedInstanceState.getBoolean(STATE_CHECKED_2, checked2);
            checked3 = savedInstanceState.getBoolean(STATE_CHECKED_3, checked3);
        }

        LinearLayout row1 = v.findViewById(R.id.checkbox_row_1);
        LinearLayout row2 = v.findViewById(R.id.checkbox_row_2);
        LinearLayout row3 = v.findViewById(R.id.checkbox_row_3);
        icon1 = v.findViewById(R.id.checkbox_icon_1);
        icon2 = v.findViewById(R.id.checkbox_icon_2);
        icon3 = v.findViewById(R.id.checkbox_icon_3);
        label1 = v.findViewById(R.id.checkbox_label_1);
        label2 = v.findViewById(R.id.checkbox_label_2);
        label3 = v.findViewById(R.id.checkbox_label_3);
        continueButton = v.findViewById(R.id.btn_human_continue);
        titleText = v.findViewById(R.id.tvHumanTitle);
        descText = v.findViewById(R.id.tvHumanDesc);
        
        // Find the PalestineRainView and prevent it from intercepting touch events
        View palestineRainView = v.findViewById(R.id.palestineRainView);
        if (palestineRainView != null) {
            palestineRainView.setClickable(false);
            palestineRainView.setFocusable(false);
        }
        
        // Configure the LottieAnimationView
        LottieAnimationView lottieHuman = v.findViewById(R.id.lottieHuman);
        if (lottieHuman != null) {
            lottieHuman.setSpeed(0.8f);
        }

        // Set initial button state
        continueButton.setEnabled(false);
        continueButton.setAlpha(0.6f);

        // Get the redPastel color
        int redPastelColor = ContextCompat.getColor(requireContext(), R.color.redPastel);

        View.OnClickListener update = view -> {
            continueButton.setEnabled(checked1 && checked2 && checked3);
            continueButton.setAlpha((checked1 && checked2 && checked3) ? 1f : 0.6f);
        };

        View.OnClickListener toggle1 = view -> {
            checked1 = !checked1;
            // Set the appropriate checkbox image and apply redPastel tint when checked
            icon1.setImageResource(checked1 ? R.drawable.placeholder_checkbox_checked : R.drawable.placeholder_checkbox_outline);
            if (checked1) {
                // Apply redPastel tint to the checked checkbox
                icon1.setColorFilter(redPastelColor, PorterDuff.Mode.SRC_IN);
            } else {
                // Clear any color filter for unchecked state
                icon1.clearColorFilter();
            }
            update.onClick(view);
        };
        
        View.OnClickListener toggle2 = view -> {
            checked2 = !checked2;
            // Set the appropriate checkbox image and apply redPastel tint when checked
            icon2.setImageResource(checked2 ? R.drawable.placeholder_checkbox_checked : R.drawable.placeholder_checkbox_outline);
            if (checked2) {
                // Apply redPastel tint to the checked checkbox
                icon2.setColorFilter(redPastelColor, PorterDuff.Mode.SRC_IN);
            } else {
                // Clear any color filter for unchecked state
                icon2.clearColorFilter();
            }
            update.onClick(view);
        };

        View.OnClickListener toggle3 = view -> {
            checked3 = !checked3;
            // Set the appropriate checkbox image and apply redPastel tint when checked
            icon3.setImageResource(checked3 ? R.drawable.placeholder_checkbox_checked : R.drawable.placeholder_checkbox_outline);
            if (checked3) {
                // Apply redPastel tint to the checked checkbox
                icon3.setColorFilter(redPastelColor, PorterDuff.Mode.SRC_IN);
            } else {
                // Clear any color filter for unchecked state
                icon3.clearColorFilter();
            }
            update.onClick(view);
        };

        applyPrivacyLabel();

        // Taps on the icon and label fall through to the row, so TalkBack gets one
        // checkbox per row instead of three stops that never said ticked or not.
        // Label 3 keeps its own listener for the Privacy Policy link.
        row1.setOnClickListener(toggle1);
        row2.setOnClickListener(toggle2);
        row3.setOnClickListener(toggle3);
        label3.setOnClickListener(toggle3);
        icon1.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        icon2.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        icon3.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        markCheckable(row1, () -> checked1);
        markCheckable(row2, () -> checked2);
        markCheckable(row3, () -> checked3);

        continueButton.setOnClickListener(view -> {
            if (getActivity() instanceof OnboardingActivity) {
                ((OnboardingActivity) getActivity()).finishOnboarding();
            } else {
                requireActivity().finish();
            }
        });

        // The pager rebuilds this view when swiping back; show the boxes already ticked
        applyCheckIcon(icon1, checked1, redPastelColor);
        applyCheckIcon(icon2, checked2, redPastelColor);
        applyCheckIcon(icon3, checked3, redPastelColor);
        update.onClick(continueButton);

        return v;
    }

    /** Reports the row as a checkbox with its ticked state; performClick then announces the change. */
    private static void markCheckable(View row, java.util.function.BooleanSupplier checked) {
        androidx.core.view.ViewCompat.setAccessibilityDelegate(row, new androidx.core.view.AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                    @NonNull androidx.core.view.accessibility.AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.CheckBox.class.getName());
                info.setCheckable(true);
                info.setChecked(checked.getAsBoolean());
            }
        });
    }

    /** Checkbox 3 text with its Privacy Policy link; also used after a language refresh. */
    private void applyPrivacyLabel() {
        if (label3 == null || !isAdded()) return;
        // Set up privacy policy clickable text
        String fullText = getString(R.string.onboarding_human_checkbox3);
        SpannableString spannableString = new SpannableString(fullText);
        
        // Find "Privacy Policy" in the text
        String privacyPolicyText = getString(R.string.onboarding_privacy_policy_link);
        int startIndex = fullText.indexOf(privacyPolicyText);
        int endIndex = startIndex + privacyPolicyText.length();
        if (startIndex < 0) {
            // Translation doesn't contain the phrase: link the whole line so the policy
            // can still be opened before agreeing to it
            startIndex = 0;
            endIndex = fullText.length();
        }
        {
            
            // Create clickable span for Privacy Policy
            ClickableSpan clickableSpan = new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    // Open privacy policy in external browser
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/jabsch/FadCam/blob/master/PRIVACY.md"));
                    try {
                        startActivity(intent);
                    } catch (android.content.ActivityNotFoundException e) {
                        android.widget.Toast.makeText(widget.getContext(), R.string.whats_new_no_browser,
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            };
            
            spannableString.setSpan(clickableSpan, startIndex, endIndex, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            spannableString.setSpan(new UnderlineSpan(), startIndex, endIndex, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        
        label3.setText(spannableString);
        label3.setMovementMethod(LinkMovementMethod.getInstance());
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_CHECKED_1, checked1);
        outState.putBoolean(STATE_CHECKED_2, checked2);
        outState.putBoolean(STATE_CHECKED_3, checked3);
    }

    private void applyCheckIcon(ImageView icon, boolean checked, int tint) {
        icon.setImageResource(checked ? R.drawable.placeholder_checkbox_checked : R.drawable.placeholder_checkbox_outline);
        if (checked) icon.setColorFilter(tint, PorterDuff.Mode.SRC_IN);
        else icon.clearColorFilter();
    }

    /**
     * Called to refresh language-specific UI elements when the language has changed
     * without recreating the entire fragment
     */
    public void refreshLanguage() {
        if (titleText != null) {
            titleText.setText(R.string.onboarding_human_title);
        }
        if (descText != null) {
            descText.setText(R.string.onboarding_human_desc);
        }
        if (label1 != null) {
            label1.setText(R.string.onboarding_human_checkbox1);
        }
        if (label2 != null) {
            label2.setText(R.string.onboarding_human_checkbox2);
        }
        // Keeps the Privacy Policy link that a plain setText dropped
        applyPrivacyLabel();
        if (continueButton != null) {
            continueButton.setText(R.string.onboarding_human_button);
            
            // Important: Force LTR layout direction for the button
            continueButton.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            continueButton.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            
            // Reset button state to match current checkbox status
            continueButton.setEnabled(checked1 && checked2 && checked3);
            continueButton.setAlpha((checked1 && checked2 && checked3) ? 1f : 0.6f);
        }
        
        // Force layout refresh to fix any RTL/LTR issues
        View view = getView();
        if (view != null) {
            view.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            view.requestLayout();
        }
    }
} 