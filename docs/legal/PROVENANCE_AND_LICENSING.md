# LibreSpan provenance and licensing

Status: **0.9.0 current provenance record**  
Repository working name: `NeoCont`  
Public product name: **LibreSpan**  
Last reviewed: **2026-10-02**  
Development branch at review: `wip/xep-0425-librespan`

## 1. Purpose

This document records the source lineage, file-level provenance classes, and bundled notice policy used by LibreSpan.

It does not replace the repository license and does not relicense upstream code. Its purpose is to distinguish inherited GPL application code, independently authored LibreSpan files, and externally sourced protocol data/assets.

This is the maintained provenance record for the 0.9 line. Historical audit checkpoints and
implementation worklogs are intentionally not kept as release documentation.

## 2. Source lineage

The project lineage is:

```text
Conversations — Daniel Gultsch (iNPUTmice)
    ↓
Narayana fork line / Conversations Classic / AnotherIM
    ↓
NeoCont (working repository/project name)
    ↓
LibreSpan (public product name)
```

**Conversations** is the principal upstream ancestor. Its original developer and principal upstream maintainer is **Daniel Gultsch (`iNPUTmice`)**:

- https://codeberg.org/iNPUTmice

The direct source baseline used for this fork was **AnotherIM**, reached through the historical **Narayana** fork line that also included Conversations Classic. The repository still contains historical Narayana source/protocol identifiers, including a Conversations Classic capability-node reference; those identifiers are provenance traces rather than current LibreSpan branding.

Upstream per-file copyright/license headers remain authoritative for individual inherited files.

## 3. Repository-level license

The repository root `LICENSE` contains the **GNU General Public License version 3**.

The LibreSpan Android application is a derivative work of the GPLv3 Conversations/AnotherIM lineage and is distributed as a GPLv3 application. Upstream notices and applicable GPL obligations are retained.

A file-level license on independently authored LibreSpan code does **not** relicense inherited GPL code and does not remove the GPL obligations that apply when the Android application is distributed as a whole.

The release-level attribution summary is `NOTICE`. A copy is bundled in the APK assets as:

```text
src/main/assets/licenses/NOTICE.txt
```

## 4. Provenance classes

### 4.1 UPSTREAM-CONVERSATIONS

Code inherited from Conversations, directly or through AnotherIM.

Typical examples include the long-lived XMPP engine, entities, services, UI framework, database layer, OMEMO/Jingle integration, and other pre-existing application code.

Individual source-file copyright/license headers remain authoritative and are intentionally preserved.

### 4.2 UPSTREAM-ANOTHERIM

Code or modifications originating in the AnotherIM baseline used to create the fork.

This is GPL-derived application code. A file is not LibreSpan-native merely because it differs from current Conversations.

### 4.3 NATIVE-CORE — validated

Files marked:

```text
Copyright (c) 2026 rockcutto
SPDX-License-Identifier: MPL-2.0
Provenance: NATIVE-CORE
```

are locally authored, transport/application-independent security or storage components intended to remain reusable at file level under MPL-2.0.

The audited NATIVE-CORE set was introduced as new project files rather than renamed upstream files. Representative validated files include:

- `SecureContentStore.kt`
- `SecureContentCrypto.kt`
- `SecureContentHandle.kt`
- `SecureContentMetadata.kt`
- `SecureContentMetadataRecord.kt`
- `SecureContentMetadataStore.kt`
- `SecureContentBlobStore.kt`
- `SecureContentKeyMaterialStore.kt`
- `SecureContentObject.kt`
- `SecureContentAssociatedData.kt`
- `SecureContentAccountAuthority.kt`
- `SecureContentStoragePolicy.kt`
- `ContentExportCoordinator.kt`
- `SecureContentTransferBinding.kt`
- `SecureContentTransferGateway.kt`
- `SecureContentAuthenticatedMetadataCache.kt`
- `SecureContentMessageRelationIndex.kt`
- `SecureContentScopedRead.kt`
- `SecureMediaPerfTrace.kt`
- `SecureColdStartPerfTrace.kt`
- `AccountSecretVaultV1.kt`
- `AppMasterKeyAccountAeadV1.kt`
- `SecureContentAccountKeyMigrationManifestV1.kt`

