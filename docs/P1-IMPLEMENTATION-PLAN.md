# Implementation Plan: Priority 1 Remaining (UI/UX & Smoothness) — REFINED

**Goal:** Implement all remaining items from IMPROVEMENTS.md §1 (Priority 1) that are not yet done.

**Refinements applied:**
- Shimmer: Implement as `Shimmer.kt` in `ui/theme` with a single `@Composable ShimmerBox(Modifier)` and theme-aware colors; use `graphicsLayer` + `Brush.linearGradient` + `infiniteTransition` for one lightweight animation.
- Thumbnail placeholder: Use a `Box` with `ShimmerBox` as background and `AsyncImage` on top (placeholder = transparent or null so shimmer shows until load); keeps layout stable.
- Nav accessibility: Reuse existing strings `nav_timeline`, `nav_backup`, `nav_settings`, `nav_media_type` for `contentDescription` (no new strings unless a different TalkBack phrase is desired).
- Notification dialog: Add string `permission_notifications_title` = "Notifications" for the dialog title.
- Cold start: Use `delay(100)` then `_isLoading.value = false` (Option A); do not wait for DB in that block.

**Scope (remaining items only):**
- **1.1b** Smooth scrolling: placeholder/skeleton while thumbnails load (shimmer or skeleton so no “blank cells that pop in late”).
- **1.1c** Loading and empty states: “obvious but unobtrusive” loading (e.g. shimmer or subtle progress); empty timeline already has CTA.
- **1.3a** Permission explanations: show “Why we need this” for **notifications** before requesting (storage and camera already done; string `permission_notifications_reason` exists but is not used).
- **1.4a** Thumbnails: no blank cells, placeholders or consistent sizing so layout doesn’t jump (reinforce with shimmer on placeholder).
- **1.4b** Cold start: Splash → main screen quick; defer heavy work until after first paint (shorten or remove block on `getTimeline().first()` so first paint is not delayed).
- **1.5b** Accessibility: TalkBack labels (nav bar icons have `contentDescription = null`), sufficient touch targets (48.dp minimum where missing).
- **1.5c** Micro-copy: already consistent (“Back up now” / “Sync now”); no code change unless we add a dedicated “Synced” string for pull-to-refresh success (optional; “Up to date” is already used).

**Out of scope for this plan:** 1.2 (backup/restore operations — already implemented), 1.1d (pull-to-refresh success state — already shows “Up to date” snackbar), 1.1f (list/grid toggle — done), theme (done).

---

## Files to create or modify

| File | Action |
|------|--------|
| `app/src/main/java/com/ulap/ui/NotificationPermission.kt` | Modify: show explanation dialog before requesting POST_NOTIFICATIONS |
| `app/src/main/java/com/ulap/ui/gallery/TimelineScreen.kt` | Modify: shimmer loading state, shimmer/skeleton placeholder for thumbnails |
| `app/src/main/java/com/ulap/ui/gallery/TimelineViewModel.kt` | Modify: cold start — reduce/remove block on first DB emission for first paint |
| `app/src/main/java/com/ulap/MainActivity.kt` | Modify: add contentDescription to bottom nav icons (use string resources) |
| `app/src/main/res/values/strings.xml` | Modify: add nav content descriptions if missing |
| `app/src/main/java/com/ulap/ui/theme/Shimmer.kt` | Create: `@Composable ShimmerBox(modifier: Modifier)` using theme colors and single `infiniteTransition` + gradient offset |

---

## Implementation order and details

### 1. Notification permission explanation (1.3a)

**File:** `NotificationPermission.kt`

- When the returned lambda is invoked and `granted` is false, **do not** call `launcher.launch()` immediately.
- Add state: `var showExplanationDialog by remember { mutableStateOf(false) }`.
- When user triggers action and `!granted`: set `showExplanationDialog = true`.
- Compose an `AlertDialog` when `showExplanationDialog` is true:
  - Title: e.g. “Notifications” (add string `permission_notifications_title` or use existing).
  - Text: `stringResource(R.string.permission_notifications_reason)`.
  - “Not now” → set `showExplanationDialog = false`.
  - “Allow” → set `showExplanationDialog = false`; call `launcher.launch(Manifest.permission.POST_NOTIFICATIONS)`.
- Keep existing launcher callback: `granted = result`; if `result` run `action()`.
- **Strings:** Add `permission_notifications_title` = “Notifications” (or use a generic title) in `strings.xml` if we want a dedicated title; otherwise use “Allow notifications” as title.

**Constraints:** Do not change call sites (BackupScreen, RestoreScreen, FolderPickerScreen); the composable continues to return a single `() -> Unit`.

---

### 2. Shimmer / loading and placeholder (1.1b, 1.1c, 1.4a)

**Files:** `app/src/main/java/com/ulap/ui/theme/Shimmer.kt` (create), `TimelineScreen.kt` (modify).

