# Termux Desktop / Samsung DeX Fork — Step-by-Step Implementation Plan for Small LLMs

> **Target Repository**: `termux/termux-app` (`v0.118.0`, commit `8629e632fcb95da272221be327db653fb24befe9`)
> **Goal**: Transform the mobile Termux UI into a native desktop-style (keyboard + mouse) terminal optimized for Samsung DeX and Android Desktop Mode while preserving 100% compatibility with upstream Termux bootstrap packages.

---

## 0. Strict Guardrails for LLM Coding Agents (READ FIRST)

Before editing any file, every LLM agent executing this plan **MUST** obey these five architectural rules:

1. **NEVER Change `applicationId` or `TERMUX_PACKAGE_NAME`**:
   - In `app/build.gradle` (line 54) and `termux-shared/.../TermuxConstants.java` (line 294), the package name **must remain `"com.termux"`**.
   - Upstream Termux bootstrap ELF binaries compile `/data/data/com.termux/files/usr` (`TERMUX_PREFIX_DIR_PATH`) into `DT_RUNPATH` and hardcoded shebangs. Changing `applicationId` breaks `pkg` and `apt` unless you recompile the entire `termux-packages` repository.
2. **NEVER Modify `:terminal-emulator` C/JNI or VT100 Core**:
   - `terminal-emulator/src/main/jni/termux.c`, `TerminalSession.java`, `TerminalEmulator.java`, and `TerminalBuffer.java` already implement `TIOCGWINSZ` window resizing (`SIGWINCH`), SGR/X10 mouse tracking (`DECSET 1000/1002/1006`), bracketed paste (`DECSET 2004`), and UTF-8 transcript extraction (`getSelectedText`). Leave `:terminal-emulator` untouched.
3. **Modify Only `:terminal-view` and `:app`**:
   - All desktop mouse/keyboard/windowing upgrades happen inside `:terminal-view` (`TerminalView.java`, `TextSelectionCursorController.java`) and `:app` (`TermuxTerminalViewClient.java`, `TermuxActivity.java`, `DesktopTabController.java`, `DesktopSplitPaneManager.java`, `activity_termux.xml`, `AndroidManifest.xml`).
4. **Execute One Step at a Time and Compile-Check After Each Step**:
   - Run the verification Gradle command listed at the end of each step before moving to the next step.
5. **Preserve Touch Fallbacks**:
   - Gate desktop mouse behaviors on `event.isFromSource(InputDevice.SOURCE_MOUSE)` (or `(event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE`) so touchscreen usage on a phone/tablet still works when undocked from Samsung DeX.

---

## Architecture Overview: File Modification Map

| Step | File Path (relative to repo root) | Action | Purpose |
| :--- | :--- | :--- | :--- |
| **Step 1A** | `terminal-view/src/main/java/com/termux/view/textselection/TextSelectionCursorController.java` | **Modify** | Add handle-free mouse drag selection (`mIsMouseSelection`), double-click word selection, and triple-click line selection without opening the mobile floating `ActionMode` or teardrop handles. |
| **Step 1B** | `terminal-view/src/main/java/com/termux/view/TerminalView.java` | **Modify** | Add LMB drag selection (`BUTTON_PRIMARY`), `Shift+Mouse` override when TUI mouse tracking is active, `Ctrl+Scroll` font zoom, and `PointerIcon.TYPE_TEXT` (I-beam) cursor. |
| **Step 2** | `app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java` | **Modify** | Add `Ctrl+Shift+C/V/T/W/N`, `Ctrl+Tab` / `Ctrl+Shift+Tab`, `Ctrl+Plus/Minus/0` zoom, and `Ctrl+Click` URL opening from hardware mouse. |
| **Step 3A** | `app/src/main/res/layout/item_desktop_tab.xml` | **Create** | Layout for individual desktop tabs (session title, active indicator bar, close `×` button). |
| **Step 3B** | `app/src/main/res/layout/activity_termux.xml` | **Modify** | Add top desktop tab bar (`36dp`), new-tab/split-pane action buttons, and a split-pane container wrapping `TerminalView`. |
| **Step 4A** | `app/src/main/java/com/termux/app/terminal/DesktopTabController.java` | **Create** | Manages the top horizontal tab strip, tab switching, middle-click tab close, and double-click tab rename. |
| **Step 4B** | `app/src/main/java/com/termux/app/terminal/DesktopSplitPaneManager.java` | **Create** | Manages side-by-side (horizontal) or stacked (vertical) dual `TerminalView` panes within a single window. |
| **Step 5** | `app/src/main/java/com/termux/app/TermuxActivity.java` | **Modify** | Wire `DesktopTabController` and `DesktopSplitPaneManager`, add Copy/Paste/Split to right-click `ContextMenu`, and auto-hide `ExtraKeysView` when a physical keyboard or DeX mode is active. |
| **Step 6** | `app/src/main/AndroidManifest.xml` | **Modify** | Add Samsung DeX default window dimensions (`960dp x 640dp`), `stateAlwaysHidden` soft-input policy, and multi-window support. |

---

## Step 1A: Add Handle-Free Desktop Mouse Selection in `TextSelectionCursorController.java`

### Why
Currently, `TextSelectionCursorController.show(MotionEvent)` always positions two mobile teardrop handles (`mStartHandle`, `mEndHandle`) and launches a mobile floating `ActionMode` toolbar (`onCreateActionMode`). On a desktop with a mouse, left-clicking and dragging should highlight text directly in `TerminalRenderer.render()` (which already reads `getSelectors(sel)`) without showing mobile drag teardrops or popups until the user right-clicks or presses `Ctrl+Shift+C`.

