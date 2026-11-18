# Ordered SSH configuration authentication methods

## Scope and boundary

CSCP/CSPP v5 records the ordered subset of supported, enabled methods from the
actual pinned OpenSSH Options. It is a prerequisite for the planned server-driven
connection flow; it does not discover server methods, select a credential, grant
secret access, or change the existing explicit authentication paths.

The preferred list is the first effective `PreferredAuthentications` value under
the pinned Host/Include parser. Preserve the first occurrence of each supported
method, remove disabled methods, and refuse unsupported active declarations as
before. Without a preference, use the pinned client's supported order:
publickey, keyboard-interactive, password. Repeated names do not introduce extra
credential attempts. A real subsequent PARTIAL is a separate protocol stage.

## Wire and ownership

In the v4 header insert a big-endian u32 immediately after server-alive count.
Its high byte is zero; its next three bytes are ordered method codes: publickey=1,
keyboard-interactive=2, password=3, followed by zero bytes. The Java decoder
strictly rejects nonzero high byte, unknown values, gaps, duplicates, and any
sequence that does not exactly match the enabled method flags. Empty order is
valid only if all three methods are disabled. v1–v4 have unknown order, represented
by `authenticationOrderKnown=false` and an empty immutable list; never infer
their missing preference for a future automatic authentication choice.

The connector freezes both known/unknown status and the entire decoded order
across every password, key and KBI protected configuration snapshot. An effective
change fails `VAULT_CHANGED`. Manual connections have no config order. This field
does not itself invoke any authentication, alter credentials, or approve fallback.

## Required verification

Use real pinned Options for default, explicit reorder, duplicate, disabled,
malformed and Host/Include first-value cases in both direct/profile modes. Test
v1–v4 compatibility, all v5 method permutations, invalid encodings, immutable
projection, snapshot mismatch and existing RequestTTY/keepalive flags. JVM mocks,
ARM64 synthetic config runtime, actual Android JNI and server-driven end-to-end
behavior are separate evidence.
