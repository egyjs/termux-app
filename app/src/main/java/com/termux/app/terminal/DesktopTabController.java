package com.termux.app.terminal;

import android.content.Intent;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.InputDevice;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.app.activities.SettingsActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.List;

/**
 * Manages the top desktop tab bar for Samsung DeX / mouse-and-keyboard workflows.
 */
public class DesktopTabController {

    private final TermuxActivity mActivity;
    private final LinearLayout mTabStrip;

    public DesktopTabController(TermuxActivity activity) {
        mActivity = activity;
        mTabStrip = activity.findViewById(R.id.desktop_tab_strip);

        View newTabButton = activity.findViewById(R.id.desktop_new_tab_button);
        if (newTabButton != null) {
            newTabButton.setOnClickListener(v -> {
                if (mActivity.getTermuxTerminalSessionClient() != null) {
                    mActivity.getTermuxTerminalSessionClient().addNewSession(false, null);
                }
            });
        }

        View splitHorizontalBtn = activity.findViewById(R.id.desktop_split_horizontal_button);
        if (splitHorizontalBtn != null) {
            splitHorizontalBtn.setOnClickListener(v -> mActivity.toggleDesktopSplitPane(false));
        }

        View splitVerticalBtn = activity.findViewById(R.id.desktop_split_vertical_button);
        if (splitVerticalBtn != null) {
            splitVerticalBtn.setOnClickListener(v -> mActivity.toggleDesktopSplitPane(true));
        }

        View settingsBtn = activity.findViewById(R.id.desktop_settings_button);
        if (settingsBtn != null) {
            settingsBtn.setOnClickListener(v ->
                ActivityUtils.startActivity(mActivity, new Intent(mActivity, SettingsActivity.class))
            );
        }
    }

    public void refreshTabs() {
        if (mTabStrip == null) return;
        mTabStrip.removeAllViews();

        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        List<TermuxSession> sessions = service.getTermuxSessions();
        TerminalSession currentSession = mActivity.getCurrentSession();
        LayoutInflater inflater = mActivity.getLayoutInflater();

        for (int i = 0; i < sessions.size(); i++) {
            final TermuxSession termuxSession = sessions.get(i);
            final TerminalSession session = termuxSession.getTerminalSession();
            if (session == null) continue;

            View tabView = inflater.inflate(R.layout.item_desktop_tab, mTabStrip, false);
            View indicator = tabView.findViewById(R.id.desktop_tab_active_indicator);
            TextView titleView = tabView.findViewById(R.id.desktop_tab_title);
            TextView closeBtn = tabView.findViewById(R.id.desktop_tab_close);

            boolean isSelected = (session == currentSession);
            indicator.setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
            tabView.setBackgroundColor(Color.parseColor(isSelected ? "#1E3A6D" : "#0F1E3A"));

            String label = "[" + (i + 1) + "] ";
            if (!TextUtils.isEmpty(session.mSessionName)) {
                label += session.mSessionName;
            } else if (!TextUtils.isEmpty(session.getTitle())) {
                label += session.getTitle();
            } else {
                label += "bash";
            }
            titleView.setText(label);

            tabView.setOnClickListener(v -> {
                if (mActivity.getTermuxTerminalSessionClient() != null) {
                    mActivity.getTermuxTerminalSessionClient().setCurrentSession(session);
                    refreshTabs();
                }
            });

            tabView.setOnLongClickListener(v -> {
                if (mActivity.getTermuxTerminalSessionClient() != null) {
                    mActivity.getTermuxTerminalSessionClient().renameSession(session);
                }
                return true;
            });

            // Support middle-click on tab to close session
            tabView.setOnTouchListener((v, event) -> {
                if (event.isFromSource(InputDevice.SOURCE_MOUSE) &&
                    event.getActionMasked() == MotionEvent.ACTION_DOWN &&
                    event.isButtonPressed(MotionEvent.BUTTON_TERTIARY)) {
                    closeSession(session);
                    return true;
                }
                return false;
            });

            closeBtn.setOnClickListener(v -> closeSession(session));

            mTabStrip.addView(tabView);
        }
    }

    private void closeSession(TerminalSession session) {
        if (session == null || mActivity.getTermuxTerminalSessionClient() == null) return;
        if (session.isRunning()) {
            session.finishIfRunning();
        }
        mActivity.getTermuxTerminalSessionClient().removeFinishedSession(session);
        refreshTabs();
    }
}