### Target File
`terminal-view/src/main/java/com/termux/view/textselection/TextSelectionCursorController.java`

### Instructions for LLM Agent
1. Open `terminal-view/src/main/java/com/termux/view/textselection/TextSelectionCursorController.java`.
2. Locate the instance fields near lines 25–35:
```java
    private String mStoredSelectedText;
    private boolean mIsSelectingText = false;
    private long mShowStartTime = System.currentTimeMillis();

    private final int mHandleHeight;
    private int mSelX1 = -1, mSelX2 = -1, mSelY1 = -1, mSelY2 = -1;
```
3. Replace those lines with the following expanded fields (adding mouse anchor tracking and click-count state):
```java
    private String mStoredSelectedText;
    private boolean mIsSelectingText = false;
    private boolean mIsMouseSelection = false;
    private long mShowStartTime = System.currentTimeMillis();

    private final int mHandleHeight;
    private int mSelX1 = -1, mSelX2 = -1, mSelY1 = -1, mSelY2 = -1;
    private int mAnchorX = -1, mAnchorY = -1;
    private long mLastMouseDownTime = 0;
    private int mMouseClickCount = 0;
    private int mLastClickX = -1, mLastClickY = -1;
```
4. Locate `hide()` and `render()` around lines 60–95:
```java
        mStartHandle.hide();
        mEndHandle.hide();

        if (mActionMode != null) {
            // This will hide the TextSelectionCursorController
            mActionMode.finish();
        }

        mSelX1 = mSelY1 = mSelX2 = mSelY2 = -1;
        mIsSelectingText = false;

        return true;
    }

    @Override
    public void render() {
        if (!isActive()) return;

        mStartHandle.positionAtCursor(mSelX1, mSelY1, false);
        mEndHandle.positionAtCursor(mSelX2 + 1, mSelY2, false);

        if (mActionMode != null) {
            mActionMode.invalidate();
        }
    }
```
5. Replace that section in `hide()` and `render()` with:
```java
        mStartHandle.hide();
        mEndHandle.hide();

        if (mActionMode != null) {
            // This will hide the TextSelectionCursorController
            mActionMode.finish();
        }

        mSelX1 = mSelY1 = mSelX2 = mSelY2 = -1;
        mAnchorX = mAnchorY = -1;
        mIsSelectingText = false;
        mIsMouseSelection = false;

        return true;
    }

    @Override
    public void render() {
        if (!isActive()) return;

        if (!mIsMouseSelection) {
            mStartHandle.positionAtCursor(mSelX1, mSelY1, false);
            mEndHandle.positionAtCursor(mSelX2 + 1, mSelY2, false);

            if (mActionMode != null) {
                mActionMode.invalidate();
            }
        }
    }
```
6. Also in `hide()` (around line 55), bypass the 300ms debounce when `mIsMouseSelection` is true by changing:
```java
        if (System.currentTimeMillis() - mShowStartTime < 300) {
            return false;
        }
```
to:
```java
        if (!mIsMouseSelection && System.currentTimeMillis() - mShowStartTime < 300) {
            return false;
        }
```
7. At the bottom of `TextSelectionCursorController.java` (just before the closing `}` of the class), add these desktop mouse selection methods:
```java
    /**
     * Start a desktop mouse selection on primary button press (LMB down).
     * Supports single-click (character anchor), double-click (word), and triple-click (full row).
     */
    public void startMouseSelection(MotionEvent event) {
        if (terminalView.mEmulator == null) return;
        int[] columnAndRow = terminalView.getColumnAndRow(event, true);
        int col = Math.max(0, Math.min(columnAndRow[0], terminalView.mEmulator.mColumns - 1));
        int row = columnAndRow[1];

        long now = event.getEventTime();
        if (now - mLastMouseDownTime <= 400 && col == mLastClickX && row == mLastClickY) {
            mMouseClickCount = (mMouseClickCount % 3) + 1;
        } else {
            mMouseClickCount = 1;
        }
        mLastMouseDownTime = now;
        mLastClickX = col;
        mLastClickY = row;

        mStartHandle.hide();
        mEndHandle.hide();
        if (mActionMode != null) {
            mActionMode.finish();
            mActionMode = null;
        }

        mAnchorX = col;
        mAnchorY = row;
        mIsMouseSelection = true;

        if (mMouseClickCount == 1) {
            // Single click clears active selection until the user drags
            mSelX1 = mSelY1 = mSelX2 = mSelY2 = -1;
            mIsSelectingText = false;
        } else if (mMouseClickCount == 2) {
            // Double click: select word at location
            setInitialTextSelectionPosition(event);
            mIsSelectingText = true;
        } else if (mMouseClickCount == 3) {
            // Triple click: select entire line
            mSelX1 = 0;
            mSelX2 = terminalView.mEmulator.mColumns - 1;
            mSelY1 = mSelY2 = row;
            mIsSelectingText = true;
        }
        terminalView.invalidate();
    }

    /**
     * Update selection range as the mouse drags with primary button held.
     */
    public void updateMouseSelection(MotionEvent event) {
        if (terminalView.mEmulator == null || mAnchorY == -1) return;

        TerminalBuffer screen = terminalView.mEmulator.getScreen();
        final int scrollRows = screen.getActiveRows() - terminalView.mEmulator.mRows;

        // Auto-scroll transcript if dragging above or below viewport on primary screen
        if (!terminalView.mEmulator.isAlternateBufferActive()) {
            int topRow = terminalView.getTopRow();
            if (event.getY() < 0 && topRow > -scrollRows) {
                terminalView.setTopRow(Math.max(-scrollRows, topRow - 1));
            } else if (event.getY() > terminalView.getHeight() && topRow < 0) {
                terminalView.setTopRow(Math.min(0, topRow + 1));
            }
        }

        int[] columnAndRow = terminalView.getColumnAndRow(event, true);
        int curX = Math.max(0, Math.min(columnAndRow[0], terminalView.mEmulator.mColumns - 1));
        int curY = Math.max(-scrollRows, Math.min(columnAndRow[1], terminalView.mEmulator.mRows - 1));

        if (curX == mAnchorX && curY == mAnchorY && mMouseClickCount == 1) {
            return;
        }

        mIsMouseSelection = true;
        mIsSelectingText = true;

        if (curY < mAnchorY || (curY == mAnchorY && curX <= mAnchorX)) {
            mSelX1 = getValidCurX(screen, curY, curX);
            mSelY1 = curY;
            mSelX2 = getValidCurX(screen, mAnchorY, mAnchorX);
            mSelY2 = mAnchorY;
        } else {
            mSelX1 = getValidCurX(screen, mAnchorY, mAnchorX);
            mSelY1 = mAnchorY;
            mSelX2 = getValidCurX(screen, curY, curX);
            mSelY2 = curY;
        }

        terminalView.invalidate();
    }

    /**
     * Store the currently selected text so a right-click context menu can access it.
     */
    public void cacheSelectedTextForContextMenu() {
        if (mIsSelectingText) {
            mStoredSelectedText = getSelectedText();
        }
    }
```

