package com.termux.app.terminal;

import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows desktop tabs in the activity: the primary pane always shows the current tab's session,
 * the secondary pane its split partner if the tab is split (see {@link DesktopTabModel}).
 *
 * Both panes share one {@link TermuxTerminalViewClient}, which acts on
 * {@link TermuxActivity#getTerminalView()}; that returns {@link #getActiveView()}, the pane
 * the user last focused.
 */
public class DesktopSplitPaneManager {

    /** Static so splits survive activity recreation (theme or DeX switch); sessions live in TermuxService. */
    private static final DesktopTabModel<TerminalSession> sTabs = new DesktopTabModel<>();

    private final TermuxActivity mActivity;
    private final LinearLayout mSplitContainer;
    private final View mSplitDivider;
    private final TerminalView mPrimaryView;
    private final TerminalView mSecondaryView;
    /** Divider thickness in px, taken from the layout XML. */
    private final int mDividerThickness;
    private boolean mIsSplitActive = false;
    private TerminalView mActiveView;

    public DesktopSplitPaneManager(TermuxActivity activity) {
        mActivity = activity;
        mSplitContainer = activity.findViewById(R.id.desktop_split_container);
        mSplitDivider = activity.findViewById(R.id.desktop_split_divider);
        mPrimaryView = activity.findViewById(R.id.terminal_view);
        mSecondaryView = activity.findViewById(R.id.terminal_view_secondary);
        mActiveView = mPrimaryView;
        mDividerThickness = mSplitDivider != null ? mSplitDivider.getLayoutParams().width : 0;

        if (mSecondaryView != null && mActivity.getTermuxTerminalViewClient() != null) {
            mSecondaryView.setTerminalViewClient(mActivity.getTermuxTerminalViewClient());
            mSecondaryView.setTextSize(mActivity.getPreferences().getFontSize());
            mSecondaryView.setTypeface(Typeface.MONOSPACE);
            mActivity.registerForContextMenu(mSecondaryView);
        }

        // TerminalView never calls super.onTouchEvent(), so a finger tap does not focus it.
        // While split, focus the touched pane first so the shared client acts on it.
        View.OnTouchListener focusOnTouch = (view, event) -> {
            if (mIsSplitActive && event.getActionMasked() == MotionEvent.ACTION_DOWN && !view.hasFocus())
                view.requestFocus();
            return false;
        };
        mPrimaryView.setOnTouchListener(focusOnTouch);
        if (mSecondaryView != null) mSecondaryView.setOnTouchListener(focusOnTouch);
    }

    /** The pane input and actions apply to: the focused pane, else the last focused one. */
    @NonNull
    public TerminalView getActiveView() {
        if (!mIsSplitActive) return mPrimaryView;
        if (mSecondaryView.hasFocus()) mActiveView = mSecondaryView;
        else if (mPrimaryView.hasFocus()) mActiveView = mPrimaryView;
        return mActiveView;
    }

    /** Tabs in display order; split partners are not tabs. */
    @NonNull
    public List<TerminalSession> getTabs() {
        List<TerminalSession> sessions = new ArrayList<>();
        TermuxService service = mActivity.getTermuxService();
        if (service != null) {
            for (TermuxSession ts : service.getTermuxSessions()) sessions.add(ts.getTerminalSession());
        }
        return sTabs.tabs(sessions);
    }

    /** The tab a session belongs to (itself, or the tab it is split into). */
    public TerminalSession tabOf(TerminalSession session) {
        return sTabs.tabOf(session);
    }

    /** Split partner of a tab, or null. */
    @Nullable
    public TerminalSession partnerOf(TerminalSession tab) {
        return sTabs.partnerOf(tab);
    }

    /**
     * Show the tab that owns {@code session} (with its split, if any) and focus the pane showing it.
     *
     * @return true if the focused pane now shows a different session than before.
     */
    public boolean showSession(@NonNull TerminalSession session) {
        TerminalSession before = getActiveView().getCurrentSession();
        TerminalSession tab = sTabs.tabOf(session);
        TerminalSession partner = sTabs.partnerOf(tab);

        mPrimaryView.attachSession(tab);
        if (partner != null && mSecondaryView != null) {
            showSecondary(sTabs.isVertical(tab));
            mSecondaryView.setTextSize(mActivity.getPreferences().getFontSize());
            mSecondaryView.attachSession(partner);
        } else {
            hideSecondary();
        }

        TerminalView target = (partner != null && session == partner) ? mSecondaryView : mPrimaryView;
        target.requestFocus();
        mActiveView = target;
        return before != session;
    }

    /**
     * Split button / Ctrl+Shift+D/E for the current tab: split it with a new session; pressing the
     * same orientation again closes that pane (confirming if its process runs); the other
     * orientation just changes the layout.
     */
    public void toggleSplit(boolean vertical) {
        if (mSplitContainer == null || mSecondaryView == null || mSplitDivider == null) return;
        TermuxTerminalSessionActivityClient client = mActivity.getTermuxTerminalSessionClient();
        TerminalSession current = mPrimaryView.getCurrentSession();
        if (client == null || current == null) return;

        TerminalSession tab = sTabs.tabOf(current);
        TerminalSession partner = sTabs.partnerOf(tab);
        if (partner != null) {
            if (sTabs.isVertical(tab) == vertical) {
                client.closeSession(partner);
            } else {
                sTabs.setVertical(tab, vertical);
                showSession(getActiveView().getCurrentSession());
            }
            return;
        }

        TerminalSession created = client.createSession(false, null);
        if (created == null) return;
        sTabs.split(tab, created, vertical);
        client.setCurrentSession(created);
    }

    /**
     * Remove a finished session from the service and the tabs, then show what is left: the same
     * tab without the closed pane, the promoted partner, or the neighbouring tab.
     */
    public void removeSession(@NonNull TerminalSession session) {
        TermuxService service = mActivity.getTermuxService();
        TermuxTerminalSessionActivityClient client = mActivity.getTermuxTerminalSessionClient();
        if (service == null || client == null) return;

        TerminalSession shownTab = mPrimaryView.getCurrentSession();
        TerminalSession focused = getActiveView().getCurrentSession();
        TerminalSession closedTab = sTabs.tabOf(session);
        int closedTabIndex = getTabs().indexOf(closedTab);

        TerminalSession owner = sTabs.remove(session);
        service.removeTermuxSession(session);

        if (service.getTermuxSessionsSize() == 0) {
            mActivity.finishActivityIfNotFinishing();
            return;
        }
        if (closedTab != shownTab) {
            // Another tab closed: what is on screen stays.
            mActivity.termuxSessionListNotifyUpdated();
            return;
        }

        TerminalSession next;
        if (owner != null) next = (focused != null && focused != session) ? focused : owner;
        else next = DesktopTabModel.afterClose(getTabs(), closedTabIndex);
        if (next != null) client.setCurrentSession(next);
    }

    private void showSecondary(boolean vertical) {
        mSplitContainer.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams primaryParams = (LinearLayout.LayoutParams) mPrimaryView.getLayoutParams();
        LinearLayout.LayoutParams secondaryParams = (LinearLayout.LayoutParams) mSecondaryView.getLayoutParams();
        LinearLayout.LayoutParams dividerParams = (LinearLayout.LayoutParams) mSplitDivider.getLayoutParams();

        if (vertical) {
            primaryParams.width = LinearLayout.LayoutParams.MATCH_PARENT;
            primaryParams.height = 0;
            secondaryParams.width = LinearLayout.LayoutParams.MATCH_PARENT;
            secondaryParams.height = 0;
            dividerParams.width = LinearLayout.LayoutParams.MATCH_PARENT;
            dividerParams.height = mDividerThickness;
        } else {
            primaryParams.width = 0;
            primaryParams.height = LinearLayout.LayoutParams.MATCH_PARENT;
            secondaryParams.width = 0;
            secondaryParams.height = LinearLayout.LayoutParams.MATCH_PARENT;
            dividerParams.width = mDividerThickness;
            dividerParams.height = LinearLayout.LayoutParams.MATCH_PARENT;
        }

        mPrimaryView.setLayoutParams(primaryParams);
        mSecondaryView.setLayoutParams(secondaryParams);
        mSplitDivider.setLayoutParams(dividerParams);
        mSplitDivider.setVisibility(View.VISIBLE);
        mSecondaryView.setVisibility(View.VISIBLE);
        mIsSplitActive = true;
    }

    private void hideSecondary() {
        if (!mIsSplitActive) return;
        mSplitDivider.setVisibility(View.GONE);
        mSecondaryView.setVisibility(View.GONE);
        mIsSplitActive = false;
        mActiveView = mPrimaryView;
    }

    /** Redraw every visible pane that shows {@code session}. */
    public void onTextChanged(@NonNull TerminalSession session) {
        if (mPrimaryView.getCurrentSession() == session) mPrimaryView.onScreenUpdated();
        if (mIsSplitActive && mSecondaryView.getCurrentSession() == session) mSecondaryView.onScreenUpdated();
    }

    /** Apply the global font size to both panes. */
    public void setTextSize(int textSize) {
        mPrimaryView.setTextSize(textSize);
        if (mSecondaryView != null) mSecondaryView.setTextSize(textSize);
    }

    /** Apply the terminal typeface to both panes. */
    public void setTypeface(Typeface typeface) {
        mPrimaryView.setTypeface(typeface);
        if (mSecondaryView != null) mSecondaryView.setTypeface(typeface);
    }

    public boolean isSplitActive() {
        return mIsSplitActive;
    }

    public TerminalView getSecondaryView() {
        return mSecondaryView;
    }
}
