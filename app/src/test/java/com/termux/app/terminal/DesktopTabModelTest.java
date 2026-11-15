package com.termux.app.terminal;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class DesktopTabModelTest {

    private DesktopTabModel<String> model;
    private List<String> sessions;

    @Before
    public void setUp() {
        model = new DesktopTabModel<>();
        sessions = new ArrayList<>(Arrays.asList("a", "b"));
    }

    private List<String> list(String... s) {
        return Arrays.asList(s);
    }

    @Test
    public void everySessionIsATabUntilSplit() {
        assertEquals(list("a", "b"), model.tabs(sessions));
        assertEquals("a", model.tabOf("a"));
        assertNull(model.partnerOf("a"));
    }

    @Test
    public void splitPartnerIsNotATab() {
        model.tabs(sessions);
        sessions.add("c");
        model.split("a", "c", false);
        assertEquals(list("a", "b"), model.tabs(sessions));
        assertEquals("c", model.partnerOf("a"));
        assertEquals("a", model.tabOf("c"));
        assertFalse(model.isVertical("a"));
    }

    @Test
    public void newSessionsAppendAfterKnownTabs() {
        model.tabs(sessions);
        sessions.add(0, "z"); // service list order changes must not reorder known tabs
        assertEquals(list("a", "b", "z"), model.tabs(sessions));
    }

    @Test
    public void orientationCanChangeOnlyWhileSplit() {
        model.setVertical("a", true);
        assertFalse(model.isVertical("a"));
        model.split("a", "c", false);
        model.setVertical("a", true);
        assertTrue(model.isVertical("a"));
    }

    @Test
    public void closingPartnerKeepsTab() {
        model.tabs(sessions);
        sessions.add("c");
        model.split("a", "c", true);
        assertEquals("a", model.remove("c"));
        sessions.remove("c");
        assertNull(model.partnerOf("a"));
        assertFalse(model.isVertical("a"));
        assertEquals(list("a", "b"), model.tabs(sessions));
    }

    @Test
    public void closingSplitTabPromotesPartnerInPlace() {
        model.tabs(sessions);
        sessions.add("c");
        model.split("a", "c", false);
        assertEquals("c", model.remove("a"));
        sessions.remove("a");
        assertEquals(list("c", "b"), model.tabs(sessions));
        assertEquals("c", model.tabOf("c"));
        assertNull(model.partnerOf("c"));
    }

    @Test
    public void closingPlainTabRemovesIt() {
        model.tabs(sessions);
        assertNull(model.remove("b"));
        sessions.remove("b");
        assertEquals(list("a"), model.tabs(sessions));
    }

    @Test
    public void removingUnknownSessionIsHarmless() {
        assertNull(model.remove("x"));
        assertEquals(list("a", "b"), model.tabs(sessions));
    }

    @Test
    public void promotedPartnerOfUnlistedTabIsAppended() {
        // split before tabs() ever ran: "a" is not in the order yet
        model.split("a", "c", false);
        assertEquals("c", model.remove("a"));
        assertEquals(list("c", "b"), model.tabs(list("b", "c")));
    }

    @Test
    public void staleEntriesAreDroppedWhenSessionsDisappear() {
        model.tabs(sessions);
        sessions.add("c");
        model.split("a", "c", false);
        // both sessions of the split tab exited without remove() being called
        assertEquals(list("b"), model.tabs(list("b")));
        assertNull(model.partnerOf("a"));
        assertEquals("c", model.tabOf("c"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void cannotSplitWithItself() {
        model.split("a", "a", false);
    }

    @Test(expected = IllegalStateException.class)
    public void cannotSplitTwice() {
        model.split("a", "c", false);
        model.split("a", "d", false);
    }

    @Test
    public void neighbourWrapsBothWays() {
        List<String> tabs = list("a", "b", "c");
        assertEquals("b", DesktopTabModel.neighbour(tabs, "a", true));
        assertEquals("a", DesktopTabModel.neighbour(tabs, "c", true));
        assertEquals("c", DesktopTabModel.neighbour(tabs, "a", false));
        assertEquals("a", DesktopTabModel.neighbour(tabs, "x", true));
        assertNull(DesktopTabModel.neighbour(Collections.<String>emptyList(), "a", true));
    }

    @Test
    public void afterCloseKeepsIndexOrTakesLast() {
        List<String> tabs = list("a", "b");
        assertSame("b", DesktopTabModel.afterClose(tabs, 1));
        assertSame("b", DesktopTabModel.afterClose(tabs, 5));
        assertSame("a", DesktopTabModel.afterClose(tabs, -1));
        assertNull(DesktopTabModel.afterClose(Collections.<String>emptyList(), 0));
    }
}
