# V38 Build Fix

- Added the missing `android.graphics.drawable.GradientDrawable` import used by the long-press popup background.
- Removed `FLAG_ACTIVITY_NO_HISTORY` from the Secure Compose popup Activity launch so returning from external Android document/file UI does not discard the activity.
- No cryptographic algorithm or file-format changes in this fix.