---

## Step 1B: Update `TerminalView.java` for Mouse Drag Selection, `Ctrl+Scroll` Zoom, and I-Beam Pointer

### Target File
`terminal-view/src/main/java/com/termux/view/TerminalView.java`

### Instructions for LLM Agent
1. Open `terminal-view/src/main/java/com/termux/view/TerminalView.java`.
2. Add the `PointerIcon` import near line 30 (with the other `android.view.*` imports):
```java
import android.view.PointerIcon;
```
3. Locate `onGenericMotionEvent(MotionEvent event)` at lines 593–602:
```java
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (mEmulator != null && event.isFromSource(InputDevice.SOURCE_MOUSE) && event.getAction() == MotionEvent.ACTION_SCROLL) {
            // Handle mouse wheel scrolling.
            boolean up = event.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0.0f;
            doScroll(event, up ? -3 : 3);
            return true;
        }
        return false;
    }
```
4. Replace `onGenericMotionEvent(MotionEvent event)` with the following version (which adds `Ctrl + Mouse Wheel` font zooming):
```java
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (mEmulator != null && event.isFromSource(InputDevice.SOURCE_MOUSE) && event.getAction() == MotionEvent.ACTION_SCROLL) {
            boolean up = event.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0.0f;
            if ((event.getMetaState() & KeyEvent.META_CTRL_ON) != 0) {
                // Desktop Ctrl + Scroll Wheel zooms terminal font size
                mScaleFactor = mClient.onScale(up ? 1.2f : 0.8f);
                return true;
            }
            // Handle mouse wheel scrolling.
            doScroll(event, up ? -3 : 3);
            return true;
        }
        return false;
    }
```
5. Locate `onTouchEvent(MotionEvent event)` at lines 605–644:
```java
    @SuppressLint("ClickableViewAccessibility")
    @Override
    @TargetApi(23)
    public boolean onTouchEvent(MotionEvent event) {
        if (mEmulator == null) return true;
        final int action = event.getAction();

        if (isSelectingText()) {
            updateFloatingToolbarVisibility(event);
            mGestureRecognizer.onTouchEvent(event);
            return true;
        } else if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (event.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
                if (action == MotionEvent.ACTION_DOWN) showContextMenu();
                return true;
            } else if (event.isButtonPressed(MotionEvent.BUTTON_TERTIARY)) {
                ClipboardManager clipboardManager = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clipData = clipboardManager.getPrimaryClip();
                if (clipData != null) {
                    ClipData.Item clipItem = clipData.getItemAt(0);
                    if (clipItem != null) {
                        CharSequence text = clipItem.coerceToText(getContext());
                        if (!TextUtils.isEmpty(text)) mEmulator.paste(text.toString());
                    }
                }
            } else if (mEmulator.isMouseTrackingActive()) { // BUTTON_PRIMARY.
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_UP:
                        sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, event.getAction() == MotionEvent.ACTION_DOWN);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, true);
                        break;
                }
            }
        }

        mGestureRecognizer.onTouchEvent(event);
        return true;
    }
```
6. Replace `onTouchEvent(MotionEvent event)` and add `onResolvePointerIcon(...)` immediately below it as follows (notice how `Shift + Left-Click Drag` forces local text selection even inside `tmux`/`nvim` when `mEmulator.isMouseTrackingActive()` is true, matching desktop terminals like Kitty/Alacritty/GNOME Terminal):
```java
    @SuppressLint("ClickableViewAccessibility")
    @Override
    @TargetApi(23)
    public boolean onTouchEvent(MotionEvent event) {
        if (mEmulator == null) return true;
        final int action = event.getActionMasked();

        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            requestFocus();

            if (event.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
                if (action == MotionEvent.ACTION_DOWN) {
                    if (mTextSelectionCursorController != null && isSelectingText()) {
                        mTextSelectionCursorController.cacheSelectedTextForContextMenu();
                    }
                    showContextMenu();
                }
                return true;
            } else if (event.isButtonPressed(MotionEvent.BUTTON_TERTIARY)) {
                if (action == MotionEvent.ACTION_DOWN) {
                    ClipboardManager clipboardManager = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clipData = clipboardManager != null ? clipboardManager.getPrimaryClip() : null;
                    if (clipData != null && clipData.getItemCount() > 0) {
                        CharSequence text = clipData.getItemAt(0).coerceToText(getContext());
                        if (!TextUtils.isEmpty(text)) mEmulator.paste(text.toString());
                    }
                }
                return true;
            }

            boolean shiftOverride = (event.getMetaState() & KeyEvent.META_SHIFT_ON) != 0;
            if (mEmulator.isMouseTrackingActive() && !shiftOverride) {
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_UP:
                        sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, action == MotionEvent.ACTION_DOWN);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if ((event.getButtonState() & MotionEvent.BUTTON_PRIMARY) != 0) {
                            sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, true);
                        }
                        break;
                }
                return true;
            } else {
                // Desktop LMB click & drag text selection
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        if ((event.getButtonState() & MotionEvent.BUTTON_PRIMARY) != 0) {
                            if ((event.getMetaState() & KeyEvent.META_CTRL_ON) != 0) {
                                mClient.onSingleTapUp(event);
                                return true;
                            }
                            getTextSelectionCursorController().startMouseSelection(event);
                            mClient.copyModeChanged(isSelectingText());
                            return true;
                        }
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if ((event.getButtonState() & MotionEvent.BUTTON_PRIMARY) != 0) {
                            getTextSelectionCursorController().updateMouseSelection(event);
                            mClient.copyModeChanged(isSelectingText());
                            return true;
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        mClient.copyModeChanged(isSelectingText());
                        return true;
                }
            }
        }

        if (isSelectingText()) {
            updateFloatingToolbarVisibility(event);
            mGestureRecognizer.onTouchEvent(event);
            return true;
        }

        mGestureRecognizer.onTouchEvent(event);
        return true;
    }

    @Override
    @TargetApi(24)
    public PointerIcon onResolvePointerIcon(MotionEvent event, int pointerIndex) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && mEmulator != null) {
            if ((event.getMetaState() & KeyEvent.META_CTRL_ON) != 0) {
                return PointerIcon.getSystemIcon(getContext(), PointerIcon.TYPE_HAND);
            }
            return PointerIcon.getSystemIcon(getContext(), PointerIcon.TYPE_TEXT);
        }
        return super.onResolvePointerIcon(event, pointerIndex);
    }
```

