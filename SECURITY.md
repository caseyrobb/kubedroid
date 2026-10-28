# Security Policy

## Reporting a vulnerability

Please report security issues **privately**:

- GitHub security advisories: *Report a vulnerability* on the repository
  (when the repo is public), or
- Email: **caseyrobb@pm.me**

Include: affected version, the attack path, and (if possible) a reproducer.
We aim to acknowledge within 7 days; critical issues faster. Please avoid
public disclosure until a fix is available.

## What "secure" means for kubedroid

kubedroid handles the most sensitive artifact in a Kubernetes cluster —
the kubeconfig. The security posture is defined in
[`docs/03-cluster-connection.md`](docs/03-cluster-connection.md) and is
part of the spec contract:

- **At rest**: kubeconfig material is encrypted with an Android Keystore
  AES-256-GCM key (non-exportable); the registry stores only opaque blob
  references. Decrypted material is zeroized from heap buffers after use
  where practical.
- **In transit**: TLS to the API server, verified against the CA pinned
  in the kubeconfig. No proxying, no third-party endpoints.
- **In logs**: bearer tokens, client certificates, private keys, and CA
  material are redacted from all logging paths; crash reports never carry
  cluster content.
- **No analytics / no telemetry**: the app contacts only API servers you
  explicitly configure.
- **Least capability**: no `exec`, no port-forwarding, no shell; writes
  (edit/delete) are explicit, confirmed, and guarded by optimistic
  concurrency.

## Scope notes

- Physical device compromise with an unlocked, rooted device and an
  active session is out of scope (as with any credential store).
- A malicious API server can always return hostile content; kubedroid
  treats all API responses as untrusted display data, but *you* remain
  the trust boundary for which clusters you import.
- QR kubeconfig transfer is chunked and validated before use, but only
  import QR codes you generated yourself (see `tools/qr-export`).

## Supported versions

| Version       | Supported          |
| ------------- | ------------------ |
| latest v0.1.x | :white_check_mark: |
| older         | :x:                |
