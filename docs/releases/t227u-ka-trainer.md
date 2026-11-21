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

The same signed APK is staged as `E:\KernelAdiutor-T227U-ZRAM.apk` for
the [T227U KA prerelease](https://github.com/senposage/KernelAdiutor/releases/tag/t227u-ka-lz4d).
The tablet disconnected before installation. Install and verify the new
APK before replacing the published `KA-LZ4D.apk` asset.

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
- Android reported `LOW_MEMORY` for the original sampler exit. The
  running capture was preserved while the recovery build was staged.
  Android's service restart and UI recovery still need an end-to-end
  test with the new APK.