### Step 1 Verification Command
Run via Expose Files MCP `run_command`:
```cmd
gradlew.bat :terminal-view:compileDebugJavaWithJavac
```

---

## Step 2: Add Desktop Keyboard Shortcuts (`Ctrl+Shift+*`, `Ctrl+Tab`, `Ctrl+Click`) in `TermuxTerminalViewClient.java`

### Why
Standard desktop terminals use `Ctrl+Shift+C` (Copy), `Ctrl+Shift+V` (Paste), `Ctrl+Shift+T` (New Tab), `Ctrl+Shift+W` (Close Tab), `Ctrl+Shift+N` (New Window), `Ctrl+Tab` / `Ctrl+Shift+Tab` (Switch Tabs), and `Ctrl+Click` to open URLs. Currently, `TermuxTerminalViewClient.java` only listens for `Ctrl+Alt+*` shortcuts and blocks `InputDevice.SOURCE_MOUSE` clicks from opening URLs unless `shouldOpenTerminalTranscriptURLOnClick()` is manually enabled in properties.

### Target File
`app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java`

### Instructions for LLM Agent
1. Open `app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java`.
2. Locate `onSingleTapUp(MotionEvent e)` around lines 186–207:
```java
    @Override
    public void onSingleTapUp(MotionEvent e) {
        TerminalEmulator term = mActivity.getCurrentSession().getEmulator();

        if (mActivity.getProperties().shouldOpenTerminalTranscriptURLOnClick()) {
            int[] columnAndRow = mActivity.getTerminalView().getColumnAndRow(e, true);
            String wordAtTap = term.getScreen().getWordAtLocation(columnAndRow[0], columnAndRow[1]);
            LinkedHashSet<CharSequence> urlSet = TermuxUrlUtils.extractUrls(wordAtTap);

            if (!urlSet.isEmpty()) {
                String url = (String) urlSet.iterator().next();
                ShareUtils.openUrl(mActivity, url);
                return;
            }
        }
```
3. Replace that `if` condition so `Ctrl + Mouse Click` always opens URLs on desktop even if single-tap URL opening is disabled for touch:
```java
    @Override
    public void onSingleTapUp(MotionEvent e) {
        TerminalEmulator term = mActivity.getCurrentSession().getEmulator();

        boolean isCtrlMouseClick = e.isFromSource(InputDevice.SOURCE_MOUSE) && (e.getMetaState() & KeyEvent.META_CTRL_ON) != 0;
        if (isCtrlMouseClick || mActivity.getProperties().shouldOpenTerminalTranscriptURLOnClick()) {
            int[] columnAndRow = mActivity.getTerminalView().getColumnAndRow(e, true);
            String wordAtTap = term.getScreen().getWordAtLocation(columnAndRow[0], columnAndRow[1]);
            LinkedHashSet<CharSequence> urlSet = TermuxUrlUtils.extractUrls(wordAtTap);

            if (!urlSet.isEmpty()) {
                String url = (String) urlSet.iterator().next();
                ShareUtils.openUrl(mActivity, url);
                return;
            }
        }
```
4. Locate `onKeyDown(int keyCode, KeyEvent e, TerminalSession currentSession)` around lines 241–287:
```java
        if (keyCode == KeyEvent.KEYCODE_ENTER && !currentSession.isRunning()) {
            mTermuxTerminalSessionActivityClient.removeFinishedSession(currentSession);
            return true;
        } else if (!mActivity.getProperties().areHardwareKeyboardShortcutsDisabled() &&
            e.isCtrlPressed() && e.isAltPressed()) {
```
5. Insert the desktop `Ctrl+Tab` and `Ctrl+Shift+*` shortcut handler block right before the `e.isCtrlPressed() && e.isAltPressed()` check:
```java
        if (keyCode == KeyEvent.KEYCODE_ENTER && !currentSession.isRunning()) {
            mTermuxTerminalSessionActivityClient.removeFinishedSession(currentSession);
            return true;
        } else if (!mActivity.getProperties().areHardwareKeyboardShortcutsDisabled() &&
            e.isCtrlPressed() && keyCode == KeyEvent.KEYCODE_TAB) {
            // Desktop Ctrl+Tab / Ctrl+Shift+Tab switches tabs
            mTermuxTerminalSessionActivityClient.switchToSession(!e.isShiftPressed());
            return true;
        } else if (!mActivity.getProperties().areHardwareKeyboardShortcutsDisabled() &&
            e.isCtrlPressed() && e.isShiftPressed()) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_C: {
                    String selectedText = mActivity.getTerminalView().getSelectedText();
                    if (! DataUtils.isNullOrEmpty(selectedText)) {
                        currentSession.onCopyTextToClipboard(selectedText);
                        mActivity.getTerminalView().stopTextSelectionMode();
                    }
                    return true;
                }
                case KeyEvent.KEYCODE_V:
                    doPaste();
                    return true;
                case KeyEvent.KEYCODE_T:
                    mTermuxTerminalSessionActivityClient.addNewSession(false, null);
                    return true;
                case KeyEvent.KEYCODE_W:
                    if (currentSession != null) {
                        if (currentSession.isRunning()) {
                            currentSession.finishIfRunning();
                        }
                        mTermuxTerminalSessionActivityClient.removeFinishedSession(currentSession);
                    }
                    return true;
                case KeyEvent.KEYCODE_R:
                    mTermuxTerminalSessionActivityClient.renameSession(currentSession);
                    return true;
                case KeyEvent.KEYCODE_D:
                    mActivity.toggleDesktopSplitPane(false); // Horizontal split (side-by-side)
                    return true;
                case KeyEvent.KEYCODE_E:
                    mActivity.toggleDesktopSplitPane(true);  // Vertical split (top/bottom)
                    return true;
                case KeyEvent.KEYCODE_PLUS:
                case KeyEvent.KEYCODE_EQUALS:
                    changeFontSize(true);
                    return true;
                case KeyEvent.KEYCODE_MINUS:
                    changeFontSize(false);
                    return true;
            }
        } else if (!mActivity.getProperties().areHardwareKeyboardShortcutsDisabled() &&
            e.isCtrlPressed() && e.isAltPressed()) {
```