The initial Secure Content abstractions were introduced as new files in `380804d2cc` on 2026-09-13. Provenance-marking commits include `cbd9f7d2386a` and `b5bfac439e07`.

The cryptolock/recovery NATIVE-CORE layer was also audited separately. Creation-history evidence includes:

- `72ab87d1af` — recovery phrase foundation;
- `3146bac848` — App Master Key / recovery wrapper core;
- `c5688574c4` — activation record core;
- `a0b1759051` — inactive-device privacy record core;
- `1762eae179` — recovery phrase v2/format core.

External word-list data used by that package is classified separately below and is **not** claimed as NATIVE-CORE.

### 4.4 NATIVE-ANDROID — validated

Files marked:

```text
Copyright (c) 2026 rockcutto
SPDX-License-Identifier: MPL-2.0
Provenance: NATIVE-ANDROID
```

are locally authored Android-specific implementations of native security/storage contracts.

For the 0.9 audit, every currently marked NATIVE-ANDROID file was checked against its path history. The earliest path entry for each audited file is an **added** file in LibreSpan/NeoCont history; no audited file is a rename of an inherited Conversations/AnotherIM file.

Validated Secure Content Android files:

- `AccountProtectedSecureContentMetadataStore.kt`
- `AccountProtectedSecureContentRecoveryStore.kt`
- `AndroidKeystoreSecureContentRootAead.kt`
- `AndroidKeystoreSecureMessageSearchMacProvider.kt`
- `AndroidSecureMessageMediaThumbnailReader.kt`
- `InternalSecureContentBlobStore.kt`
- `PersistentSecureContentKeyMaterialStore.kt`
- `RuntimeSecureContentStore.kt`
- `SecureContentStoreProvider.kt`
- `TinkSecureContentCryptoEngine.kt`

The original provenance-marking commit for that set is:

- `980e7a855906` — `chore: mark native secure storage android provenance`

Validated High Security / cryptolock Android files:

- `ActivationJournalAeadV1.kt`
- `AndroidAuthBoundAppMasterKeyWrapperV1.kt`
- `InactiveDeviceActivationRecoveryCoordinatorV1.kt`
- `InactiveDeviceDeactivationRecoveryCoordinatorV1.kt`
- `InactiveDevicePrivacyRuntimeV1.kt`
- `InactiveDevicePrivacyStateAeadV1.kt`
- `InactiveDevicePrivacyStoreV1.kt`
- `InactiveDeviceProtectionActivationStoreV1.kt`
- `InactiveDeviceProtectionActivationTransactionV1.kt`
- `InactiveDeviceProtectionDeactivationV1.kt`
- `InactiveDeviceRecoveryRepairTransactionV1.kt`
- `NormalWrappedAppMasterKeyRecordV1.kt`
- `SecureContentCryptoSessionRuntimeV1.kt`

Creation history remains available in the repository history and in the file-level provenance
headers. The legacy one-off 0.9 audit checkpoint has been removed from maintained documentation.

### 4.5 External recovery protocol data

The recovery package intentionally contains externally sourced data with its own licenses.

**BIP-0039 English word list**

- vendored verbatim in `RecoveryPhraseEnglishWordListV1.kt`;
- upstream: `bitcoin/bips`, `bip-0039/english.txt`;
- BIP-0039 authors: Marek Palatinus, Pavol Rusnak, Aaron Voisine, Sean Bowe;
- upstream BIP-0039 license declaration: **MIT**;
- local source header retains `SPDX-License-Identifier: MIT`;
- bundled attribution: `src/main/assets/licenses/recovery/BIP39-NOTICE.txt`;
- bundled MIT terms: `src/main/assets/licenses/recovery/BIP39-MIT.txt`.

**LibreSpan Russian recovery dictionary v1**

