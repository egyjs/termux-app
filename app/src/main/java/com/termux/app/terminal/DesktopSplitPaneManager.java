package com.termux.app.terminal;

import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.util.List;

/**
 * Manages dual-pane horizontal or vertical split views inside TermuxActivity.
 */
public class DesktopSplitPaneManager {

    private final TermuxActivity mActivity;
    private final LinearLayout mSplitContainer;
    private final View mSplitDivider;
    private final TerminalView mPrimaryView;
    private final TerminalView mSecondaryView;
    private boolean mIsSplitActive = false;

    public DesktopSplitPaneManager(TermuxActivity activity) {
        mActivity = activity;
        mSplitContainer = activity.findViewById(R.id.desktop_split_container);
        mSplitDivider = activity.findViewById(R.id.desktop_split_divider);
        mPrimaryView = activity.findViewById(R.id.terminal_view);
        mSecondaryView = activity.findViewById(R.id.terminal_view_secondary);

        if (mSecondaryView != null && mActivity.getTermuxTerminalViewClient() != null) {
            mSecondaryView.setTerminalViewClient(mActivity.getTermuxTerminalViewClient());
            mSecondaryView.setTextSize(mActivity.getPreferences().getFontSize());
            mSecondaryView.setTypeface(Typeface.MONOSPACE);
        }
    }

    public void toggleSplit(boolean vertical) {
        if (mSplitContainer == null || mSecondaryView == null || mSplitDivider == null) return;

        int targetOrientation = vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL;
        if (mIsSplitActive && mSplitContainer.getOrientation() == targetOrientation) {
            closeSplit();
            return;
        }

        mSplitContainer.setOrientation(targetOrientation);
        LinearLayout.LayoutParams primaryParams = (LinearLayout.LayoutParams) mPrimaryView.getLayoutParams();
        LinearLayout.LayoutParams secondaryParams = (LinearLayout.LayoutParams) mSecondaryView.getLayoutParams();
        LinearLayout.LayoutParams dividerParams = (LinearLayout.LayoutParams) mSplitDivider.getLayoutParams();

        if (vertical) {
            primaryParams.width = LinearLayout.LayoutParams.MATCH_PARENT;
            primaryParams.height = 0;
            secondaryParams.width = LinearLayout.LayoutParams.MATCH_PARENT;
            secondaryParams.height = 0;
            dividerParams.width = LinearLayout.LayoutParams.MATCH_PARENT;
            dividerParams.height = 4;
        } else {
            primaryParams.width = 0;
            primaryParams.height = LinearLayout.LayoutParams.MATCH_PARENT;
            secondaryParams.width = 0;
            secondaryParams.height = LinearLayout.LayoutParams.MATCH_PARENT;
            dividerParams.width = 4;
            dividerParams.height = LinearLayout.LayoutParams.MATCH_PARENT;
        }

        mPrimaryView.setLayoutParams(primaryParams);
        mSecondaryView.setLayoutParams(secondaryParams);
        mSplitDivider.setLayoutParams(dividerParams);

        TermuxService service = mActivity.getTermuxService();
        if (service != null) {
            List<TermuxSession> sessions = service.getTermuxSessions();
            TerminalSession primarySession = mPrimaryView.getCurrentSession();
            TerminalSession secondarySession = null;

            for (TermuxSession ts : sessions) {
                if (ts.getTerminalSession() != primarySession) {
                    secondarySession = ts.getTerminalSession();
                    break;
                }
            }

            if (secondarySession == null && mActivity.getTermuxTerminalSessionClient() != null) {
                String cwd = primarySession != null ? primarySession.getCwd() : mActivity.getProperties().getDefaultWorkingDirectory();
                TermuxSession created = service.createTermuxSession(null, null, null, cwd, false, null);
                if (created != null) {
                    secondarySession = created.getTerminalSession();
                }
            }

            if (secondarySession != null) {
                mSecondaryView.setTextSize(mActivity.getPreferences().getFontSize());
                mSecondaryView.attachSession(secondarySession);
            }
        }

        mSplitDivider.setVisibility(View.VISIBLE);
        mSecondaryView.setVisibility(View.VISIBLE);
        mIsSplitActive = true;
        mPrimaryView.updateSize();
        mSecondaryView.updateSize();
        mSecondaryView.requestFocus();
    }

    public void closeSplit() {
        if (!mIsSplitActive) return;
        mSplitDivider.setVisibility(View.GONE);
        mSecondaryView.setVisibility(View.GONE);
        mIsSplitActive = false;
        mPrimaryView.updateSize();
        mPrimaryView.requestFocus();
    }

    public boolean isSplitActive() {
        return mIsSplitActive;
    }

    public TerminalView getSecondaryView() {
        return mSecondaryView;
    }
}