---

## Step 3: Add Desktop Top Tab Bar & Split-Pane Container Layouts

### Step 3A: Create `app/src/main/res/layout/item_desktop_tab.xml`
Use Expose Files MCP `write_file` to create `app/src/main/res/layout/item_desktop_tab.xml` with this exact content:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/desktop_tab_item_root"
    android:layout_width="wrap_content"
    android:layout_height="match_parent"
    android:minWidth="120dp"
    android:maxWidth="220dp"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:paddingStart="10dp"
    android:paddingEnd="6dp"
    android:layout_marginEnd="2dp"
    android:background="#1A2639">

    <View
        android:id="@+id/desktop_tab_active_indicator"
        android:layout_width="4dp"
        android:layout_height="16dp"
        android:layout_marginEnd="8dp"
        android:background="#E6C786"
        android:visibility="invisible" />

    <TextView
        android:id="@+id/desktop_tab_title"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:singleLine="true"
        android:ellipsize="end"
        android:textColor="#FFFFFF"
        android:textSize="12sp"
        android:fontFamily="monospace" />

    <TextView
        android:id="@+id/desktop_tab_close"
        android:layout_width="22dp"
        android:layout_height="22dp"
        android:layout_marginStart="6dp"
        android:gravity="center"
        android:text="×"
        android:textColor="#B0BEC5"
        android:textSize="14sp"
        android:background="?android:attr/selectableItemBackgroundBorderless"
        android:contentDescription="Close session" />