- **Shimmer modifier/composable:** Add a simple brush-based shimmer (e.g. `Modifier.shimmer()` or `@Composable fun ShimmerBox(modifier: Modifier)`) using `Brush.linearGradient` with `Color.Unspecified` / `surfaceVariant` and animation (e.g. `infiniteTransition` + `animateFloat`). Keep it lightweight; no new dependencies (use Compose foundation + material).
- **Timeline loading (1.1c):** Replace or supplement the full-screen `CircularProgressIndicator` when `isLoading` with a **shimmer skeleton** of the timeline: same structure as the grid (e.g. 3 columns, fixed count of placeholder cells with `aspectRatio(1f)`) so the layout is stable and “obvious but unobtrusive”. Option: show a LazyVerticalGrid of placeholder boxes with shimmer for ~6–9 cells, then when `!isLoading` show real content.
- **Thumbnail placeholder (1.1b, 1.4a):** In `MediaThumbnail` and `TimelineListRow`, when showing placeholder (no URL yet or loading), use the same shimmer on the placeholder box (e.g. `Box(modifier = Modifier.shimmer())` or `Box(modifier = Modifier.background(brush = shimmerBrush))`) instead of only `ColorPainter(surfaceVariant)`, so “no blank cells that pop in late” and layout stays stable (keep `aspectRatio(1f)` and fixed size).

**Placeholder logic:** Keep existing behavior: when `item.streamUrl == null` and we have `contentUri`, we still load local; when neither or loading, show shimmer placeholder. For cloud-only items we may not have URL until resolved; that’s when the shimmer placeholder is most useful.

---

### 3. Cold start (1.4b)

**File:** `TimelineViewModel.kt`

- In `init`, the first `viewModelScope.launch` block currently does `withTimeout(3_000) { getTimeline().first() }` then `_isLoading.value = false`. This can block first paint for up to 3 seconds.
- **Change:** In the first `viewModelScope.launch` block, do **only** `delay(100)` then `_isLoading.value = false`. Remove `withTimeout(3_000) { getTimeline().first() }`. First paint happens within ~100ms; loading skeleton (step 2) is shown until `groups` has data (flow will update when DB emits).
- Leave the **second** `viewModelScope.launch` block unchanged (fetchIndex, refreshFolders, scanMedia after first paint).

---

### 4. Accessibility (1.5b)

**Files:** `MainActivity.kt`, `strings.xml`

- **Nav bar icons:** Replace `contentDescription = null` with `stringResource(R.string.nav_timeline)`, `stringResource(R.string.nav_media_type)`, `stringResource(R.string.nav_backup)`, `stringResource(R.string.nav_settings)` for the four items respectively. No new strings; reuse existing nav_* strings.
- **Touch targets:** No code change; TimelineScreen already uses `minimumInteractiveComponentSize()` for the view-mode IconButton. Material3 `NavigationBarItem` meets 48.dp by default.

---

### 5. Micro-copy (1.5c)

- No change required: “Back up now” and “Sync now” are already used consistently; pull-to-refresh success uses “Up to date”. Optionally add a string `sync_complete` = “Synced” and use it for the refresh-completed snackbar instead of “Up to date” for slightly clearer success feedback; **optional** and low priority.

---

## Architecture and constraints

- **No new dependencies:** Use only Compose (foundation, material3) and existing project dependencies for shimmer.
- **Minimal surface:** Only the files listed above; do not refactor unrelated screens.
- **Strings:** All new user-visible text in `strings.xml` (and consider `strings.xml` for any new locale later).
- **Theme:** Shimmer colors should use `MaterialTheme.colorScheme.surfaceVariant` (and optionally `surfaceVariant.copy(alpha = 0.x)`) so dark/light theme are respected.

---

## Risks and edge cases

- **Shimmer performance:** Keep animation simple (single float driving gradient offset) to avoid jank on low-end devices.
- **Cold start:** If we set `_isLoading = false` after 100ms and DB is slow, user sees loading skeleton (shimmer grid) until first emission; that’s acceptable and matches “defer heavy work until after first paint”.
- **Notification dialog:** If user taps “Not now”, the action (e.g. start backup) is not run; that’s correct. If user taps “Allow” and then denies in the system dialog, we don’t run the action (current behavior).

---

## Summary checklist

- [ ] 1.3a Notification permission: dialog with `permission_notifications_reason` before request.
- [ ] 1.1b / 1.4a Shimmer placeholder for thumbnail cells (and list row thumbnail).
- [ ] 1.1c Shimmer/skeleton for initial timeline loading instead of only spinner.
- [ ] 1.4b Cold start: set `_isLoading = false` after short delay (e.g. 100ms), not after `getTimeline().first()`.
- [ ] 1.5b Nav bar contentDescription from string resources; touch targets verified.
- [ ] 1.5c No change (or optional “Synced” string).
