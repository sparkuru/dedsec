# Authentication integration findings — 2026-10-19

The continuous Android/JNI and private-key stage is implemented. Authoritative
contracts are in .trellis/spec/backend/ssh-native.md and app-secrets.md; actual
commands, artifacts, intermediate failures, test matrices and limits are in
design/verification.md#20261019-android-auth.

- Real AppSecrets loan120s and native role max60s must compose by containment;
  both client admission and hostfile issuer had the same equality and both needed fixing.
- A final guard before protectedEffect is insufficient when protectedEffect reads the
  store: repeat role/host/KEX/selected guard after that read, immediately before receiver.
- Expected Ed25519 public equality alone does not prove serialized private seed consistency;
  local verification is mandatory before any USERAUTH packet is queued.
- Passphrase entry identity is key identity, while capability target is actual remote:
  use one new typed atomic capability and immutable CSASv2, not generic target substitution.
- Inspect imported OpenSSH bcrypt parameters before KDF; reject unbounded encrypted PKCS8.
  Pinned OpenSSL pem2der maps traditional RSA/EC labels to type-specific DER, separate
  from EncryptedPrivateKeyInfo; strict complete armor/header validation prevents prefix smuggling.
- Own approved known_hosts CAS may preserve only its identical attached loan while advancing
  epoch; generic mutation/lock/re-unlock/old file cancellation remain revocation boundaries.
- With fixture properties Gradle aliases testReleaseUnitTest to testSecretsFixtureReleaseUnitTest;
  read that fresh XML path. Final normal244 test run is separate and successful.

No real account/vault, normal APK installation, dist publication, commit or archive.
Broader product channels/PTY/forward/service/UI and KBI/MFA/rekey compatibility remain open.