</LinearLayout>
```

### Step 3B: Update `app/src/main/res/layout/activity_termux.xml`
Replace `app/src/main/res/layout/activity_termux.xml` with the following layout, which preserves every existing view ID (`activity_termux_root_view`, `drawer_layout`, `terminal_view`, `left_drawer`, `terminal_sessions_list`, `settings_button`, `toggle_keyboard_button`, `new_session_button`, `terminal_toolbar_view_pager`) while adding the top desktop tab bar (`@+id/desktop_top_bar`) and dual-pane split container (`@+id/desktop_split_container` + `@+id/terminal_view_secondary`):

```xml
<com.termux.app.terminal.TermuxActivityRootView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/activity_termux_root_view"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:fitsSystemWindows="true">

    <!-- Desktop Top Tab Bar (Always visible for mouse/DeX workflow) -->
    <LinearLayout
        android:id="@+id/desktop_top_bar"
        android:layout_width="match_parent"
        android:layout_height="36dp"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:background="#12264A"
        android:paddingStart="4dp"
        android:paddingEnd="4dp">

        <HorizontalScrollView
            android:id="@+id/desktop_tab_scroll"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:scrollbars="none"
            android:fillViewport="false">

            <LinearLayout
                android:id="@+id/desktop_tab_strip"
                android:layout_width="wrap_content"
                android:layout_height="match_parent"
                android:orientation="horizontal"
                android:gravity="center_vertical" />
        </HorizontalScrollView>

        <TextView
            android:id="@+id/desktop_new_tab_button"
            android:layout_width="32dp"
            android:layout_height="32dp"
            android:gravity="center"
            android:text="+"
            android:textColor="#E6C786"
            android:textSize="18sp"
            android:background="?android:attr/selectableItemBackgroundBorderless"
            android:contentDescription="@string/action_new_session" />

        <TextView
            android:id="@+id/desktop_split_horizontal_button"
            android:layout_width="32dp"
            android:layout_height="32dp"
            android:gravity="center"
            android:text="◫"
            android:textColor="#FFFFFF"
            android:textSize="14sp"
            android:background="?android:attr/selectableItemBackgroundBorderless"
            android:contentDescription="Split Horizontally (Ctrl+Shift+D)" />

        <TextView
            android:id="@+id/desktop_split_vertical_button"
            android:layout_width="32dp"
            android:layout_height="32dp"
            android:gravity="center"
            android:text="⬒"
            android:textColor="#FFFFFF"
            android:textSize="14sp"
            android:background="?android:attr/selectableItemBackgroundBorderless"
            android:contentDescription="Split Vertically (Ctrl+Shift+E)" />

        <ImageButton
            android:id="@+id/desktop_settings_button"
            android:layout_width="32dp"
            android:layout_height="32dp"
            android:src="@drawable/ic_settings"
            android:background="?android:attr/selectableItemBackgroundBorderless"
            android:contentDescription="@string/action_open_settings"
            app:tint="#FFFFFF" />
    </LinearLayout>

    <RelativeLayout
        android:id="@+id/activity_termux_root_relative_layout"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:layout_marginHorizontal="3dp"
        android:layout_marginVertical="0dp"
        android:orientation="vertical">

        <androidx.drawerlayout.widget.DrawerLayout
            android:id="@+id/drawer_layout"
            android:layout_width="match_parent"
            android:layout_alignParentTop="true"
            android:layout_above="@+id/terminal_toolbar_view_pager"
            android:layout_height="match_parent">

            <LinearLayout
                android:id="@+id/desktop_split_container"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:orientation="horizontal"
                android:baselineAligned="false">

                <com.termux.view.TerminalView
                    android:id="@+id/terminal_view"
                    android:layout_width="0dp"
                    android:layout_height="match_parent"
                    android:layout_weight="1"
                    android:defaultFocusHighlightEnabled="false"
                    android:focusableInTouchMode="true"
                    android:scrollbarThumbVertical="@drawable/terminal_scroll_shape"
                    android:scrollbars="vertical"
                    tools:ignore="UnusedAttribute" />

                <View
                    android:id="@+id/desktop_split_divider"
                    android:layout_width="2dp"
                    android:layout_height="match_parent"
                    android:background="#12264A"
                    android:visibility="gone" />

                <com.termux.view.TerminalView
                    android:id="@+id/terminal_view_secondary"
                    android:layout_width="0dp"
                    android:layout_height="match_parent"
                    android:layout_weight="1"
                    android:visibility="gone"
                    android:defaultFocusHighlightEnabled="false"
                    android:focusableInTouchMode="true"
                    android:scrollbarThumbVertical="@drawable/terminal_scroll_shape"
                    android:scrollbars="vertical"
                    tools:ignore="UnusedAttribute" />

            </LinearLayout>

            <LinearLayout
                android:id="@+id/left_drawer"
                android:layout_width="240dp"
                android:layout_height="match_parent"
                android:layout_gravity="start"
                android:choiceMode="singleChoice"
                android:divider="@android:color/transparent"
                android:dividerHeight="0dp"
                android:descendantFocusability="blocksDescendants"
                android:orientation="vertical"
                android:background="?attr/termuxActivityDrawerBackground">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="horizontal">
                    <ImageButton
                        android:id="@+id/settings_button"
                        android:layout_width="40dp"
                        android:layout_height="40dp"
                        android:src="@drawable/ic_settings"
                        android:background="@null"
                        android:contentDescription="@string/action_open_settings"
                        app:tint="?attr/termuxActivityDrawerImageTint" />
                </LinearLayout>

                <ListView
                    android:id="@+id/terminal_sessions_list"
                    android:layout_width="match_parent"
                    android:layout_height="0dp"
                    android:layout_gravity="top"
                    android:layout_weight="1"
                    android:choiceMode="singleChoice"
                    android:longClickable="true" />

                <LinearLayout
                    style="?android:attr/buttonBarStyle"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="horizontal">

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/toggle_keyboard_button"
                        style="?android:attr/buttonBarButtonStyle"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:text="@string/action_toggle_soft_keyboard" />

                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/new_session_button"
                        style="?android:attr/buttonBarButtonStyle"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:text="@string/action_new_session" />
                </LinearLayout>
            </LinearLayout>

        </androidx.drawerlayout.widget.DrawerLayout>

        <androidx.viewpager.widget.ViewPager
            android:id="@+id/terminal_toolbar_view_pager"
            android:visibility="gone"
            android:layout_width="match_parent"
            android:layout_height="37.5dp"
            android:background="@color/black"
            android:layout_alignParentBottom="true" />

    </RelativeLayout>

    <View
        android:id="@+id/activity_termux_bottom_space_view"
        android:layout_width="match_parent"
        android:layout_height="1dp"
        android:background="@android:color/transparent" />

