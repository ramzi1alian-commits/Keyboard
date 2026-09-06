# SecureKeyboard — Threat Model

Status: engineering-authored, **not** yet reviewed by an accredited third-party
lab. This document exists to make that future review possible and efficient —
it is a starting point for an auditor, not a substitute for one.

## 1. Scope

In scope: the Android application in this repository (input method service,
encryption/decryption activities, contact pairing, local storage, file
encryption). Out of scope: the Android OS/kernel, the device hardware,
Google Play services, and any other app the user types into.

## 2. Assets (what we are protecting)

| Asset | Where it lives | Confidentiality need |
|---|---|---|
| Plaintext being typed | Transient, in `InputConnection` calls | High while in this app; N/A once committed to another app's field |
| User passphrase | Transient `CharArray` | Very high |
| Derived AES keys | Transient `ByteArray` | Very high |
| Device ECDH private key | `device_identity_v14.enc`, Keystore-wrapped | Very high |
| Paired contacts' public keys | `ContactStore`, Keystore-wrapped at rest | Medium (public keys, but linkage is sensitive) |
| Learned dictionary / phrases | `LearnedDictionary.kt`, `PhraseDictionary.kt`, Keystore-wrapped | Medium |
| Ciphertext of messages/files | Wherever the user pastes/saves it | N/A (designed to be shareable) |

## 3. Adversary model — three tiers, explicitly

Being explicit about which tier is (and is not) addressed is the single
biggest gap an unstated threat model leaves for a reviewer.

### Tier 1 — Opportunistic (addressed)
A stranger who finds a lost/stolen unlocked or locked phone, a nosy
acquaintance with brief physical access, or another app on the same device
trying to read the clipboard/screen. **This app's current controls target
this tier**: `FLAG_SECURE`, clipboard auto-clear + sensitive marking, no
`INTERNET` permission, `allowBackup="false"`, Keystore-wrapped local storage.

### Tier 2 — Motivated local attacker with forensic tools (partially addressed)
Someone with extended physical access to an unlocked/rooted device and
commodity forensic tooling (e.g. Cellebrite-class extraction). Argon2id
(256 MB default) and Keystore/StrongBox-backed keys raise the cost here, but:
- On devices **without** StrongBox/TEE-backed Keystore, the OS-level guarantee
  degrades to software-only key wrapping.
- A rooted device with the app's process compromised while unlocked can read
  keys from live memory (no defense claims otherwise).

### Tier 3 — Nation-state / advanced persistent adversary (NOT addressed)
Supply-chain compromise of the build pipeline, hardware implants, cryptanalytic
advances, side-channel attacks (timing/power/EM) against the AES-GCM or Argon2id
implementations, or legal/coercive compulsion of the user. **This project makes
no claims of resistance at this tier.** This is the primary reason it is not
suitable for military/government classified use as-is (see README "V20 is not
a military/FIPS certification").

## 4. Trust boundaries

1. **Keyboard ↔ host app**: any app the user types into is untrusted from this
   app's perspective beyond `InputConnection.commitText()`. This app cannot
   control what the host app does with committed text afterward.
2. **This app ↔ Android OS/Keystore**: trusted for key wrapping and hardware
   backing where available; trust degrades on rooted/compromised devices.
3. **This app ↔ paired contact's device**: trusted only after out-of-band
   safety-number verification (`ContactPairingActivity`). Unverified pairing
   is explicitly a MITM risk and is documented as such.
4. **This app ↔ its own build/CI pipeline**: currently **not modeled** —
   addressed in the supply-chain hardening below.

## 5. Known accepted risks / non-goals

- `secureDelete()` is best-effort; flash storage wear-leveling means physical
  overwrite is not guaranteed (already documented in-code).
- Metadata (message length, timing, who paired with whom) is not hidden.
- No forward secrecy for the passphrase-only (non-ECDH) message path — only
  the ECDH contact path (`CryptoEngineV2`) gets fresh ephemeral keys per
  message.
- No protection against a compromised host keyboard app capturing keystrokes
  *before* this app is switched to (applies to any third-party IME).
- No code obfuscation claims beyond standard ProGuard/R8 shrinking — this is
  not anti-reverse-engineering hardening.

## 6. What third-party audit should focus on

For a future accredited reviewer, the highest-value targets are, in order:
1. `CryptoEngine.kt` / `CryptoEngineV2.kt` — AEAD usage, nonce uniqueness
   guarantees, Argon2id parameter bounds-checking against DoS.
2. `ContactPairingActivity.kt` / `DeviceIdentity.kt` — ECDH key exchange and
   safety-number MITM defense.
3. `LocalStorageCrypto.kt` — Keystore key generation flags (is the key
   actually non-exportable, StrongBox-backed where available, and
   authentication-bound correctly?).
4. Build/CI pipeline — see `SUPPLY_CHAIN.md`.

## 7. Revision history
- v1 (this document): initial formal threat model, written to prepare the
  project for independent review. No prior version existed.
