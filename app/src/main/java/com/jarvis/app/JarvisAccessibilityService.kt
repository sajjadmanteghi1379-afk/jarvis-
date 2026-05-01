package com.jarvis.app

/**
 * Backward-compatible alias for older references. The manifest now registers
 * MyAccessibilityService, which owns the real screen-text capture.
 */
class JarvisAccessibilityService : MyAccessibilityService()
