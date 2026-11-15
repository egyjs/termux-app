# Small-LLM Multi-Session Execution Guide (`AGENT_SESSION_GUIDE.md`)

This guide keeps context usage tiny (~3K–6K tokens per session) by instructing each LLM agent session to read **only** the handoff tracker (`AGENT_PROGRESS.md`), the shared guardrails (`Section 0`), and **one single step slice** from `DESKTOP_DEX_FORK_PLAN.md`.

---

## 1. Copy-Paste Master Prompt for Every Agent Session

Give this exact prompt to your small LLM at the start of **every** new session:

```text
You are executing ONE single step of the Termux Desktop (Samsung DeX) fork.
DO NOT read the entire DESKTOP_DEX_FORK_PLAN.md file at once. Follow this exact 5-step protocol:

1. READ PROGRESS: Read `AGENT_PROGRESS.md` to find `NEXT_STEP` (and see what previous sessions completed).
2. READ CONTEXT: Read "Section 0 & Architecture Overview" of `DESKTOP_DEX_FORK_PLAN.md` using `read_file` with `offset: 0, length: 3400`.
3. READ TARGET STEP ONLY: Look up the `offset` and `length` for `NEXT_STEP` in the Section Index Table inside `AGENT_SESSION_GUIDE.md`, then call `read_file` on `DESKTOP_DEX_FORK_PLAN.md` using ONLY that `offset` and `length`.
4. IMPLEMENT & VERIFY: Read only the target source file(s) listed in that step, apply the exact code changes, and run the step's verification command if specified.
5. WRITE HANDOFF & STOP: Update `AGENT_PROGRESS.md` to mark the current step `[x]`, log the files modified and build/check result under `## Last Session Handoff`, set `NEXT_STEP` to the next item in sequence, and STOP immediately. Do not start the next step in the same session.
```

---

## 2. Section Index Table for `DESKTOP_DEX_FORK_PLAN.md`

Use these exact `read_file(path="DESKTOP_DEX_FORK_PLAN.md", offset=..., length=...)` parameters so you never load the whole plan file into context:

| Step ID | Section Heading in `DESKTOP_DEX_FORK_PLAN.md` | `offset` (bytes) | `length` (bytes) | Target Source File(s) |
| :--- | :--- | :--- | :--- | :--- |
| **Context** | `## 0. Strict Guardrails` + `## Architecture Overview` | `0` | `3400` | *(Read in every session)* |
| **Step 1A** | `## Step 1A: Add Handle-Free Desktop Mouse Selection...` | `3350` | `6600` | `terminal-view/.../TextSelectionCursorController.java` |
| **Step 1B** | `## Step 1B: Update TerminalView.java for Mouse Drag...` | `9900` | `6500` | `terminal-view/.../TerminalView.java` |
| **Step 2** | `## Step 2: Add Desktop Keyboard Shortcuts...` | `16350` | `4600` | `app/.../terminal/TermuxTerminalViewClient.java` |
| **Step 3** | `## Step 3: Add Desktop Top Tab Bar & Split-Pane...` | `20900` | `9400` | `app/src/main/res/layout/item_desktop_tab.xml`, `activity_termux.xml` |
| **Step 4** | `## Step 4: Create DesktopTabController.java and...` | `30250` | `9600` | `app/.../terminal/DesktopTabController.java`, `DesktopSplitPaneManager.java` |
| **Step 5** | `## Step 5: Wire Desktop Controllers, Right-Click...` | `39800` | `5200` | `app/.../TermuxActivity.java`, `TermuxTerminalSessionActivityClient.java` |
| **Step 6** | `## Step 6: Configure AndroidManifest.xml for Samsung...` | `44950` | `2300` | `app/src/main/AndroidManifest.xml` |
| **Step 7** | `## Step 7: End-to-End Build & Verification Checklist` | `47200` | `2500` | Full Gradle build (`gradlew.bat :app:assembleDebug`) |

*(Tip: If an edit ever shifts offsets in `DESKTOP_DEX_FORK_PLAN.md`, run `search_files(path="DESKTOP_DEX_FORK_PLAN.md", contentRegex="^## Step")` to see exact line numbers.)*

---

## 3. Session-by-Session Execution Flow

```text
Session 1 ──► Reads AGENT_PROGRESS.md (NEXT_STEP = Step 1A)
          ──► Reads DESKTOP_DEX_FORK_PLAN.md [offset 0, len 3400] (Context)
          ──► Reads DESKTOP_DEX_FORK_PLAN.md [offset 3350, len 6600] (Step 1A)
          ──► Edits TextSelectionCursorController.java
          ──► Updates AGENT_PROGRESS.md (NEXT_STEP = Step 1B) & exits

Session 2 ──► Reads AGENT_PROGRESS.md (NEXT_STEP = Step 1B + Session 1 notes)
          ──► Reads DESKTOP_DEX_FORK_PLAN.md [offset 0, len 3400] (Context)
          ──► Reads DESKTOP_DEX_FORK_PLAN.md [offset 9900, len 6500] (Step 1B)
          ──► Edits TerminalView.java & runs :terminal-view:compileDebugJavaWithJavac
          ──► Updates AGENT_PROGRESS.md (NEXT_STEP = Step 2) & exits
   ...
Session 7 ──► Completes Step 6 & Step 7 full APK verification (NEXT_STEP = DONE)
```

---

## 4. Required Format for `AGENT_PROGRESS.md`

After finishing its single assigned step, each agent session must overwrite `AGENT_PROGRESS.md` using this exact template:

```markdown
# Termux DeX Fork — Agent Session Progress Tracker

NEXT_STEP: <Step 1A | Step 1B | Step 2 | Step 3 | Step 4 | Step 5 | Step 6 | Step 7 | DONE>

## Step Checklist
- [x] Step 1A: Handle-free mouse selection in `TextSelectionCursorController.java`
- [ ] Step 1B: Mouse drag, `Ctrl+Scroll` zoom, and `PointerIcon` in `TerminalView.java`
- [ ] Step 2: Desktop `Ctrl+Shift+*` shortcuts & `Ctrl+Click` URL in `TermuxTerminalViewClient.java`
- [ ] Step 3: Desktop tab item & split-pane layouts (`item_desktop_tab.xml`, `activity_termux.xml`)
- [ ] Step 4: `DesktopTabController.java` and `DesktopSplitPaneManager.java`
- [ ] Step 5: Wire controllers, context menu, and hardware keyboard detection in `TermuxActivity.java`
- [ ] Step 6: Samsung DeX window size & metadata in `AndroidManifest.xml`
- [ ] Step 7: Final Gradle build verification (`gradlew.bat :app:assembleDebug`)

## Last Session Handoff
- **Completed Step**: Step 1A
- **Files Created/Modified**: `terminal-view/src/main/java/com/termux/view/textselection/TextSelectionCursorController.java`
- **Summary of Changes**: Added `mIsMouseSelection`, `mAnchorX/Y`, `startMouseSelection()`, `updateMouseSelection()`, and `cacheSelectedTextForContextMenu()`.
- **Verification / Build Status**: Ready for Step 1B (`TerminalView.java` calls these methods, then run `:terminal-view:compileDebugJavaWithJavac`).
- **Instructions for Next Session**: Execute `Step 1B` at `offset: 9900, length: 6500` in `DESKTOP_DEX_FORK_PLAN.md`.
```
