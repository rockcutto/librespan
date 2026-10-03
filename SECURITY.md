# LibreSpan Security Policy

This document describes the release-level security model and reporting policy for LibreSpan.

## Supported versions

Security fixes target the latest published LibreSpan release. Older builds may not receive security
updates and should not be relied on once a newer security-relevant release is available.

## Reporting a vulnerability

Please use GitHub's private vulnerability reporting for this repository when the
**Report a vulnerability** option is available.

If private vulnerability reporting is unavailable, open a public issue only to request a private
contact channel. Do **not** include exploit details, credentials, recovery phrases, private message
content, account identifiers or other sensitive material in a public issue.

A useful private report includes the affected LibreSpan version, Android version, reproduction
steps, expected and observed behavior, and the security impact. Logs should be minimized and
sanitized before sharing.

## Message and transport security

- XMPP connections use TLS according to the capabilities and configuration of the selected server.
- End-to-end message encryption is provided by the current OMEMO implementation.
- OMEMO identity and device fingerprints are exposed in the UI so users can inspect and verify
  devices.
- Retired own OMEMO devices can be removed from the published device list and their published
  bundle/verification nodes can be deleted from the server.
- Removing an OMEMO device is not account revocation. A device that still has valid XMPP
  credentials may reconnect and announce itself again. For a lost or stolen device, changing the
  XMPP account password is an additional protective step when supported by the server.

## Protected local content

LibreSpan uses a Secure Content Store (SCS) for protected message payloads and media handled by the
protected-content paths.

The core properties are:

- content ownership is account-scoped;
- readers consume only committed protected objects;
- protected text and media use controlled read paths rather than public filesystem paths as their
  primary identity;
- new protected media is kept app-private by default;
- explicit user actions such as Save or Share are export boundaries;
- temporary staging and read-cache files are not the durable source of truth.

Logout or account disablement is not treated as secure-data destruction. Data-removal operations are
explicit and account-scoped.

## App Lock

App Lock is a UI access gate and is independent from High Security. It uses Android system
authentication, such as a strong biometric or the device PIN, pattern or password, according to
device capabilities and configuration.

App Lock does not by itself change the lifetime of the High Security application master key.

## High Security

High Security protects the application master key used for protected local data with Android
Keystore-backed, authenticated key wrapping. High Security requires Android 11 or later and
supported Android device authentication.

Its release-level behavior is:

- after a process restart or device reboot, protected local data and protected network operation
  remain locked until successful device authentication;
- after 24 hours without authenticated use, LibreSpan disconnects accounts and removes the working
  protected-data decryption capability from memory;
- normal unlock uses Android device authentication, such as a strong biometric or device
  credential;
- recovery uses a separate 12-word recovery phrase and recovery path;
- LibreSpan does not persist the recovery phrase;
- losing both usable device authentication and the recovery phrase can make locally protected data
  unrecoverable.

High Security and App Lock are separate controls: enabling High Security does not require App Lock
to be enabled.

These controls protect local application data and key access. They do not replace Android device
security and do not make a compromised operating system or an already-unlocked hostile device
trustworthy.

## Account credentials

XMPP account passwords authenticate to the remote XMPP server. They are separate from Android device
authentication and from the High Security recovery phrase.

When the server advertises the required capability, LibreSpan can change the XMPP password from the
account/security UI. Changing the server password is the appropriate additional step after losing a
device that may still possess valid account credentials.

## Device and key lifecycle

LibreSpan keeps local OMEMO trust/fingerprint history separate from the server's current device
announcement. Removing a retired device from the server therefore does not erase all local identity
history.

The current device cannot remove itself through the retired-device UI.

## Data-deletion boundaries

Local deletion does not imply deletion from server-side archives, from another participant's
device, or from the user's other devices. MUC leave/delete behavior is described in
[README.md](README.md).

## Explicit boundaries

LibreSpan does not claim to hide XMPP routing metadata from the XMPP server or network
infrastructure beyond what TLS and the XMPP/OMEMO protocols provide.