</com.termux.app.terminal.TermuxActivityRootView>
```

---

## Step 4: Create `DesktopTabController.java` and `DesktopSplitPaneManager.java`

### Step 4A: Create `app/src/main/java/com/termux/app/terminal/DesktopTabController.java`
Use Expose Files MCP `write_file` to create `app/src/main/java/com/termux/app/terminal/DesktopTabController.java`:

```java
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
```

### Step 4B: Create `app/src/main/java/com/termux/app/terminal/DesktopSplitPaneManager.java`
Use Expose Files MCP `write_file` to create `app/src/main/java/com/termux/app/terminal/DesktopSplitPaneManager.java`:

```java
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
```

---

## Step 5: Wire Desktop Controllers, Right-Click Menu Actions, and Hardware Keyboard Detection in `TermuxActivity.java`

### Target File
`app/src/main/java/com/termux/app/TermuxActivity.java`

### Instructions for LLM Agent
1. Open `app/src/main/java/com/termux/app/TermuxActivity.java`.
2. Add the `Configuration` import near the top of `TermuxActivity.java`:
```java
import android.content.res.Configuration;
```
3. Add two fields to `TermuxActivity` (around line 135, next to `mTermuxSessionListViewController`):
```java
    private DesktopTabController mDesktopTabController;
    private DesktopSplitPaneManager mDesktopSplitPaneManager;
```
4. Add new context menu item ID constants around line 190 (after `CONTEXT_MENU_REPORT_ID = 9`):
```java
    private static final int CONTEXT_MENU_COPY_ID = 12;
    private static final int CONTEXT_MENU_PASTE_ID = 13;
    private static final int CONTEXT_MENU_SPLIT_HORIZONTAL_ID = 14;
    private static final int CONTEXT_MENU_SPLIT_VERTICAL_ID = 15;
```
5. In `setTermuxTerminalViewAndClients()` (around line 484), instantiate `DesktopTabController` and `DesktopSplitPaneManager` at the end of the method:
```java
        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onCreate();

        mDesktopTabController = new DesktopTabController(this);
        mDesktopSplitPaneManager = new DesktopSplitPaneManager(this);
        updateDesktopToolbarVisibilityForHardwareKeyboard();
