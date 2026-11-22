# T227U Kernel Adiutor dictionary trainer

In Virtual Memory, tap **Train an LZ4 dictionary** and select up to
four apps. KA takes small samples from resident writable anonymous
pages in their main processes with a 30-second wait between rounds. It skips
duplicate and repeated-fill pages and collects up to 2,048 usable pages
in total, even if a single app supplies them. Sampling stops at that
limit or with **Stop sampling**. At least 128 usable pages are needed
to build if you stop early.

Check **Auto** at the top of the workload picker to rotate through running
launcher app main processes and Android System (`system_server`). In
Auto mode, check the processes to exclude. KA remembers those exclusions
when you start a session. Auto samples up to four sources per round and
keeps the same 2,048-page total limit. Android System can also be selected
in manual mode.

Use **Build when idle** after closing the workload. KA checks idle CPU
time and available RAM before building. If the tablet is too busy, it
keeps the samples in memory so you can retry. A successful build saves
exactly 16 KiB of trained LZ4 content. The notification shows
progress and has actions to stop sampling, build, or discard the samples.
After completion it can start another session with the same apps.

Results show dictionary size, total usable pages, and the two largest
contributors by unique-page count. Candidate filenames include short
names for those contributors and a unique suffix. All candidates live
in `Download/KernelAdiutor` beside any existing dictionaries. Tap the
**LZ4 dictionary** card to choose a file or plain LZ4. The choice takes
effect the next time KA initializes ZRAM via Apply on Boot or **Apply
ZRAM size**. Already active boot swap is left alone. KA keeps the
contributor report in its settings under the original filename.

Keep dictionaries from personal workloads local. They can contain
fragments from the selected apps' memory. Complete rounds are checkpointed
in a root-only tmpfs directory. If Android kills KA, the trainer can
resume from the last complete round when the service restarts. If Android
leaves the service stopped, reopen KA and tap **Resume LZ4 training**.
Saving or discarding a session clears its checkpoint. A tablet reboot
clears all volatile checkpoints.

## Build

From the KA checkout in WSL, run `sh tools/build_zram_trainer.sh`.
The script builds an ARM64 helper with the AArch64 GCC cross compiler
and Zstandard v1.5.7 pinned to `f8745da6ff1ad1e7bab384bd1f9d742439278e99`.
Then run `gradlew.bat --offline :app:assembleDebug` on Windows. The
generated helper and upstream license are embedded in the APK.

Local artifact: `E:\KA-Trainer.apk`

APK SHA-256: `7f73e4f8bb48843a756e8d466b981d8cc629ec43827abfd28d7a0ca3bab38d5c`

Auto update: `E:\KA-Trainer-Auto.apk`

APK SHA-256: `f57d35746fc1b4a588e5118991671d402a1a033229db84f9990dbd6e2d0fa314`

30-second update: `E:\KA-Trainer-30s.apk`

APK SHA-256: `bff83eb14346d129b9e4caec26b2cc2e9d39b65093e9a50aabf417b2d1880d09`

Full-capacity update: `E:\KA-Trainer-Full.apk`

APK SHA-256: `d6999647791e970eef4fa8b661afcb82167dba0cdf98a81ad3780d253462458e`

Checkpoint recovery update: `E:\KA-Trainer-Resume.apk`

APK SHA-256: `950af4c4a0939b5bf22ff09f6aaf3aa0805605aa2cc49ea6b5e08e8141ca6a63`

The signed APK is installed on the tablet and published as
`KernelAdiutor-T227U-ZRAM.apk` in the
[T227U KA prerelease](https://github.com/senposage/KernelAdiutor/releases/tag/t227u-ka-lz4d).
The installed and downloaded APK hashes matched `E:\KernelAdiutor-T227U-ZRAM.apk`.
The active 16,235-byte test dictionary survived the app update with
zero ZRAM I/O errors.

## Verification

- The ZRAM setup harness passed named-file selection, plain LZ4,
  fallback, active-swap protection, and failed swapoff.
- The SM-T227U captured 768 synthetic pages, of which 474 were usable,
  and generated a 16,294-byte raw dictionary. Its live dictionary
  remained 16,384 bytes.
- The installed KA UID could start the ARM64 helper from its private
  cache through KernelSU. The new APK's interactive notification and
  picker were not exercised on the tablet.
- The debug APK signature verified and matched the previous T227U KA APK.
- The Auto update was installed on the T227U. The workload picker
  showed the Auto checkbox, exclusion mode, and the Android System
  entry. The ZRAM setup harness passed again.
- The full-capacity trainer produced exactly 16,384 raw bytes from 2,048
  synthetic pages in WSL, and an LZ4 compression round trip passed.
- On the tablet, a synthetic checkpoint rejected a partial write,
  committed a complete record atomically, and prevented reads from
  the unprivileged KA UID. In WSL, the native trainer rejected short
  and oversized checkpoint streams and emitted a full 16 KiB dictionary
  from 2,048 complete records.
- Android reported `LOW_MEMORY` for the original sampler exit.
  Android's service restart and UI recovery still need an end-to-end
  test with the recovery APK.

## T227U CPU hotplug recovery

- On build #30, the tablet reported CPUs 0, 5, and 6 online.
  The KA settings still held 18 writes and permission changes for
  legacy per-core online controls. The saved commands disabled CPUs
  1–4 and 7 and gave those sysfs nodes read-only permissions.
- The settings were backed up and those 18 records were removed on the
  tablet. The CPU node permissions were restored and all eight CPUs
  came online. An unpinned eight-worker run advanced all eight CPU
  counters, including CPUs 4–7.
- Apply on Boot now skips the old T227U CPU online and permission
  commands. Legacy governor records resolve to the stable cluster
  policy path, and current T227U governor controls use that path.
  CPU usage reads the numeric CPU ID from each /proc/stat row.
- The host check covered missing CPU rows, a CPU coming online
  between samples, and command matching. The Windows offline debug
  build passed. The APK signer matched the previous T227U APK, and
  an in-place ADB install succeeded with all eight CPUs online.
- Local APK E:\KA-T227U-CPU.apk has SHA-256
  4dc4ab8d583d8afbe0cf667dcb95ec5fa2314060acfa97a3d20ed4855fa96317.

### Automatic cleanup in version code 263

- On T227U launch and Apply on Boot, KA queues a migration on its
  settings writer. It removes old CPU online and chmod commands
  from the CPU boot settings. The update writes to a temporary file
  and renames it after syncing. A run with no matching records leaves
  the file untouched.
- The matcher identified all 18 obsolete entries in the archived
  tablet profile, and the remaining profile matched the original
  manual cleanup. An in-place update on the tablet removed two
  harmless seeded records on launch, preserved all 32 existing
  settings, and left CPUs 0–7 online.
- Windows offline debug build and same-signer APK verification passed.
  Local APK E:\KA-T227U-263.apk has SHA-256
  5cc1d0674d195087dd1156e0f19f85aeb4942b2eb33e1cd87744a4cc1ce3dfd8.
