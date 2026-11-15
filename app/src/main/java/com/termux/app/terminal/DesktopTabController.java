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

import androidx.drawerlayout.widget.DrawerLayout;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.app.activities.SettingsActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
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
            // Long press: named or failsafe session (was long press on the drawer's New session).
            newTabButton.setOnLongClickListener(v -> {
                mActivity.showNewNamedSessionDialog();
                return true;
            });
        }

        View keyboardBtn = activity.findViewById(R.id.desktop_keyboard_button);
        if (keyboardBtn != null) {
            keyboardBtn.setOnClickListener(v -> {
                if (mActivity.getTermuxTerminalViewClient() != null)
                    mActivity.getTermuxTerminalViewClient().onToggleSoftKeyboardRequest();
            });
            keyboardBtn.setOnLongClickListener(v -> {
                mActivity.toggleTerminalToolbar();
                return true;
            });
        }

        // The tab bar replaces the session drawer: no edge swipe (it also stole mouse drags that
        // start at column 0). The drawer can still be opened from code (extra-keys DRAWER key,
        // Ctrl+Alt+Right).
        View topBar = activity.findViewById(R.id.desktop_top_bar);
        if (topBar != null && topBar.getVisibility() == View.VISIBLE) {
            mActivity.getDrawer().setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
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

        // One tab per tab session; a split partner is shown inside its tab, not as a tab.
        DesktopSplitPaneManager splitPaneManager = mActivity.getDesktopSplitPaneManager();
        List<TerminalSession> tabs = new ArrayList<>();
        if (splitPaneManager != null) {
            tabs = splitPaneManager.getTabs();
        } else {
            for (TermuxSession ts : service.getTermuxSessions()) tabs.add(ts.getTerminalSession());
        }
        TerminalSession currentSession = mActivity.getCurrentSession();
        TerminalSession currentTab = (splitPaneManager != null && currentSession != null)
            ? splitPaneManager.tabOf(currentSession) : currentSession;
        LayoutInflater inflater = mActivity.getLayoutInflater();

        for (int i = 0; i < tabs.size(); i++) {
            final TerminalSession session = tabs.get(i);
            if (session == null) continue;

            View tabView = inflater.inflate(R.layout.item_desktop_tab, mTabStrip, false);
            View indicator = tabView.findViewById(R.id.desktop_tab_active_indicator);
            TextView titleView = tabView.findViewById(R.id.desktop_tab_title);
            TextView closeBtn = tabView.findViewById(R.id.desktop_tab_close);

            boolean isSelected = (session == currentTab);
            indicator.setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
            tabView.setBackgroundColor(Color.parseColor(isSelected ? "#1E3A6D" : "#0F1E3A"));

            String label = "[" + (i + 1) + "] " + sessionLabel(session);
            TerminalSession partner = splitPaneManager != null ? splitPaneManager.partnerOf(session) : null;
            if (partner != null) label += " | " + sessionLabel(partner);
            titleView.setText(label);

            tabView.setOnClickListener(v -> {
                if (mActivity.getTermuxTerminalSessionClient() != null) {
                    // setCurrentSession() refreshes the tabs itself.
                    mActivity.getTermuxTerminalSessionClient().setCurrentSession(session);
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

    /** Close the whole tab (both panes); confirms before killing a running process. */
    private void closeSession(TerminalSession tab) {
        if (mActivity.getTermuxTerminalSessionClient() == null) return;
        mActivity.getTermuxTerminalSessionClient().closeTab(tab);
    }

    private static String sessionLabel(TerminalSession session) {
        if (!TextUtils.isEmpty(session.mSessionName)) return session.mSessionName;
        if (!TextUtils.isEmpty(session.getTitle())) return session.getTitle();
        return "bash";
    }
}