- dictionary ID: `librespan-ru-pairs-v1`;
- frequency basis: Digital Pushkin Lab `Russian-Word-Frequency-Lists-for-Children`, **CC0-1.0**;
- lexical/POS/proper-name validation: OpenRussian / `Badestrand/russian-dictionary`, **CC-BY-SA-4.0**;
- resulting curated ordered adjective/noun lists are distributed as **CC-BY-SA-4.0** protocol data;
- source files carry `Provenance: LIBRESPAN-DATA`;
- the immutable protocol lists live in
  `RecoveryPhraseRussianAdjectiveListV1.kt` and `RecoveryPhraseRussianNounListV1.kt`;
- source/license attribution is retained in the root `NOTICE` and bundled
  `src/main/assets/licenses/NOTICE.txt`.

## 5. Application integration code

New code is not automatically NATIVE-CORE or NATIVE-ANDROID merely because it was written during LibreSpan development.

Files that directly integrate with inherited application structures such as:

- `DatabaseBackend`
- `Message`
- `Conversation`
- `FileBackend`
- `XmppConnectionService`
- HTTP/Jingle integration paths
- Android Activity/Fragment/UI flows
- App Lock presentation/gating integration

remain in the GPL application integration layer unless a specific provenance audit establishes otherwise.

This boundary is intentional.

## 6. Rules for new native-marked files

Before adding `Provenance: NATIVE-CORE` or `Provenance: NATIVE-ANDROID`, verify that:

1. the file was created as new project code rather than copied, renamed, or adapted from upstream implementation;
2. no non-trivial upstream implementation was copied into it;
3. reused algorithms/specifications are implemented independently or under a compatible license;
4. dependencies on inherited application code are understood;
5. the intended file-level license is compatible with its dependencies and distribution context.

When uncertain, leave the file in the GPL application layer until a provenance audit establishes otherwise.

## 7. Brand and provenance

**LibreSpan** is the public product name.

**NeoCont** is the historical/working project and repository name. Occurrences of NeoCont in package names, diagnostics, migration identifiers, MIME/magic identifiers, or source keys are not evidence of a different lineage and are not automatically public-branding defects.

Branding, logos, and trademarks are separate from source-code licensing.

## 8. Bundled notices and third-party material

The 0.9 release package contains a release attribution record and relevant license texts under `src/main/assets/licenses/`.

Current bundled notice/license material includes:

- LibreSpan Android application — GPLv3;
- independently authored NATIVE-CORE/NATIVE-ANDROID files — MPL-2.0;
- Onest — SIL Open Font License 1.1;
- Tabler Icons — MIT;
- BIP-0039 English recovery word list — upstream MIT declaration and authorship notice;
- LibreSpan Russian recovery dictionary source basis — CC0-1.0 / CC-BY-SA-4.0;
- curated Russian recovery dictionary — CC-BY-SA-4.0;
- retained Android Open Source Project snippets — Apache-2.0;
- repository `art/` workspace — retained CC-BY-SA-4.0 license where no file-specific notice applies.

The release-level summary exists both as root `NOTICE` and bundled `assets/licenses/NOTICE.txt`.

Third-party Gradle libraries remain under their respective upstream licenses and metadata. This provenance record does not overwrite or aggregate those licenses into LibreSpan's GPL/MPL classifications.

## 9. 0.9 provenance status

Completed for the 0.9 provenance/NOTICE gate:

- repository root GPLv3 check;
- correction and confirmation of source lineage;
- retained AnotherIM/Narayana compatibility-trace classification;
- NATIVE-CORE creation-history and dependency-boundary review;
- NATIVE-ANDROID file-by-file creation-history review;
- cryptolock/recovery package provenance review;
- external recovery word-list/data classification;
- bundled static asset/data license inventory;
- retained AOSP notice review;
- root `NOTICE` creation;
- APK-assets NOTICE/license bundle;
- About-screen source/notices link;
- final public-release attribution review within this source/bundled-material scope.

No unresolved provenance/NOTICE item remains in this scope for **LibreSpan 0.9.0**.

Dependency version/security upgrades, future SBOM automation, and legal review by counsel are separate concerns and do not change the provenance classifications recorded here.
