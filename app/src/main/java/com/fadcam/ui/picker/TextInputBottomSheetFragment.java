package com.fadcam.ui.picker;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * TextInputBottomSheetFragment
 * Simple, styled text input bottom sheet consistent with NumberInputBottomSheetFragment.
 */
public class TextInputBottomSheetFragment extends BottomSheetDialogFragment {

    /** True when the system recreated this sheet (process death, or an undeclared config change). */
    private boolean restoredFromState;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restoredFromState = savedInstanceState != null;
    }

    public static final String ARG_TITLE = "title";
    public static final String ARG_VALUE = "value";
    public static final String ARG_HINT = "hint";
    public static final String ARG_DESCRIPTION = "description";
    public static final String ARG_RESULT_KEY = "result_key";
    public static final String RESULT_TEXT = "text_value";

    public static TextInputBottomSheetFragment newInstance(String title, String value, String hint, String description, String resultKey){
        TextInputBottomSheetFragment f = new TextInputBottomSheetFragment();
        Bundle b = new Bundle();
        b.putString(ARG_TITLE, title);
        b.putString(ARG_VALUE, value);
        b.putString(ARG_HINT, hint);
        b.putString(ARG_DESCRIPTION, description);
        b.putString(ARG_RESULT_KEY, resultKey);
        f.setArguments(b);
        return f;
    }

    private String title; private String value; private String hint; private String description; private String resultKey;
    private EditText field; private TextView helper; private Button ok;
    private Integer previousActivityNavBarColor; private Boolean previousActivityNavContrastEnforced;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.text_input_bottom_sheet, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle a = getArguments();
        if(a!=null){
            title = a.getString(ARG_TITLE, "");
            value = a.getString(ARG_VALUE, "");
            hint = a.getString(ARG_HINT, "");
            description = a.getString(ARG_DESCRIPTION, null);
            resultKey = a.getString(ARG_RESULT_KEY, "text_input_result");
        }
        TextView titleView = view.findViewById(R.id.text_input_title);
        field = view.findViewById(R.id.text_input_field);
        helper = view.findViewById(R.id.text_input_description);
        ok = view.findViewById(R.id.text_input_ok);
        Button cancel = view.findViewById(R.id.text_input_cancel);

        if(titleView!=null) titleView.setText(title);
        if(field!=null){
            field.setHint(hint);
            if(value!=null){ field.setText(value); field.setSelection(field.getText().length()); }
        }
        if(helper!=null){ if(description!=null && !description.isEmpty()){ helper.setText(description); helper.setVisibility(View.VISIBLE);} else { helper.setVisibility(View.GONE);} }
        cancel.setOnClickListener(v-> dismiss());
        ok.setOnClickListener(v->{
            String text = field!=null && field.getText()!=null ? field.getText().toString().trim() : "";
            Bundle res = new Bundle();
            res.putString(RESULT_TEXT, text);
            getParentFragmentManager().setFragmentResult(resultKey, res);
            dismiss();
        });
        field.addTextChangedListener(new TextWatcher(){ public void beforeTextChanged(CharSequence s,int a,int b,int c){} public void onTextChanged(CharSequence s,int a,int b,int c){ validate(); } public void afterTextChanged(Editable e){} });
        validate();
    }

    private void validate(){
        if(ok==null || field==null) return;
        String t = field.getText()!=null ? field.getText().toString().trim() : "";
        // Allow empty to mean "use default" for rename, but disable if whitespace-only
        ok.setEnabled(true);
    }

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
    }

    @Override
    public android.app.Dialog onCreateDialog(Bundle savedInstanceState) {
        android.app.Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((com.google.android.material.bottomsheet.BottomSheetDialog) dialog)
                .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(R.drawable.picker_bottom_sheet_gradient_bg_dynamic);
            }
        });
        if (dialog.getWindow() != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            com.fadcam.util.SystemBarUtil.setNavigationBarColor(dialog.getWindow(), android.graphics.Color.BLACK);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                dialog.getWindow().setNavigationBarContrastEnforced(false);
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                com.fadcam.util.SystemBarUtil.setNavigationBarIconsLight(dialog.getWindow(), false);
            }
        }
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        // Hosts register their result listener when the row is tapped, so after recreation
        // nothing listens: a pick here was lost, then applied silently on the row's next tap.
        // Close instead and let the user reopen the picker.
        if (restoredFromState) {
            dismissAllowingStateLoss();
            return;
        }
        if (getActivity() != null && getActivity().getWindow() != null
                && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            android.view.Window window = getActivity().getWindow();
            if (previousActivityNavBarColor == null) previousActivityNavBarColor = com.fadcam.util.SystemBarUtil.getNavigationBarColor(window); // first onStart only; later ones see our black
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                if (previousActivityNavContrastEnforced == null) previousActivityNavContrastEnforced = window.isNavigationBarContrastEnforced();
                window.setNavigationBarContrastEnforced(false);
            }
            com.fadcam.util.SystemBarUtil.setNavigationBarColor(window, android.graphics.Color.BLACK);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                com.fadcam.util.SystemBarUtil.setNavigationBarIconsLight(window, false);
            }
        }
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        if (getActivity() != null && getActivity().getWindow() != null
                && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP
                && previousActivityNavBarColor != null) {
            android.view.Window window = getActivity().getWindow();
            com.fadcam.util.SystemBarUtil.setNavigationBarColor(window, previousActivityNavBarColor);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
                    && previousActivityNavContrastEnforced != null) {
                window.setNavigationBarContrastEnforced(previousActivityNavContrastEnforced);
            }
        }
        super.onDismiss(dialog);
    }
}
