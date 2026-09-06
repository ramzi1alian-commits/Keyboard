# V32.2 IME / File Crypto Flow Fix

## Root cause
`FileCryptoActivity.onStop()` was used as the signal that the file-crypto flow had returned. `onStop()` also occurs when Android DocumentsUI opens above the activity, so the IME was asked to restore too early. `FLAG_ACTIVITY_NO_HISTORY` further weakened the return path.

## Fix
- Removed the `onStop()` broadcast.
- Broadcast only from `onDestroy()` when `isFinishing` is true.
- Removed `FLAG_ACTIVITY_NO_HISTORY` from the file-crypto launch path.
- Restore the secure crypto page with bounded explicit IME requests after the real activity finish.
- Preserve the existing `cryptoMenuSticky` state.

## Required device validation
1. Android 8: secure keyboard -> file encryption -> choose input -> choose output -> finish -> keyboard returns on secure crypto page.
2. Android 14: same flow.
3. Android 8/14: cancel input picker, then cancel output picker; keyboard must not jump to the normal keyboard unexpectedly.
4. Launch file encryption from the standalone File Crypto screen; returning must not corrupt the IME state.
