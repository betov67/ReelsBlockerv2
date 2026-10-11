package com.example.reelsblocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * ReelsBlockerAccessibilityService  v2
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * ── Blocking rules ──────────────────────────────────────────────────────────
 *  Rule 1 – BOTTOM NAV TAB  : Tap on Reels tab → GLOBAL_ACTION_BACK
 *  Rule 2 – HOME FEED REEL  : Reel expands from main feed → GLOBAL_ACTION_BACK
 *  Rule 3 – PROFILE REEL    : Reel opened from a profile → watch freely
 *                             but swipe/scroll → GLOBAL_ACTION_BACK (Rule 5)
 *  Rule 4 – DM REEL         : Reel opened from DM → watch freely
 *                             but swipe/scroll → GLOBAL_ACTION_BACK (Rule 5)
 *  Rule 5 – SWIPE BLOCKER   : Vertical scroll inside an active Reel view
 *                             (including after ad injection) → GLOBAL_ACTION_BACK
 *
 * ── v2 bug fixes ────────────────────────────────────────────────────────────
 *  Fix A – STORIES EXEMPTION
 *    Stories share ReelViewer containers with Reels. We detect Story context
 *    by looking for Story-exclusive nodes (reply bar, segmented progress bar,
 *    avatar tray) and Story-containing class-name fragments. If ANY is found
 *    the entire event is silently skipped.
 *
 *  Fix B – AD REEL STATE-BREAK
 *    Ad Reels inject a different subtree (ad_unit_root, sponsored_label…)
 *    that previously caused isReelPlayerVisible() to return false mid-session,
 *    resetting reelIsAllowedAndActive. Fix: expanded REEL_PLAYER_IDS to cover
 *    ad containers, and on TYPE_WINDOW_CONTENT_CHANGED we only reset state on
 *    a definitive window-state event, not on transient content changes.
 *
 *  Fix C – DM SWIPE LOCK stays armed across re-navigations
 *    Origin is re-latched on every clean window-state transition so that
 *    FEED → back → DM → Reel correctly re-arms the swipe blocker.
 *
 *  Fix D – CONTENT-CHANGE FLOOD GUARD
 *    Full hierarchy traversal on TYPE_WINDOW_CONTENT_CHANGED is debounced
 *    to at most once per CONTENT_CHANGE_DEBOUNCE_MS to avoid CPU spikes.
 */
class ReelsBlockerAccessibilityService : AccessibilityService() {

    // ─── Constants ────────────────────────────────────────────────────────────
    companion object {
        private const val TAG = "ReelsBlocker"
        private const val INSTAGRAM_PKG = "com.instagram.android"

        private const val BACK_COOLDOWN_MS           = 700L
        private const val CONTENT_CHANGE_DEBOUNCE_MS = 300L

        // Bottom-nav Reels tab IDs
        private val REELS_TAB_IDS = setOf(
            "clips_tab", "tab_icon_clips", "reels_tab", "tab_clips",
            "bottom_nav_clips", "bottom_navigation_clips"
        )

        // Reel/Clips player containers — includes ad-Reel roots (Fix B)
        private val REEL_PLAYER_IDS = setOf(
            "clips_viewer_root",
            "unified_reel_view",
            "reel_viewer_root",
            "clips_swipe_refresh",
            "reel_viewer_pager",
            "clips_video_container",
            "clips_fragment_root",
            "reels_viewer_fragment_root",
            // Ad-Reel containers (Fix B)
            "ad_unit_root",
            "ads_manager_view_holder",
            "carousel_ad_container",
            "sponsored_reel_root"
        )

        // Story-exclusive node IDs (Fix A)
        // These only appear in Stories, never in regular Reels.
        private val STORY_EXCLUSIVE_IDS = setOf(
            "story_reply_composer",
            "reply_composer",
            "story_progress_bar",
            "story_duration_bar",
            "story_progress_container",
            "stories_tray",
            "stories_header",
            "story_viewer",
            "ig_story_view",
            "story_share_sheet"
        )

        // Story Activity/Fragment class-name fragments (Fix A)
        // "ReelViewer" is intentionally excluded here — it is also used for
        // regular Reels and would cause false positives; we handle it separately.
        private val STORY_CLASS_FRAGMENTS = setOf(
            "StoryViewer", "Stories", "StoryFragment", "StoryActivity"
        )

        // Feed-origin indicators
        private val FEED_ORIGIN_IDS = setOf(
            "feed_timeline",
            "home_feed_container",
            "clips_home_unit",
            "feed_surface_controller",
            "main_tab_fragment_container"
        )

        // Profile-origin indicators
        private val PROFILE_ORIGIN_IDS = setOf(
            "profile_main_content_view",
            "profile_tab_icon",
            "user_profile_header_list_view",
            "profile_grid_view",
            "media_grid_view",
            "profile_clips_tab"
        )

        // DM-origin indicators
        private val DM_ORIGIN_IDS = setOf(
            "direct_thread_fragment",
            "ig_direct_thread",
            "direct_thread_row",
            "direct_message_container",
            "thread_fragment",
            "direct_inbox_fragment",
            "direct_visual_thread_fragment"
        )
    }

