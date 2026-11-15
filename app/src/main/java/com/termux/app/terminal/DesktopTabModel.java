package com.termux.app.terminal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure model of desktop tabs: a tab is one session, optionally split with one partner session
 * shown in the second pane. Partner sessions are not tabs of their own.
 *
 * Generic over the session type so it can be unit tested without Android.
 * Not thread safe; use from the main thread only.
 *
 * @param <S> session type
 */
public final class DesktopTabModel<S> {

    /** Tab order; may hold stale sessions, which {@link #tabs} drops. */
    private final List<S> mTabOrder = new ArrayList<>();
    /** tab session -> split partner session */
    private final Map<S, S> mPartnerOf = new HashMap<>();
    /** tab session -> split is top/bottom (true) or side by side (false) */
    private final Map<S, Boolean> mVertical = new HashMap<>();

    /**
     * Tabs for the current session list: known tabs in their order, then any new session that is
     * neither a tab nor a partner, in list order. Forgets tabs/partners no longer in the list.
     */
    public List<S> tabs(List<S> sessions) {
        mTabOrder.retainAll(sessions);
        mPartnerOf.keySet().retainAll(sessions);
        mPartnerOf.values().retainAll(sessions);
        mVertical.keySet().retainAll(mPartnerOf.keySet());
        for (S s : sessions) {
            if (!mTabOrder.contains(s) && !mPartnerOf.containsValue(s)) mTabOrder.add(s);
        }
        return new ArrayList<>(mTabOrder);
    }

    /** The tab a session belongs to: its own tab, or the tab it is the split partner of. */
    public S tabOf(S session) {
        for (Map.Entry<S, S> e : mPartnerOf.entrySet()) {
            if (e.getValue() == session) return e.getKey();
        }
        return session;
    }

    /** Split partner of a tab, or null if the tab is not split. */
    public S partnerOf(S tab) {
        return mPartnerOf.get(tab);
    }

    public boolean isVertical(S tab) {
        Boolean v = mVertical.get(tab);
        return v != null && v;
    }

    /** Split {@code tab} with the new session {@code partner}, which leaves the tab list. */
    public void split(S tab, S partner, boolean vertical) {
        if (tab == partner) throw new IllegalArgumentException("a tab cannot be split with itself");
        if (mPartnerOf.containsKey(tab)) throw new IllegalStateException("tab is already split");
        mTabOrder.remove(partner);
        mPartnerOf.put(tab, partner);
        mVertical.put(tab, vertical);
    }

    public void setVertical(S tab, boolean vertical) {
        if (mPartnerOf.containsKey(tab)) mVertical.put(tab, vertical);
    }

    /**
     * Forget a closed session. A split partner just leaves its tab. A split tab hands its place in
     * the tab order to its partner. A plain tab leaves the order.
     *
     * @return the tab the session belonged to, or the promoted partner if that tab itself closed;
     *     null if the closed session was a plain tab (the tab is gone).
     */
    public S remove(S session) {
        for (Map.Entry<S, S> e : mPartnerOf.entrySet()) {
            if (e.getValue() == session) {
                S tab = e.getKey();
                mPartnerOf.remove(tab);
                mVertical.remove(tab);
                return tab;
            }
        }
        S partner = mPartnerOf.remove(session);
        mVertical.remove(session);
        int index = mTabOrder.indexOf(session);
        if (partner != null) {
            if (index >= 0) mTabOrder.set(index, partner);
            else mTabOrder.add(partner);
            return partner;
        }
        if (index >= 0) mTabOrder.remove(index);
        return null;
    }

    /** Tab next to {@code current} in {@code tabs}, wrapping around; null if {@code tabs} is empty. */
    public static <S> S neighbour(List<S> tabs, S current, boolean forward) {
        if (tabs.isEmpty()) return null;
        int i = tabs.indexOf(current);
        if (i < 0) return tabs.get(0);
        int n = tabs.size();
        return tabs.get(((forward ? i + 1 : i - 1) % n + n) % n);
    }

    /** Tab to show after the tab at {@code removedIndex} closed: the one now at that index, else the last. */
    public static <S> S afterClose(List<S> tabsAfter, int removedIndex) {
        if (tabsAfter.isEmpty()) return null;
        return tabsAfter.get(Math.max(0, Math.min(removedIndex, tabsAfter.size() - 1)));
    }
}
