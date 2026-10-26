# Android authentication API checks

Inspected official Android documentation on 2026-10-25 for the P0 probe. This is API evidence, not device evidence.

- [KeyInfo.getSecurityLevel](https://developer.android.com/reference/android/security/keystore/KeyInfo#getSecurityLevel()) is added in runtime API 31. Guard calls accordingly; use `isInsideSecureHardware` below 31. Do not infer runtime availability from the broader Keystore guide's target-SDK wording.
- [Platform BiometricPrompt](https://developer.android.com/reference/android/hardware/biometrics/BiometricPrompt) provides crypto-bound authentication from API 28, requires `USE_BIOMETRIC`, and permits only strong biometric integration with Keystore. The host does not need the manager's Flutter Activity or AndroidX activity classes.
- [Key generation builder](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder) and [biometric guide](https://developer.android.com/identity/sign-in/biometric-auth) describe per-operation authentication. P0 deliberately excludes device-credential fallback. Each encrypt/decrypt operation uses its own cipher-bound authentication; prompt success alone is not proof of cryptographic success.

Review actual key hardware/authentication properties and enforce lifecycle/session validity before crypto use and result publication. JVM tests cannot establish these platform properties. Record device-dependent behavior, including prompt/background lifecycle interactions, in device-validation.md.