    // ─── State ────────────────────────────────────────────────────────────────

    private enum class ReelOrigin { UNKNOWN, FEED, PROFILE, DM }

    /** True while the user watches an allowed Reel (profile or DM); swipe fires BACK. */
    @Volatile private var reelIsAllowedAndActive = false

    /** Origin of the current Reel session. */
    @Volatile private var currentReelOrigin = ReelOrigin.UNKNOWN

    /**
     * True after we successfully classified the current Reel's origin.
     * Prevents ad-content changes from re-running origin detection and
     * accidentally resetting the session (Fix B).
     */
    @Volatile private var reelOriginLatched = false

    /** Last window class name from TYPE_WINDOW_STATE_CHANGED. */
    @Volatile private var lastWindowClassName = ""

    /** Epoch-ms of last GLOBAL_ACTION_BACK we fired. */
    @Volatile private var lastBackMs = 0L

    /** Epoch-ms of last hierarchy traversal (debounce Fix D). */
    @Volatile private var lastTraversalMs = 0L

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "v2 — AccessibilityService connected")
        serviceInfo = serviceInfo.apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED   or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED          or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            packageNames = arrayOf(INSTAGRAM_PKG)
            notificationTimeout = 80
        }
        startForegroundService(Intent(this, BlockerForegroundService::class.java))
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted — resetting state")
        resetReelState()
    }

    // ─── Event dispatcher ─────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != INSTAGRAM_PKG) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED   -> onWindowStateChanged(event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> onContentChanged()
            AccessibilityEvent.TYPE_VIEW_CLICKED           -> onViewClicked(event)
            AccessibilityEvent.TYPE_VIEW_SCROLLED          -> onViewScrolled(event)
        }
    }

    // ─── Rule 1 — Bottom-nav Reels tab ───────────────────────────────────────

    private fun onViewClicked(event: AccessibilityEvent) {
        val fullId  = event.source?.viewIdResourceName ?: return
        val shortId = fullId.substringAfterLast('/')
        if (REELS_TAB_IDS.any { shortId.contains(it, ignoreCase = true) }) {
            Log.d(TAG, "Rule 1 — Reels tab clicked ($shortId)")
            fireBack("Rule1-NavTab")
        }
    }

    // ─── Rules 2/3/4 — Window-state change ───────────────────────────────────

    private fun onWindowStateChanged(event: AccessibilityEvent) {
        lastWindowClassName = event.className?.toString() ?: ""
        // Window-state changes always unblock origin re-classification.
        evaluateScreen(isWindowStateEvent = true)
    }

    // ─── Content change — debounced ───────────────────────────────────────────

    private fun onContentChanged() {
        val now = System.currentTimeMillis()
        if (now - lastTraversalMs < CONTENT_CHANGE_DEBOUNCE_MS) return
        evaluateScreen(isWindowStateEvent = false)
    }

    // ─── Core evaluation ─────────────────────────────────────────────────────

    /**
     * Walks the window hierarchy once and applies Rules 1–4.
     *
     * @param isWindowStateEvent true → a brand-new window opened; we may safely
     *   reset state and re-classify the origin. false → in-place content update
     *   (e.g. ad injection); preserve latched origin (Fix B).
     */
    private fun evaluateScreen(isWindowStateEvent: Boolean) {
        val root = rootInActiveWindow ?: return
        lastTraversalMs = System.currentTimeMillis()
        try {
            // ── STORIES EXEMPTION (Fix A) — checked before everything else ────
            if (isStoryContext(root)) {
                Log.d(TAG, "Story context — all rules bypassed")
                return
                // We intentionally do NOT reset reelIsAllowedAndActive here.
                // If the user opened a Story while a Reel session was active the
                // Reel session was already over (Story ≠ Reel screen), but we
                // also don't want to spuriously disarm a future allowed Reel.
            }

            val reelVisible = isReelPlayerVisible(root)

            if (reelVisible) {
                // Only re-classify if this is a fresh window or origin unknown
                if (isWindowStateEvent || !reelOriginLatched) {
                    val origin = detectReelOrigin(root)
                    currentReelOrigin = origin
                    reelOriginLatched = true

                    when (origin) {
                        ReelOrigin.FEED, ReelOrigin.UNKNOWN -> {
                            Log.d(TAG, "Rule 2 — Reel from ${origin.name} → BACK")
                            reelIsAllowedAndActive = false
                            fireBack("Rule2-${origin.name}")
                        }
                        ReelOrigin.PROFILE, ReelOrigin.DM -> {
                            Log.d(TAG, "Rules 3/4 — Reel from ${origin.name} → allowed, swipe armed")
                            reelIsAllowedAndActive = true
                        }
                    }
                }
                // else: in-content update inside same Reel → keep latched state (Fix B)

            } else {
                // Reel player gone — only reset on a clean window-state transition
                // so transient ad-layout changes don't disarm the blocker (Fix B).
                if (isWindowStateEvent && (reelIsAllowedAndActive || reelOriginLatched)) {
                    Log.d(TAG, "Reel player gone — full state reset")
                    resetReelState()
                }
            }
        } finally {
            root.recycle()
        }
    }

    // ─── Rule 5 — Swipe/scroll blocker ───────────────────────────────────────

    private fun onViewScrolled(event: AccessibilityEvent) {
        if (!reelIsAllowedAndActive) return

        val fullId  = event.source?.viewIdResourceName ?: ""
        val shortId = fullId.substringAfterLast('/')

        val inReel = REEL_PLAYER_IDS.any { shortId.contains(it, ignoreCase = true) }
            || parentInReelPlayer(event.source)

        if (inReel) {
            Log.d(TAG, "Rule 5 — Scroll inside Reel (origin=${currentReelOrigin}) → BACK")
            reelIsAllowedAndActive = false
            reelOriginLatched      = false
            fireBack("Rule5-Swipe")
        }
    }

    private fun parentInReelPlayer(node: AccessibilityNodeInfo?): Boolean {
        val parent = node?.parent ?: return false
        return try {
            val pid = parent.viewIdResourceName?.substringAfterLast('/') ?: ""
            REEL_PLAYER_IDS.any { pid.contains(it, ignoreCase = true) }
        } finally {
            parent.recycle()
        }
    }

    // ─── Stories discrimination (Fix A) ──────────────────────────────────────

    /**
     * Returns true if the current screen is an Instagram Story.
     *
     * Two-signal detection:
     *  1. Class-name check (fast, zero traversal) — catches clearly named classes.
     *  2. Story-exclusive node DFS — looks for nodes that only appear in Stories
     *     (reply composer, segmented progress bar, avatar tray).
     *
     * "ReelViewer" is NOT used as a class-name signal because Instagram uses it
     * for both Stories and Reels. We rely on node IDs for disambiguation.
     */
    private fun isStoryContext(root: AccessibilityNodeInfo): Boolean {
        // Class-name fast path (excludes "ReelViewer" deliberately)
        if (STORY_CLASS_FRAGMENTS.any { lastWindowClassName.contains(it, ignoreCase = true) }) {
            return true
        }
        // Node-ID DFS — Story-exclusive elements, depth capped for speed
        return containsIdFragment(root, STORY_EXCLUSIVE_IDS, maxDepth = 8)
    }

    // ─── Reel player detection ────────────────────────────────────────────────

    private fun isReelPlayerVisible(root: AccessibilityNodeInfo): Boolean {
        // Fast exact-lookup first
        for (id in REEL_PLAYER_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId("$INSTAGRAM_PKG:id/$id")
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                return true
            }
        }
        // DFS fallback for partial/suffixed IDs
        return containsIdFragment(root, REEL_PLAYER_IDS, maxDepth = 10)
    }

    // ─── Origin detection ─────────────────────────────────────────────────────

    /**
     * Determines which Instagram surface launched the current Reel.
     * Priority order: DM > Profile > Feed > class-name heuristic > UNKNOWN.
     * DM is checked first because DM threads can contain profile previews that
     * would otherwise match the Profile rule prematurely.
     */
    private fun detectReelOrigin(root: AccessibilityNodeInfo): ReelOrigin {
        if (containsIdFragment(root, DM_ORIGIN_IDS,      maxDepth = 10)) return ReelOrigin.DM
        if (containsIdFragment(root, PROFILE_ORIGIN_IDS, maxDepth = 10)) return ReelOrigin.PROFILE
        if (containsIdFragment(root, FEED_ORIGIN_IDS,    maxDepth = 10)) return ReelOrigin.FEED
        // Class-name fallback
        return when {
            lastWindowClassName.contains("Direct",  ignoreCase = true) -> ReelOrigin.DM
            lastWindowClassName.contains("Profile", ignoreCase = true) -> ReelOrigin.PROFILE
            lastWindowClassName.contains("MainTab", ignoreCase = true) -> ReelOrigin.FEED
            lastWindowClassName.contains("Feed",    ignoreCase = true) -> ReelOrigin.FEED
            else -> ReelOrigin.UNKNOWN
        }
    }

    // ─── DFS hierarchy helper ─────────────────────────────────────────────────

    /**
     * Depth-first search: returns true if any node's short view-ID (the part
     * after the last '/') contains any string from [fragments].
     * All child nodes obtained during traversal are recycled before return.
     */
    private fun containsIdFragment(
        node: AccessibilityNodeInfo,
        fragments: Set<String>,
        maxDepth: Int,
        depth: Int = 0
    ): Boolean {
        if (depth > maxDepth) return false
        val shortId = node.viewIdResourceName?.substringAfterLast('/') ?: ""
        if (shortId.isNotEmpty() && fragments.any { shortId.contains(it, ignoreCase = true) }) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = try {
                containsIdFragment(child, fragments, maxDepth, depth + 1)
            } finally {
                child.recycle()
            }
            if (found) return true
        }
        return false
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun resetReelState() {
        reelIsAllowedAndActive = false
        currentReelOrigin      = ReelOrigin.UNKNOWN
        reelOriginLatched      = false
    }

    private fun fireBack(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastBackMs < BACK_COOLDOWN_MS) {
            Log.d(TAG, "Back cooldown — skipping [$reason]")
            return
        }
        lastBackMs = now
        Log.i(TAG, "GLOBAL_ACTION_BACK ← $reason")
        performGlobalAction(GLOBAL_ACTION_BACK)
    }
}