```
6. In `setTermuxSessionsListView()` (around line 500), also refresh the desktop tab bar:
```java
    private void setTermuxSessionsListView() {
        ListView termuxSessionsListView = findViewById(R.id.terminal_sessions_list);
        mTermuxSessionListViewController = new TermuxSessionsListViewController(this, mTermuxService.getTermuxSessions());
        termuxSessionsListView.setAdapter(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemClickListener(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemLongClickListener(mTermuxSessionListViewController);
        if (mDesktopTabController != null) {
            mDesktopTabController.refreshTabs();
        }
    }
```
7. In `termuxSessionListNotifyUpdated()` (around line 858), update it so both the drawer list and the top desktop tab strip refresh whenever a session is added, removed, or renamed:
```java
    public void termuxSessionListNotifyUpdated() {
        if (mTermuxSessionListViewController != null)
            mTermuxSessionListViewController.notifyDataSetChanged();
        if (mDesktopTabController != null)
            mDesktopTabController.refreshTabs();
    }
```
8. In `onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo)` (around line 630), add Copy, Paste, and Split Pane actions at the top of the right-click context menu:
```java
    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        TerminalSession currentSession = getCurrentSession();
        if (currentSession == null) return;

        boolean autoFillEnabled = mTerminalView.isAutoFillEnabled();
        String selectedText = mTerminalView.getSelectedText();
        if (DataUtils.isNullOrEmpty(selectedText)) {
            selectedText = mTerminalView.getStoredSelectedText();
        }

        if (!DataUtils.isNullOrEmpty(selectedText)) {
            menu.add(Menu.NONE, CONTEXT_MENU_COPY_ID, Menu.NONE, R.string.copy_text);
        }
        menu.add(Menu.NONE, CONTEXT_MENU_PASTE_ID, Menu.NONE, R.string.paste_text);
        menu.add(Menu.NONE, CONTEXT_MENU_SPLIT_HORIZONTAL_ID, Menu.NONE, "Split Horizontally (Ctrl+Shift+D)");
        menu.add(Menu.NONE, CONTEXT_MENU_SPLIT_VERTICAL_ID, Menu.NONE, "Split Vertically (Ctrl+Shift+E)");
```
9. In `onContextItemSelected(MenuItem item)` (around line 661), handle the four new menu IDs inside the `switch (item.getItemId())` statement:
```java
            case CONTEXT_MENU_COPY_ID: {
                String sel = mTerminalView.getSelectedText();
                if (DataUtils.isNullOrEmpty(sel)) sel = mTerminalView.getStoredSelectedText();
                if (!DataUtils.isNullOrEmpty(sel) && session != null) {
                    session.onCopyTextToClipboard(sel);
                    mTerminalView.stopTextSelectionMode();
                }
                return true;
            }
            case CONTEXT_MENU_PASTE_ID:
                mTermuxTerminalViewClient.doPaste();
                return true;
            case CONTEXT_MENU_SPLIT_HORIZONTAL_ID:
                toggleDesktopSplitPane(false);
                return true;
            case CONTEXT_MENU_SPLIT_VERTICAL_ID:
                toggleDesktopSplitPane(true);
                return true;
```
10. Add these helper methods to `TermuxActivity.java` (just before `finishActivityIfNotFinishing()`):
```java
    public void toggleDesktopSplitPane(boolean vertical) {
        if (mDesktopSplitPaneManager != null) {
            mDesktopSplitPaneManager.toggleSplit(vertical);
        }
    }

    public void updateDesktopToolbarVisibilityForHardwareKeyboard() {
        Configuration config = getResources().getConfiguration();
        boolean hasHardwareKeyboard = (config.keyboard == Configuration.KEYBOARD_QWERTY &&
            config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO);
        boolean isDeskMode = (config.uiMode & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_DESK;
        ViewPager pager = getTerminalToolbarViewPager();
        if (pager != null && (hasHardwareKeyboard || isDeskMode)) {
            pager.setVisibility(View.GONE);
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateDesktopToolbarVisibilityForHardwareKeyboard();
        if (mTerminalView != null) mTerminalView.updateSize();
        if (mDesktopSplitPaneManager != null && mDesktopSplitPaneManager.getSecondaryView() != null) {
            mDesktopSplitPaneManager.getSecondaryView().updateSize();
        }
    }
```
11. Also in `TermuxTerminalSessionActivityClient.java` (at `setCurrentSession(TerminalSession session)`, line 293, and `onTitleChanged`, line 125), add a call to `mActivity.termuxSessionListNotifyUpdated();` inside `setCurrentSession` right after `checkAndScrollToSession(session);` so switching tabs via keyboard shortcuts (`Ctrl+Tab`) updates the active indicator bar on the top tab strip.

---

## Step 6: Configure `AndroidManifest.xml` for Samsung DeX Window Sizing & Keyboard Handling

### Target File
`app/src/main/AndroidManifest.xml`

### Instructions for LLM Agent
1. Open `app/src/main/AndroidManifest.xml`.
2. Locate the `<activity android:name=".app.TermuxActivity" ...>` block at lines 52–75:
```xml
        <activity
            android:name=".app.TermuxActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|smallestScreenSize|density|screenLayout|keyboard|keyboardHidden|navigation"
            android:label="@string/application_name"
            android:launchMode="singleTask"
            android:resizeableActivity="true"
            android:theme="@style/Theme.TermuxActivity.DayNight.NoActionBar"
            tools:targetApi="n">
```
3. Update the `configChanges` attribute to include `uiMode`, add `android:windowSoftInputMode="stateAlwaysHidden|adjustResize"`, and add a `<layout>` block inside `<activity android:name=".app.TermuxActivity">` so Samsung DeX opens Termux in a comfortable desktop window size (`960dp x 640dp`) by default:
```xml
        <activity
            android:name=".app.TermuxActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|smallestScreenSize|density|screenLayout|uiMode|keyboard|keyboardHidden|navigation"
            android:label="@string/application_name"
            android:launchMode="singleTask"
            android:resizeableActivity="true"
            android:windowSoftInputMode="stateAlwaysHidden|adjustResize"
            android:theme="@style/Theme.TermuxActivity.DayNight.NoActionBar"
            tools:targetApi="n">
            <layout
                android:defaultWidth="960dp"
                android:defaultHeight="640dp"
                android:gravity="center"
                android:minWidth="420dp"
                android:minHeight="300dp" />
```
4. Inside `<application ...>` (just before `</application>` at the bottom of `AndroidManifest.xml`), add Samsung DeX multi-window metadata tags:
```xml
        <!-- Samsung DeX & Multi-Window Desktop Support -->
        <meta-data
            android:name="com.samsung.android.sdk.multiwindow.enable"
            android:value="true" />
        <meta-data
            android:name="com.samsung.android.multidisplay.keep_process_alive"
            android:value="true" />
```

---

## Step 7: End-to-End Build & Verification Checklist for LLM Agents

After completing Steps 1 through 6, run the following commands via Expose Files MCP `run_command`:

1. **Compile `:terminal-emulator`, `:terminal-view`, and `:termux-shared`**:
   ```cmd
   gradlew.bat :terminal-emulator:compileDebugJavaWithJavac :terminal-view:compileDebugJavaWithJavac :termux-shared:compileDebugJavaWithJavac
   ```
2. **Assemble the full Debug APK (`:app:assembleDebug`)**:
   ```cmd
   gradlew.bat :app:assembleDebug
   ```
3. **Functional Acceptance Checklist on Samsung DeX / Android Desktop Mode**:
   - [ ] **Mouse Selection**: Left-click and drag highlights text without showing mobile teardrop handles or floating `ActionMode` popups; double-click selects a word; triple-click selects the entire row.
   - [ ] **TUI Mouse Override**: Inside `tmux` or `nvim` with mouse mode enabled, normal clicks go to the TUI while `Shift + Left-Click Drag` selects text locally.
   - [ ] **Pointer Icon**: Hovering over the terminal displays an I-beam (`TYPE_TEXT`) cursor and switches to a hand (`TYPE_HAND`) when `Ctrl` is held.
   - [ ] **Mouse Wheel & Zoom**: Scrolling the mouse wheel scrolls the transcript buffer; `Ctrl + Mouse Wheel` dynamically zooms the terminal font size.
   - [ ] **Desktop Keyboard Shortcuts**: `Ctrl+Shift+C` copies selected text, `Ctrl+Shift+V` pastes clipboard text, `Ctrl+Shift+T` opens a new tab in the current working directory, `Ctrl+Shift+W` closes the active session, `Ctrl+Tab` / `Ctrl+Shift+Tab` cycles tabs, and `Ctrl+Shift+D` / `Ctrl+Shift+E` toggles horizontal/vertical split panes.
   - [ ] **Top Tab Bar & ExtraKeys Auto-Hide**: Top tab strip shows all sessions with `+`, `◫`, `⬒`, and `×` buttons; bottom `ExtraKeysView` automatically hides when a hardware keyboard or DeX desktop mode is active.
